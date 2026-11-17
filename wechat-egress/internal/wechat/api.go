// Package wechat calls a fixed set of draft APIs on api.weixin.qq.com.
// It is not a general HTTP proxy: every request uses a fixed path on the
// configured origin, and that origin is either the official host or a
// loopback address used by tests. It does not call freepublish/submit or
// the mass-send APIs. The official host always verifies TLS certificates.
package wechat

import (
	"bytes"
	"context"
	"crypto/tls"
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
	officialHost        = "api.weixin.qq.com"
	pathStableToken     = "/cgi-bin/stable_token"
	pathUploadImage     = "/cgi-bin/media/uploadimg"
	pathAddMaterial     = "/cgi-bin/material/add_material"
	pathDraftAdd        = "/cgi-bin/draft/add"
	pathDraftUpdate     = "/cgi-bin/draft/update"
	pathDraftBatchGet   = "/cgi-bin/draft/batchget"
	pathPublishBatchGet = "/cgi-bin/freepublish/batchget"
	coverType           = "image"
	maxBody             = 1 << 20
	batchPageSize       = 20
	maxListedArticles   = 2000
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
	if err := rejectInsecureSkip(base, client); err != nil {
		return nil, err
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
	transport.TLSClientConfig = &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: false,
	}
	return &http.Client{
		Timeout:       40 * time.Second,
		Transport:     transport,
		CheckRedirect: refuseRedirect,
	}
}

// rejectInsecureSkip refuses a client that disables certificate checks when
// the origin is the real WeChat host. Loopback bases may skip verification
// so a local HTTPS stub can run without a private CA.
func rejectInsecureSkip(base *url.URL, client *http.Client) error {
	if client == nil || !transportSkipsVerify(client.Transport) {
		return nil
	}
	if isLoopback(base.Hostname()) {
		return nil
	}
	return fmt.Errorf("TLS certificate verification cannot be disabled for %s", base.Hostname())
}

func transportSkipsVerify(rt http.RoundTripper) bool {
	transport, ok := rt.(*http.Transport)
	if !ok || transport == nil || transport.TLSClientConfig == nil {
		return false
	}
	return transport.TLSClientConfig.InsecureSkipVerify
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

// UploadCover stores a jpeg or png as permanent image material and returns
// its media_id. draft/add uses that id as thumb_media_id. The material type
// is fixed to image; this is not a general add_material proxy.
func (a *API) UploadCover(ctx context.Context, content []byte, filename string) (string, error) {
	contentType, safeName, err := validateImage(content, filename)
	if err != nil {
		return "", err
	}
	var mediaID string
	err = a.withToken(ctx, func(token string) error {
		body, partType, err := imageBody(safeName, contentType, content)
		if err != nil {
			return err
		}
		env, err := a.postExtra(ctx, "add_material", pathAddMaterial, token, partType, body, url.Values{"type": {coverType}})
		if err != nil {
			return err
		}
		if err := validMediaID(env.MediaID); err != nil {
			return &CallError{Op: "add_material", Kind: "wechat", ErrMsg: "response did not include a media_id"}
		}
		mediaID = env.MediaID
		return nil
	})
	return mediaID, err
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

// ListedArticle is one news item already stored by WeChat. Content is not downloaded.
type ListedArticle struct {
	Title            string
	ContentSourceURL string
	MediaID          string
	ArticleID        string
}

// ListDrafts reads every draft via draft/batchget. It does not add or send.
func (a *API) ListDrafts(ctx context.Context) ([]ListedArticle, error) {
	return a.listArticles(ctx, "draft/batchget", pathDraftBatchGet, false)
}

// ListPublished reads freepublish/batchget. It does not call freepublish/submit.
func (a *API) ListPublished(ctx context.Context) ([]ListedArticle, error) {
	return a.listArticles(ctx, "freepublish/batchget", pathPublishBatchGet, true)
}

func (a *API) listArticles(ctx context.Context, op, path string, published bool) ([]ListedArticle, error) {
	var out []ListedArticle
	offset := 0
	for {
		var env envelope
		err := a.withToken(ctx, func(token string) error {
			got, err := a.postJSON(ctx, op, path, token, batchRequest{
				Offset:    offset,
				Count:     batchPageSize,
				NoContent: 1,
			})
			if err != nil {
				return err
			}
			env = got
			return nil
		})
		if err != nil {
			return nil, err
		}
		if len(env.Item) > batchPageSize {
			return nil, &CallError{Op: op, Kind: "wechat", ErrMsg: "batch page was larger than requested"}
		}
		for _, item := range env.Item {
			for _, news := range item.Content.NewsItem {
				listed := ListedArticle{
					Title:            strings.TrimSpace(news.Title),
					ContentSourceURL: strings.TrimSpace(news.ContentSourceURL),
				}
				if published {
					listed.ArticleID = item.ArticleID
				} else {
					listed.MediaID = item.MediaID
				}
				out = append(out, listed)
			}
		}
		got := len(env.Item)
		if got == 0 {
			break
		}
		step := env.ItemCount
		if step <= 0 {
			step = got
		}
		offset += step
		if env.TotalCount > 0 && offset >= env.TotalCount {
			break
		}
		if env.TotalCount <= 0 && got < batchPageSize {
			break
		}
		if offset > maxListedArticles || len(out) > maxListedArticles {
			return nil, &CallError{Op: op, Kind: "wechat", ErrMsg: "too many articles to compare safely"}
		}
	}
	return out, nil
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

type batchRequest struct {
	Offset    int `json:"offset"`
	Count     int `json:"count"`
	NoContent int `json:"no_content"`
}

type batchNews struct {
	Title            string `json:"title"`
	ContentSourceURL string `json:"content_source_url"`
}

type batchEntry struct {
	MediaID   string `json:"media_id"`
	ArticleID string `json:"article_id"`
	Content   struct {
		NewsItem []batchNews `json:"news_item"`
	} `json:"content"`
}

type envelope struct {
	ErrCode     *int         `json:"errcode"`
	ErrMsg      string       `json:"errmsg"`
	AccessToken string       `json:"access_token"`
	ExpiresIn   int          `json:"expires_in"`
	URL         string       `json:"url"`
	MediaID     string       `json:"media_id"`
	TotalCount  int          `json:"total_count"`
	ItemCount   int          `json:"item_count"`
	Item        []batchEntry `json:"item"`
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
	return a.postExtra(ctx, op, path, token, contentType, body, nil)
}

// postExtra adds a fixed query. Callers pass only constants they own.
// extra must not come from an RPC field: this process is not an open proxy.
func (a *API) postExtra(ctx context.Context, op, path, token, contentType string, body io.Reader, extra url.Values) (envelope, error) {
	u := *a.base
	u.Path = path
	q := u.Query()
	for key, values := range extra {
		for _, value := range values {
			q.Add(key, value)
		}
	}
	if token != "" {
		q.Set("access_token", token)
	}
	if len(q) > 0 {
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
