// Package wechat calls the draft APIs on api.weixin.qq.com.
// It is not a general HTTP proxy: every request uses a fixed path on the
// configured origin, and that origin is either the official host or a
// loopback address used by tests.
package wechat

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/textproto"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	officialHost    = "api.weixin.qq.com"
	pathStableToken = "/cgi-bin/stable_token"
	pathUploadImage = "/cgi-bin/media/uploadimg"
	pathDraftAdd    = "/cgi-bin/draft/add"
	pathDraftUpdate = "/cgi-bin/draft/update"
	maxBody         = 1 << 20
)

// Invalid is a caller error. The gRPC server maps it to InvalidArgument.
type Invalid struct {
	Msg string
}

func (e *Invalid) Error() string { return e.Msg }

// CallError is a failure talking to WeChat, with secrets already removed.
type CallError struct {
	Op      string
	Kind    string
	ErrCode int
	ErrMsg  string
}

func (e *CallError) Error() string {
	if e.Kind == "wechat" {
		if e.ErrCode == 40164 {
			return fmt.Sprintf("%s: wechat 40164 %s (source IP is not on the API whitelist)", e.Op, e.ErrMsg)
		}
		return fmt.Sprintf("%s: wechat %d %s", e.Op, e.ErrCode, e.ErrMsg)
	}
	return fmt.Sprintf("%s: %s", e.Op, e.ErrMsg)
}

// Config configures the WeChat client. HTTP and Now are test seams.
type Config struct {
	AppID   string
	Secret  string
	BaseURL string
	HTTP    *http.Client
	Now     func() time.Time
}

// API is the only component that dials WeChat.
type API struct {
	base   *url.URL
	appID  string
	secret string
	http   *http.Client
	now    func() time.Time

	mu          sync.Mutex
	accessToken string
	refreshAt   time.Time
	expiresAt   time.Time
}

func New(cfg Config) (*API, error) {
	appID, err := credential("WECHAT_APPID", cfg.AppID)
	if err != nil {
		return nil, err
	}
	secret, err := credential("WECHAT_APPSECRET", cfg.Secret)
	if err != nil {
		return nil, err
	}
	base, err := ValidateBase(cfg.BaseURL)
	if err != nil {
		return nil, err
	}
	client := cfg.HTTP
	if client == nil {
		client = defaultHTTP()
	} else {
		copied := *client
		copied.CheckRedirect = refuseRedirect
		client = &copied
	}
	if client.Timeout == 0 {
		client.Timeout = 40 * time.Second
	}
	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	return &API{base: base, appID: appID, secret: secret, http: client, now: now}, nil
}

func credential(name, value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", fmt.Errorf("%s is required", name)
	}
	if strings.ContainsAny(value, " \t\r\n") {
		return "", fmt.Errorf("%s must not contain whitespace", name)
	}
	return value, nil
}

// ValidateBase allows https://api.weixin.qq.com, or an http(s) loopback
// origin for tests. Any other host is rejected so this process cannot be
// pointed at an arbitrary URL.
func ValidateBase(raw string) (*url.URL, error) {
	if strings.TrimSpace(raw) == "" {
		raw = "https://" + officialHost
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("WeChat API base: %w", err)
	}
	if u.User != nil {
		return nil, fmt.Errorf("WeChat API base must not contain userinfo")
	}
	if u.RawQuery != "" || u.Fragment != "" {
		return nil, fmt.Errorf("WeChat API base must not contain a query or fragment")
	}
	if u.Path != "" && u.Path != "/" {
		return nil, fmt.Errorf("WeChat API base must not contain a path")
	}
	host := u.Hostname()
	switch {
	case strings.EqualFold(host, officialHost):
		if u.Scheme != "https" {
			return nil, fmt.Errorf("%s must use https", officialHost)
		}
		if port := u.Port(); port != "" && port != "443" {
			return nil, fmt.Errorf("%s must use port 443", officialHost)
		}
	case isLoopback(host):
		if u.Scheme != "http" && u.Scheme != "https" {
			return nil, fmt.Errorf("loopback WeChat API base must be http or https")
		}
	default:
		return nil, fmt.Errorf("WeChat API base host %q is not allowed", host)
	}
	u.Path = ""
	u.RawPath = ""
	return u, nil
}

