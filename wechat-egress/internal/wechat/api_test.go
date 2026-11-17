package wechat

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/png"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestBaseURLIsNotAnOpenProxy(t *testing.T) {
	ok := []string{"", "https://api.weixin.qq.com", "https://API.WEIXIN.QQ.COM/", "http://127.0.0.1:9", "http://localhost:9"}
	for _, raw := range ok {
		if _, err := ValidateBase(raw); err != nil {
			t.Errorf("ValidateBase(%q) unexpected error %v", raw, err)
		}
	}
	bad := []string{
		"http://api.weixin.qq.com",
		"https://api.weixin.qq.com/cgi-bin",
		"https://api.weixin.qq.com/?x=1",
		"https://user:pass@api.weixin.qq.com",
		"https://example.com",
		"https://api.weixin.qq.com.evil.example",
		"http://8.8.8.8",
		"https://127.0.0.1.example.com",
	}
	for _, raw := range bad {
		if _, err := ValidateBase(raw); err == nil {
			t.Errorf("ValidateBase(%q) was accepted", raw)
		}
	}
}

func TestStableTokenUploadAndDraftsStayOnLoopback(t *testing.T) {
	var mu sync.Mutex
	var seen []string
	tokenHits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Hostname() != "" && r.Host != "" && !strings.HasPrefix(r.Host, "127.0.0.1:") {
			t.Errorf("host %s", r.Host)
		}
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		mu.Lock()
		seen = append(seen, r.URL.Path)
		mu.Unlock()
		switch r.URL.Path {
		case pathStableToken:
			tokenHits++
			if tokenHits > 1 {
				t.Errorf("stable_token called %d times", tokenHits)
			}
			if !bytes.Contains(body, []byte(`"force_refresh":false`)) || !bytes.Contains(body, []byte(`"appid":"wx-server"`)) || !bytes.Contains(body, []byte(`"secret":"server-secret-value"`)) {
				t.Fatalf("token body %s", body)
			}
			writeJSON(w, map[string]any{"access_token": "access-token-value", "expires_in": 7200})
		case pathUploadImage:
			if r.URL.Query().Get("access_token") != "access-token-value" {
				t.Errorf("upload token %q", r.URL.RawQuery)
			}
			_, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if err != nil {
				t.Fatal(err)
			}
			mr := multipart.NewReader(bytes.NewReader(body), params["boundary"])
			part, err := mr.NextPart()
			if err != nil {
				t.Fatal(err)
			}
			if part.FormName() != "media" {
				t.Fatalf("form name %q", part.FormName())
			}
			img, _ := io.ReadAll(part)
			if !bytes.HasPrefix(img, []byte{0x89, 'P', 'N', 'G'}) {
				t.Fatal("upload did not contain the png")
			}
			writeJSON(w, map[string]any{"url": "http://mmbiz.qpic.cn/test", "errcode": 0, "errmsg": "ok"})
		case pathDraftAdd:
			var draft struct {
				Articles []Article `json:"articles"`
			}
			if err := json.Unmarshal(body, &draft); err != nil {
				t.Fatal(err)
			}
			if len(draft.Articles) != 1 || draft.Articles[0].Title != "标题" || draft.Articles[0].Content != "<p>a&b</p>" {
				t.Fatalf("add body %s", body)
			}
			if bytes.Contains(body, []byte(`\u003c`)) {
				t.Fatalf("content was escaped: %s", body)
			}
			if draft.Articles[0].NeedOpenComment == nil || *draft.Articles[0].NeedOpenComment != 1 {
				t.Fatal("need_open_comment was not forwarded")
			}
			if len(draft.Articles[0].CoverCrops()) != 1 || draft.Articles[0].CoverCrops()[0].Ratio != "2.35_1" {
				t.Fatal("cover crop was not forwarded")
			}
			writeJSON(w, map[string]any{"media_id": "draft-media"})
		case pathDraftUpdate:
			var draft struct {
				MediaID  string  `json:"media_id"`
				Index    int     `json:"index"`
				Articles Article `json:"articles"`
			}
			if err := json.Unmarshal(body, &draft); err != nil {
				t.Fatalf("update should be an object: %v body %s", err, body)
			}
			if draft.MediaID != "draft-media" || draft.Index != 0 || draft.Articles.Title != "新标题" {
				t.Fatalf("update body %s", body)
			}
			writeJSON(w, map[string]any{"errcode": 0, "errmsg": "ok"})
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	api := newTestAPI(t, srv, nil)
	ctx := context.Background()
	token, expires, err := api.StableToken(ctx)
	if err != nil || token != "access-token-value" || expires != 7200 {
		t.Fatalf("token %q expires %d err %v", token, expires, err)
	}
	if _, _, err := api.StableToken(ctx); err != nil {
		t.Fatal(err)
	}
	url, err := api.UploadImage(ctx, pngBytes(t), "cover.png")
	if err != nil || url != "http://mmbiz.qpic.cn/test" {
		t.Fatalf("upload %q %v", url, err)
	}
	open := 1
	article := WithCrops(Article{
		Title:            "标题",
		Author:           "Lightwing",
		Digest:           "摘要",
		Content:          "<p>a&b</p>",
		ContentSourceURL: "https://example.com/posts/demo/",
		ThumbMediaID:     "thumb123",
		NeedOpenComment:  &open,
	}, []Crop{{Ratio: "2.35_1", X1: "0", Y1: "0.1", X2: "1", Y2: "0.52"}})
	mediaID, err := api.AddDraft(ctx, []Article{article})
	if err != nil || mediaID != "draft-media" {
		t.Fatalf("add %q %v", mediaID, err)
	}
	article.Title = "新标题"
	if err := api.UpdateDraft(ctx, mediaID, 0, article); err != nil {
		t.Fatal(err)
	}
	if tokenHits != 1 {
		t.Fatalf("token hits %d", tokenHits)
	}
}

func TestUploadCoverUsesPermanentImageMaterial(t *testing.T) {
	var gotPath, gotType, gotToken string
	var gotBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		switch r.URL.Path {
		case pathStableToken:
			writeJSON(w, map[string]any{"access_token": "access-token-value", "expires_in": 7200})
		case pathAddMaterial:
			gotPath = r.URL.Path
			gotType = r.URL.Query().Get("type")
			gotToken = r.URL.Query().Get("access_token")
			gotBody = append([]byte(nil), body...)
			if r.URL.Query().Get("type") != "image" {
				t.Errorf("type %q", r.URL.RawQuery)
			}
			writeJSON(w, map[string]any{"media_id": "cover-media", "url": "http://mmbiz.qpic.cn/cover"})
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	api := newTestAPI(t, srv, nil)
	mediaID, err := api.UploadCover(context.Background(), pngBytes(t), "cover.png")
	if err != nil || mediaID != "cover-media" {
		t.Fatalf("cover %q %v", mediaID, err)
	}
	if gotPath != pathAddMaterial || gotType != "image" || gotToken != "access-token-value" {
		t.Fatalf("path %s type %s token %s", gotPath, gotType, gotToken)
	}
	if !bytes.Contains(gotBody, []byte("image/png")) || !bytes.Contains(gotBody, pngBytes(t)) {
		t.Fatal("cover body was not the png")
	}
	if bytes.Contains(gotBody, []byte("api.weixin.qq.com")) {
		t.Fatal("cover body named the WeChat host")
	}
}

func TestRejectsBadDraftsWithoutCallingUpstream(t *testing.T) {
	called := 0
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called++
	}))
	defer srv.Close()
	api := newTestAPI(t, srv, nil)
	_, err := api.AddDraft(context.Background(), []Article{{
		Title:   "标题",
		Content: strings.Repeat("字", 20000),
	}})
	if err == nil {
		t.Fatal("oversized content was accepted")
	}
	big := make([]byte, 1<<20)
	copy(big, []byte{0xff, 0xd8, 0xff})
	_, err = api.UploadImage(context.Background(), big, "")
	if err == nil || !strings.Contains(err.Error(), "1MB") {
		t.Fatalf("oversized image error = %v", err)
	}
	_, err = api.UploadImage(context.Background(), []byte("not an image"), "")
	if err == nil {
		t.Fatal("plain text was accepted as an image")
	}
	if called != 0 {
		t.Fatalf("upstream calls = %d", called)
	}
}

