package egress

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"image"
	"image/png"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lightwing/blog/wechat-egress/internal/pb"
	"github.com/lightwing/blog/wechat-egress/internal/srvdns"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const testToken = "test-bearer-token"

func TestClientCallsServerOnLoopback(t *testing.T) {
	fake := newFakeWeChat(t)
	defer fake.Close()
	_, lis := startServer(t, fake.server.Client(), fake.server.URL, true, "", "")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, ClientConfig{
		Token:    testToken,
		Address:  lis.Addr().String(),
		Insecure: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	token, expires, err := client.GetStableToken(ctx)
	if err != nil || token != "access-token-value" || expires != 7200 {
		t.Fatalf("token %q %d %v", token, expires, err)
	}
	url, err := client.UploadImage(ctx, pngBytes(t), "cover.png")
	if err != nil || url != "http://mmbiz.qpic.cn/test" {
		t.Fatalf("upload %q %v", url, err)
	}
	coverID, err := client.UploadCover(ctx, pngBytes(t), "cover.png")
	if err != nil || coverID != "cover-media" {
		t.Fatalf("cover %q %v", coverID, err)
	}
	open := int32(1)
	fans := int32(0)
	mediaID, err := client.AddDraft(ctx, []*pb.Article{{
		Title:              "标题",
		Author:             "Lightwing",
		Digest:             "摘要",
		Content:            "<p>a&b</p>",
		ContentSourceUrl:   "https://example.com/p/",
		ThumbMediaId:       "thumb123",
		NeedOpenComment:    &open,
		OnlyFansCanComment: &fans,
		CoverCrops: []*pb.CoverCrop{{
			Ratio: "2.35_1", X1: "0", Y1: "0.1", X2: "1", Y2: "0.52",
		}},
	}})
	if err != nil || mediaID != "draft-media" {
		t.Fatalf("add %q %v", mediaID, err)
	}
	if err := client.UpdateDraft(ctx, mediaID, 0, &pb.Article{Title: "新标题", Content: "<p>ok</p>"}); err != nil {
		t.Fatal(err)
	}
	if !fake.sawAppID || fake.sawClientMarker {
		t.Fatalf("appid forwarded=%v client marker=%v", fake.sawAppID, fake.sawClientMarker)
	}
	var added struct {
		Articles []struct {
			Title           string `json:"title"`
			Content         string `json:"content"`
			NeedOpenComment *int   `json:"need_open_comment"`
			CoverInfo       *struct {
				Crops []struct {
					Ratio string `json:"ratio"`
					Y1    string `json:"y1"`
				} `json:"crop_percent_list"`
			} `json:"cover_info"`
		} `json:"articles"`
	}
	if err := json.Unmarshal(fake.addBody, &added); err != nil {
		t.Fatalf("add body %s: %v", fake.addBody, err)
	}
	if len(added.Articles) != 1 || added.Articles[0].Content != "<p>a&b</p>" || added.Articles[0].NeedOpenComment == nil || *added.Articles[0].NeedOpenComment != 1 {
		t.Fatalf("add body %s", fake.addBody)
	}
	if added.Articles[0].CoverInfo == nil || len(added.Articles[0].CoverInfo.Crops) != 1 || added.Articles[0].CoverInfo.Crops[0].Ratio != "2.35_1" {
		t.Fatalf("cover crop missing: %s", fake.addBody)
	}
	var updated struct {
		MediaID  string `json:"media_id"`
		Index    int    `json:"index"`
		Articles struct {
			Title string `json:"title"`
		} `json:"articles"`
	}
	if err := json.Unmarshal(fake.updateBody, &updated); err != nil {
		t.Fatalf("update body %s: %v", fake.updateBody, err)
	}
	if updated.MediaID != "draft-media" || updated.Index != 0 || updated.Articles.Title != "新标题" {
		t.Fatalf("update body %s", fake.updateBody)
	}
}

func TestSRVLookupReachesLoopbackServer(t *testing.T) {
	fake := newFakeWeChat(t)
	defer fake.Close()
	_, lis := startServer(t, fake.server.Client(), fake.server.URL, true, "", "")
	_, port, err := net.SplitHostPort(lis.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	dnsAddr := serveSRV(t, mustPort(t, port))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, ClientConfig{
		Token:     testToken,
		SRVName:   "_wechat._tcp",
		SRVHost:   "example.com",
		Insecure:  true,
		dnsServer: dnsAddr,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := client.GetStableToken(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestFailoverToNextTarget(t *testing.T) {
	fake := newFakeWeChat(t)
	defer fake.Close()
	_, lis := startServer(t, fake.server.Client(), fake.server.URL, true, "", "")
	dead, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	deadAddr := dead.Addr().String()
	dead.Close()
	_, deadPortStr, _ := net.SplitHostPort(deadAddr)
	_, livePortStr, _ := net.SplitHostPort(lis.Addr().String())
	deadPort := mustPort(t, deadPortStr)
	livePort := mustPort(t, livePortStr)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, ClientConfig{
		Token:    testToken,
		Insecure: true,
		resolve: func(context.Context, string, string) ([]srvdns.Endpoint, error) {
			return []srvdns.Endpoint{
				{Host: "127.0.0.1", Port: deadPort},
				{Host: "127.0.0.1", Port: livePort},
			}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := client.GetStableToken(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestRejectsBadBearerBeforeUpstream(t *testing.T) {
	fake := newFakeWeChat(t)
	defer fake.Close()
	_, lis := startServer(t, fake.server.Client(), fake.server.URL, true, "", "")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, ClientConfig{Token: "wrong-bearer-token", Address: lis.Addr().String(), Insecure: true})
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = client.GetStableToken(ctx)
	if status.Code(err) != codes.Unauthenticated {
		t.Fatalf("code = %v err = %v", status.Code(err), err)
	}
	if fake.calls != 0 {
		t.Fatalf("upstream calls = %d", fake.calls)
	}
}

func TestInsecureRequiresLoopback(t *testing.T) {
	_, err := Dial(context.Background(), ClientConfig{
		Token:    testToken,
		Address:  "example.com:443",
		Insecure: true,
	})
	if err == nil || !strings.Contains(err.Error(), "loopback") {
		t.Fatalf("error = %v", err)
	}
	_, err = NewServer(ServerConfig{
		AppID: "wx-server", Secret: "server-secret-value", Token: testToken, Insecure: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := NewServer(ServerConfig{
		AppID: "wx-server", Secret: "server-secret-value", Token: testToken,
	}); err == nil {
		t.Fatal("server started without TLS")
	}
	if _, err := NewServer(ServerConfig{
		AppID: "wx-server", Secret: testToken, Token: testToken, Insecure: true,
	}); err == nil {
		t.Fatal("bearer token matching the AppSecret was accepted")
	}
	srv, err := NewServer(ServerConfig{
		AppID: "wx-server", Secret: "server-secret-value", Token: testToken, Insecure: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := srv.ListenAndServe(ctx, "0.0.0.0:0"); err == nil {
		t.Fatal("insecure listen on all interfaces was accepted")
	}
}

func TestTLSLoopback(t *testing.T) {
	certFile, keyFile, caFile := writeCert(t, "127.0.0.1")
	fake := newFakeWeChat(t)
	defer fake.Close()
	_, lis := startServer(t, fake.server.Client(), fake.server.URL, false, certFile, keyFile)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := Dial(ctx, ClientConfig{
		Token:   testToken,
		Address: lis.Addr().String(),
		CAFile:  caFile,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := client.GetStableToken(ctx); err != nil {
		t.Fatal(err)
	}
	bad, err := Dial(ctx, ClientConfig{Token: testToken, Address: lis.Addr().String()})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := bad.GetStableToken(ctx); err == nil {
		t.Fatal("system roots accepted a private certificate")
	}
}

type fakeWeChat struct {
	server          *httptest.Server
	calls           int
	sawAppID        bool
	sawClientMarker bool
	addBody         []byte
	updateBody      []byte
}

func newFakeWeChat(t *testing.T) *fakeWeChat {
	t.Helper()
	fake := &fakeWeChat{}
	fake.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fake.calls++
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if bytes.Contains(body, []byte("from-client")) {
			fake.sawClientMarker = true
		}
		if bytes.Contains(body, []byte(`"appid":"wx-server"`)) && bytes.Contains(body, []byte(`"secret":"server-secret-value"`)) {
			fake.sawAppID = true
		}
		switch r.URL.Path {
		case "/cgi-bin/stable_token":
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "access-token-value", "expires_in": 7200})
		case "/cgi-bin/media/uploadimg":
			_ = json.NewEncoder(w).Encode(map[string]any{"url": "http://mmbiz.qpic.cn/test", "errcode": 0})
		case "/cgi-bin/material/add_material":
			if r.URL.Query().Get("type") != "image" {
				t.Errorf("cover type %q", r.URL.RawQuery)
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"media_id": "cover-media", "errcode": 0})
		case "/cgi-bin/draft/add":
			fake.addBody = append([]byte(nil), body...)
			_ = json.NewEncoder(w).Encode(map[string]any{"media_id": "draft-media"})
		case "/cgi-bin/draft/update":
			fake.updateBody = append([]byte(nil), body...)
			_ = json.NewEncoder(w).Encode(map[string]any{"errcode": 0, "errmsg": "ok"})
		default:
			t.Errorf("path %s", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	return fake
}

func (f *fakeWeChat) Close() { f.server.Close() }

func startServer(t *testing.T, client *http.Client, base string, insecure bool, cert, key string) (*Server, net.Listener) {
	t.Helper()
	srv, err := NewServer(ServerConfig{
		AppID:    "wx-server",
		Secret:   "server-secret-value",
		Token:    testToken,
		APIBase:  base,
		Insecure: insecure,
		CertFile: cert,
		KeyFile:  key,
		HTTP:     client,
	})
	if err != nil {
		t.Fatal(err)
	}
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() {
		cancel()
		lis.Close()
	})
	go func() { _ = srv.Serve(ctx, lis) }()
	return srv, lis
}

func serveSRV(t *testing.T, grpcPort uint16) string {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	question := []byte{7, '_', 'w', 'e', 'c', 'h', 'a', 't', 4, '_', 't', 'c', 'p', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0}
	target := encodeDNSName("127.0.0.1")
	go func() {
		buf := make([]byte, 2048)
		_ = conn.SetReadDeadline(time.Now().Add(4 * time.Second))
		n, addr, err := conn.ReadFrom(buf)
		if err != nil || n < 2 {
			return
		}
		var msg []byte
		if !bytes.Contains(buf[:n], question) {
			msg = append(msg, buf[0], buf[1], 0x81, 0x03)
			msg = append(msg, make([]byte, 8)...)
			_, _ = conn.WriteTo(msg, addr)
			return
		}
		msg = append(msg, buf[0], buf[1], 0x81, 0x80)
		msg = append(msg, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
		msg = append(msg, question...)
		msg = append(msg, 0x00, 33, 0x00, 1)
		msg = append(msg, 0xC0, 0x0C)
		msg = append(msg, 0x00, 33, 0x00, 1)
		msg = append(msg, 0, 0, 0, 60)
		rdata := make([]byte, 6+len(target))
		binary.BigEndian.PutUint16(rdata[2:4], 1)
		binary.BigEndian.PutUint16(rdata[4:6], grpcPort)
		copy(rdata[6:], target)
		msg = append(msg, byte(len(rdata)>>8), byte(len(rdata)))
		msg = append(msg, rdata...)
		_, _ = conn.WriteTo(msg, addr)
	}()
	return conn.LocalAddr().String()
}

func encodeDNSName(name string) []byte {
	var out []byte
	for _, label := range strings.Split(name, ".") {
		out = append(out, byte(len(label)))
		out = append(out, label...)
	}
	return append(out, 0)
}

func mustPort(t *testing.T, s string) uint16 {
	t.Helper()
	n, err := strconv.Atoi(s)
	if err != nil || n < 1 || n > 65535 {
		t.Fatalf("port %q", s)
	}
	return uint16(n)
}

func writeCert(t *testing.T, host string) (certFile, keyFile, caFile string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: host},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:  []net.IP{net.ParseIP(host)},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	dir := t.TempDir()
	certFile = filepath.Join(dir, "cert.pem")
	keyFile = filepath.Join(dir, "key.pem")
	caFile = filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(certFile, certPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, keyPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(caFile, certPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	return certFile, keyFile, caFile
}

func pngBytes(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewNRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}