func isLoopback(host string) bool {
	if strings.EqualFold(strings.TrimSuffix(host, "."), "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func defaultHTTP() *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	return &http.Client{
		Timeout:       40 * time.Second,
		Transport:     transport,
		CheckRedirect: refuseRedirect,
	}
}

func refuseRedirect(*http.Request, []*http.Request) error {
	return http.ErrUseLastResponse
}

// StableToken returns a cached token, refreshing with force_refresh=false.
func (a *API) StableToken(ctx context.Context) (string, int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	if a.accessToken != "" && now.Before(a.refreshAt) {
		remain := int(a.expiresAt.Sub(now).Seconds())
		if remain < 1 {
			remain = 1
		}
		return a.accessToken, remain, nil
	}
	body := tokenRequest{
		GrantType:    "client_credential",
		AppID:        a.appID,
		Secret:       a.secret,
		ForceRefresh: false,
	}
	env, err := a.postJSON(ctx, "stable_token", pathStableToken, "", body)
	if err != nil {
		return "", 0, err
	}
	if env.AccessToken == "" || env.ExpiresIn <= 0 {
		return "", 0, &CallError{Op: "stable_token", Kind: "wechat", ErrMsg: "response did not include a token"}
	}
	a.accessToken = env.AccessToken
	a.expiresAt = now.Add(time.Duration(env.ExpiresIn) * time.Second)
	skew := 60 * time.Second
	lifetime := time.Duration(env.ExpiresIn) * time.Second
	if lifetime/2 < skew {
		skew = lifetime / 2
	}
	a.refreshAt = a.expiresAt.Add(-skew)
	return env.AccessToken, env.ExpiresIn, nil
}

func (a *API) invalidate() {
	a.mu.Lock()
	a.accessToken = ""
	a.mu.Unlock()
}

// UploadImage sends a jpeg or png to media/uploadimg and returns its URL.
// filename may be empty. A non-empty name must match the sniffed type and
// must not be a path.
func (a *API) UploadImage(ctx context.Context, content []byte, filename string) (string, error) {
	contentType, safeName, err := validateImage(content, filename)
	if err != nil {
		return "", err
	}
	var imageURL string
	err = a.withToken(ctx, func(token string) error {
		body, partType, err := imageBody(safeName, contentType, content)
		if err != nil {
			return err
		}
		env, err := a.post(ctx, "uploadimg", pathUploadImage, token, partType, body)
		if err != nil {
			return err
		}
		if env.URL == "" {
			return &CallError{Op: "uploadimg", Kind: "wechat", ErrMsg: "response did not include a url"}
		}
		imageURL = env.URL
		return nil
	})
	return imageURL, err
}

// AddDraft calls draft/add. articles is the WeChat articles array.
func (a *API) AddDraft(ctx context.Context, articles []Article) (string, error) {
	if err := validateArticles(articles); err != nil {
		return "", err
	}
	var mediaID string
	err := a.withToken(ctx, func(token string) error {
		env, err := a.postJSON(ctx, "draft/add", pathDraftAdd, token, addRequest{Articles: articles})
		if err != nil {
			return err
		}
		if err := validMediaID(env.MediaID); err != nil {
			return &CallError{Op: "draft/add", Kind: "wechat", ErrMsg: "response did not include a media_id"}
		}
		mediaID = env.MediaID
		return nil
	})
	return mediaID, err
}

// UpdateDraft calls draft/update. index 0 is the first article.
func (a *API) UpdateDraft(ctx context.Context, mediaID string, index int, article Article) error {
	if err := validMediaID(mediaID); err != nil {
		return err
	}
	if index < 0 || index > 7 {
		return &Invalid{Msg: "draft index must be from 0 to 7"}
	}
	if err := validateArticles([]Article{article}); err != nil {
		return err
	}
	return a.withToken(ctx, func(token string) error {
		_, err := a.postJSON(ctx, "draft/update", pathDraftUpdate, token, updateRequest{
			MediaID:  mediaID,
			Index:    index,
			Articles: article,
		})
		return err
	})
}

