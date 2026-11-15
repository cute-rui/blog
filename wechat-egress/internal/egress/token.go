package egress

import (
	"errors"
	"strings"
)

// NormalizeToken trims a bearer token and rejects empty or short values.
// The same value is set on the VPS and in the workflow. It is not the
// WeChat AppSecret.
func NormalizeToken(raw string) (string, error) {
	token := strings.TrimSpace(raw)
	if token == "" {
		return "", errors.New("WECHAT_EGRESS_TOKEN is required")
	}
	if len(token) < 16 {
		return "", errors.New("WECHAT_EGRESS_TOKEN must be at least 16 characters")
	}
	if strings.ContainsAny(token, " \t\r\n") {
		return "", errors.New("WECHAT_EGRESS_TOKEN must not contain whitespace")
	}
	return token, nil
}
