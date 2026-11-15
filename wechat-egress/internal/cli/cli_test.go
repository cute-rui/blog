package cli

import (
	"bytes"
	"context"
	"image"
	"image/png"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/lightwing/blog/wechat-egress/internal/egress"
)

func TestUsageAndMissingToken(t *testing.T) {
	var stderr bytes.Buffer
	if code := Run(nil, mapEnv(nil), ioDiscard{}, &stderr); code != 2 || !strings.Contains(stderr.String(), "wechat-egress") {
		t.Fatalf("code %d stderr %s", code, stderr.String())
	}
	stderr.Reset()
	if code := Run([]string{"help"}, mapEnv(nil), ioDiscard{}, &stderr); code != 0 {
		t.Fatalf("help code %d", code)
	}
	stderr.Reset()
	if code := Run([]string{"nope"}, mapEnv(nil), ioDiscard{}, &stderr); code != 2 {
		t.Fatalf("unknown code %d", code)
	}
	stderr.Reset()
	env := map[string]string{"WECHAT_EGRESS_ADDRESS": "127.0.0.1:1"}
	if code := Run([]string{"stable-token"}, mapEnv(env), ioDiscard{}, &stderr); code != 1 || !strings.Contains(stderr.String(), "WECHAT_EGRESS_TOKEN") {
		t.Fatalf("code %d stderr %s", code, stderr.String())
	}
}

func TestWorkflowCommandOnLoopback(t *testing.T) {
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/cgi-bin/media/uploadimg":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"url":"http://mmbiz.qpic.cn/from-cli","errcode":0}`))
		case "/cgi-bin/draft/add":
			_, _ = w.Write([]byte(`{"media_id":"draft-from-cli"}`))
		case "/cgi-bin/stable_token":
			_, _ = w.Write([]byte(`{"access_token":"access-token-value","expires_in":7200}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer fake.Close()
	srv, err := egress.NewServer(egress.ServerConfig{
		AppID:    "wx-server",
		Secret:   "server-secret-value",
		Token:    "test-bearer-token",
		APIBase:  fake.URL,
		Insecure: true,
		HTTP:     fake.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = srv.Serve(ctx, lis) }()

	dir := t.TempDir()
	img := filepath.Join(dir, "cover.png")
	if err := os.WriteFile(img, pngBytes(t), 0o600); err != nil {
		t.Fatal(err)
	}
	draft := filepath.Join(dir, "draft.json")
	body := `{
	  "articles": [{
	    "title": "标题",
	    "content": "<p>hello</p>",
	    "thumb_media_id": "thumb123"
	  }]
	}`
	if err := os.WriteFile(draft, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{
		"WECHAT_EGRESS_TOKEN":    "test-bearer-token",
		"WECHAT_EGRESS_ADDRESS":  lis.Addr().String(),
		"WECHAT_EGRESS_INSECURE": "1",
	}
	var stdout, stderr bytes.Buffer
	code := Run([]string{"upload-image", "--file", img, "--timeout", "5s"}, mapEnv(env), &stdout, &stderr)
	if code != 0 || !strings.Contains(stdout.String(), "http://mmbiz.qpic.cn/from-cli") {
		t.Fatalf("upload code %d\n%s\n%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"add-draft", "--file", draft, "--timeout", "5s"}, mapEnv(env), &stdout, &stderr)
	if code != 0 || !strings.Contains(stdout.String(), "draft-from-cli") {
		t.Fatalf("add code %d\n%s\n%s", code, stdout.String(), stderr.String())
	}
}

func TestServerRejectsPublicInsecureListen(t *testing.T) {
	env := map[string]string{
		"WECHAT_APPID":        "wx-server",
		"WECHAT_APPSECRET":    "server-secret-value",
		"WECHAT_EGRESS_TOKEN": "test-bearer-token",
		"WECHAT_API_BASE":     "http://127.0.0.1:9",
	}
	done := make(chan int, 1)
	go func() {
		var stderr bytes.Buffer
		done <- Run([]string{"server", "--listen", "0.0.0.0:8443", "--insecure"}, mapEnv(env), ioDiscard{}, &stderr)
	}()
	select {
	case code := <-done:
		if code == 0 {
			t.Fatal("server started")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server did not reject the listen address")
	}
}

func mapEnv(env map[string]string) func(string) string {
	return func(key string) string { return env[key] }
}

type ioDiscard struct{}

func (ioDiscard) Write(p []byte) (int, error) { return len(p), nil }

func pngBytes(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewNRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}
