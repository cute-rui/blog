// Package config loads the VPS process settings from a TOML file.
// Secrets are not read from the environment. The only environment variable
// the server accepts is the path of this file.
package config

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/pelletier/go-toml/v2"
)

const maxBytes = 1 << 16

// File is the on-disk shape. Unknown keys are rejected.
type File struct {
	AppID     string `toml:"app_id"`
	AppSecret string `toml:"app_secret"`
	Token     string `toml:"token"`
	Listen    string `toml:"listen"`
	TLSCert   string `toml:"tls_cert"`
	TLSKey    string `toml:"tls_key"`
	Insecure  bool   `toml:"insecure"`
	APIBase   string `toml:"api_base"`
}

// Load reads path. The file must be a regular file and must not be readable
// by group or others, because it holds the WeChat AppSecret.
func Load(path string) (File, error) {
	f, err := os.Open(path)
	if err != nil {
		return File{}, fmt.Errorf("open config: %w", err)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return File{}, fmt.Errorf("stat config: %w", err)
	}
	if !info.Mode().IsRegular() {
		return File{}, fmt.Errorf("config %s is not a regular file", path)
	}
	if info.Mode().Perm()&0o077 != 0 {
		return File{}, fmt.Errorf("config %s must be readable only by its owner (mode 0600)", path)
	}
	raw, err := io.ReadAll(io.LimitReader(f, maxBytes+1))
	if err != nil {
		return File{}, fmt.Errorf("read config: %w", err)
	}
	if len(raw) > maxBytes {
		return File{}, fmt.Errorf("config %s is larger than %d bytes", path, maxBytes)
	}
	dec := toml.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	var cfg File
	if err := dec.Decode(&cfg); err != nil {
		return File{}, fmt.Errorf("parse config: %w", err)
	}
	cfg.AppID = strings.TrimSpace(cfg.AppID)
	cfg.AppSecret = strings.TrimSpace(cfg.AppSecret)
	cfg.Token = strings.TrimSpace(cfg.Token)
	cfg.Listen = strings.TrimSpace(cfg.Listen)
	cfg.TLSCert = strings.TrimSpace(cfg.TLSCert)
	cfg.TLSKey = strings.TrimSpace(cfg.TLSKey)
	cfg.APIBase = strings.TrimSpace(cfg.APIBase)
	if cfg.Listen == "" {
		cfg.Listen = "0.0.0.0:8443"
	}
	return cfg, nil
}