func (a *API) withToken(ctx context.Context, fn func(token string) error) error {
	token, _, err := a.StableToken(ctx)
	if err != nil {
		return err
	}
	err = fn(token)
	var call *CallError
	if errors.As(err, &call) && tokenRejected(call.ErrCode) {
		a.invalidate()
		token, _, err = a.StableToken(ctx)
		if err != nil {
			return err
		}
		return fn(token)
	}
	return err
}

func tokenRejected(code int) bool {
	switch code {
	case 40001, 40014, 42001:
		return true
	default:
		return false
	}
}

type tokenRequest struct {
	GrantType    string `json:"grant_type"`
	AppID        string `json:"appid"`
	Secret       string `json:"secret"`
	ForceRefresh bool   `json:"force_refresh"`
}

type addRequest struct {
	Articles []Article `json:"articles"`
}

type updateRequest struct {
	MediaID  string  `json:"media_id"`
	Index    int     `json:"index"`
	Articles Article `json:"articles"`
}

type envelope struct {
	ErrCode     *int   `json:"errcode"`
	ErrMsg      string `json:"errmsg"`
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
	URL         string `json:"url"`
	MediaID     string `json:"media_id"`
}

func (a *API) postJSON(ctx context.Context, op, path, token string, body any) (envelope, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(body); err != nil {
		return envelope{}, err
	}
	return a.post(ctx, op, path, token, "application/json; charset=utf-8", &buf)
}

func (a *API) post(ctx context.Context, op, path, token, contentType string, body io.Reader) (envelope, error) {
	u := *a.base
	u.Path = path
	if token != "" {
		q := u.Query()
		q.Set("access_token", token)
		u.RawQuery = q.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), body)
	if err != nil {
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: "could not build request"}
	}
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "blog-wechat-egress")
	resp, err := a.http.Do(req)
	if err != nil {
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: redactNet(err, token, a.secret)}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: "read failed"}
	}
	if len(raw) > maxBody {
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: "response is too large"}
	}
	var env envelope
	decErr := json.Unmarshal(raw, &env)
	if env.ErrCode != nil && *env.ErrCode != 0 {
		return envelope{}, &CallError{
			Op:      op,
			Kind:    "wechat",
			ErrCode: *env.ErrCode,
			ErrMsg:  clean(env.ErrMsg, token, a.secret),
		}
	}
	if resp.StatusCode >= 300 {
		msg := clean(env.ErrMsg, token, a.secret)
		if msg == "" {
			msg = fmt.Sprintf("HTTP %d", resp.StatusCode)
		}
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: msg}
	}
	if decErr != nil {
		return envelope{}, &CallError{Op: op, Kind: "network", ErrMsg: "response was not JSON"}
	}
	return env, nil
}

func imageBody(filename, contentType string, content []byte) (*bytes.Buffer, string, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := make(textproto.MIMEHeader)
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="media"; filename="%s"`, filename))
	h.Set("Content-Type", contentType)
	part, err := w.CreatePart(h)
	if err != nil {
		return nil, "", err
	}
	if _, err := part.Write(content); err != nil {
		return nil, "", err
	}
	if err := w.Close(); err != nil {
		return nil, "", err
	}
	return &buf, w.FormDataContentType(), nil
}

func redactNet(err error, secrets ...string) string {
	msg := err.Error()
	var uerr *url.Error
	if errors.As(err, &uerr) && uerr.Err != nil {
		msg = uerr.Err.Error()
	}
	return clean(msg, secrets...)
}

func clean(msg string, secrets ...string) string {
	msg = strings.TrimSpace(msg)
	if len(msg) > 200 {
		msg = msg[:200]
	}
	for _, secret := range secrets {
		if len(secret) < 8 {
			continue
		}
		msg = strings.ReplaceAll(msg, secret, "REDACTED")
		msg = strings.ReplaceAll(msg, url.QueryEscape(secret), "REDACTED")
	}
	return msg
}
