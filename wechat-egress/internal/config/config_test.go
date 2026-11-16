package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const sample = `
app_id = "wx-server"
app_secret = "server-secret-value"
token = "test-bearer-token"
listen = "127.0.0.1:8443"
tls_cert = "/tmp/cert.pem"
tls_key = "/tmp/key.pem"
insecure = false
api_base = "http://127.0.0.1:9"
`

func TestLoadTrimsAndRejectsUnknownKeys(t *testing.T) {
	path := writeConfig(t, sample, 0o600)
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AppID != "wx-server" || cfg.AppSecret != "server-secret-value" || cfg.Token != "test-bearer-token" {
		t.Fatalf("secrets = %+v", cfg)
	}
	if cfg.Listen != "127.0.0.1:8443" || cfg.TLSCert != "/tmp/cert.pem" || cfg.TLSKey != "/tmp/key.pem" {
		t.Fatalf("paths = %+v", cfg)
	}
	if cfg.Insecure || cfg.APIBase != "http://127.0.0.1:9" {
		t.Fatalf("deployment = %+v", cfg)
	}

	padded := strings.Replace(sample, `app_id = "wx-server"`, `app_id = "  wx-server  "`, 1)
	padded = strings.Replace(padded, "listen = \"127.0.0.1:8443\"\n", "", 1)
	cfg, err = Load(writeConfig(t, padded, 0o600))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AppID != "wx-server" || cfg.Listen != "0.0.0.0:8443" {
		t.Fatalf("default listen = %+v", cfg)
	}

	bad := sample + "\nappsecret = \"nope\"\n"
	if _, err := Load(writeConfig(t, bad, 0o600)); err == nil || !strings.Contains(err.Error(), "parse config") {
		t.Fatalf("unknown key error = %v", err)
	}
}

func TestLoadRejectsLoosePermissions(t *testing.T) {
	path := writeConfig(t, sample, 0o644)
	if _, err := Load(path); err == nil || !strings.Contains(err.Error(), "0600") {
		t.Fatalf("mode error = %v", err)
	}
}

func writeConfig(t *testing.T, body string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.toml")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
	return path
}