func TestDoesNotFollowRedirectsOrUseProxy(t *testing.T) {
	followed := 0
	other := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		followed++
	}))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusFound)
	}))
	defer srv.Close()
	api := newTestAPI(t, srv, nil)
	if _, _, err := api.StableToken(context.Background()); err == nil {
		t.Fatal("redirect was treated as success")
	}
	if followed != 0 {
		t.Fatal("redirect was followed")
	}

	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	t.Setenv("http_proxy", "http://127.0.0.1:1")
	direct := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"access_token": "access-token-value", "expires_in": 100})
	}))
	defer direct.Close()
	api, err := New(Config{AppID: "wx-server", Secret: "server-secret-value", BaseURL: direct.URL})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := api.StableToken(context.Background()); err != nil {
		t.Fatalf("proxy was used or the call failed: %v", err)
	}
}

func TestErrorsRedactSecrets(t *testing.T) {
	const secret = "server-secret-value"
	const issued = "access-token-value"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "stable_token") {
			writeJSON(w, map[string]any{"access_token": issued, "expires_in": 7200})
			return
		}
		http.Error(w, "boom "+r.URL.Query().Get("access_token")+" "+secret, http.StatusBadGateway)
	}))
	defer srv.Close()
	api := newTestAPI(t, srv, nil)
	_, err := api.UploadImage(context.Background(), pngBytes(t), "")
	if err == nil {
		t.Fatal("expected upload error")
	}
	if strings.Contains(err.Error(), issued) || strings.Contains(err.Error(), secret) {
		t.Fatalf("error leaked a secret: %s", err)
	}
}

func TestWeChatErrorIncludesCode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"errcode": 40164, "errmsg": "invalid ip"})
	}))
	defer srv.Close()
	api := newTestAPI(t, srv, nil)
	_, _, err := api.StableToken(context.Background())
	if err == nil || !strings.Contains(err.Error(), "40164") {
		t.Fatalf("error = %v", err)
	}
}

func newTestAPI(t *testing.T, srv *httptest.Server, client *http.Client) *API {
	t.Helper()
	if client == nil {
		client = srv.Client()
	}
	client.Transport = guardTransport{base: client.Transport, t: t}
	api, err := New(Config{
		AppID:   "wx-server",
		Secret:  "server-secret-value",
		BaseURL: srv.URL,
		HTTP:    client,
	})
	if err != nil {
		t.Fatal(err)
	}
	return api
}

type guardTransport struct {
	base http.RoundTripper
	t    *testing.T
}

func (g guardTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	host := req.URL.Hostname()
	if host != "127.0.0.1" && host != "::1" && host != "localhost" {
		g.t.Errorf("non-loopback request %s", req.URL)
		return nil, io.ErrClosedPipe
	}
	base := g.base
	if base == nil {
		base = http.DefaultTransport
	}
	return base.RoundTrip(req)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func pngBytes(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewNRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}
