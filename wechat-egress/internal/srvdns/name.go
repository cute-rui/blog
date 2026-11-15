package srvdns

import (
	"fmt"
	"strings"
)

const DefaultService = "_wechat._tcp"

// QueryName builds the SRV lookup name. service is the two-label prefix
// (default _wechat._tcp). domain is the name's domain, for example example.com.
func QueryName(service, domain string) (string, error) {
	service = strings.TrimSuffix(strings.TrimSpace(service), ".")
	domain = strings.TrimSuffix(strings.TrimSpace(domain), ".")
	if service == "" {
		service = DefaultService
	}
	if err := validateService(service); err != nil {
		return "", err
	}
	if err := validateDomain(domain); err != nil {
		return "", err
	}
	return service + "." + domain, nil
}

func validateService(service string) error {
	labels := strings.Split(service, ".")
	if len(labels) != 2 {
		return fmt.Errorf("SRV name %q must be two labels such as %s", service, DefaultService)
	}
	for _, label := range labels {
		if len(label) < 2 || label[0] != '_' {
			return fmt.Errorf("SRV label %q must start with _", label)
		}
		if err := checkLabel(label[1:]); err != nil {
			return fmt.Errorf("SRV label %q: %w", label, err)
		}
	}
	return nil
}

func validateDomain(domain string) error {
	if domain == "" {
		return fmt.Errorf("SRV host is required")
	}
	if len(domain) > 253 {
		return fmt.Errorf("SRV host %q is too long", domain)
	}
	for _, label := range strings.Split(domain, ".") {
		if err := checkLabel(label); err != nil {
			return fmt.Errorf("SRV host %q: %w", domain, err)
		}
	}
	return nil
}

func checkLabel(label string) error {
	if label == "" || len(label) > 63 {
		return fmt.Errorf("invalid label %q", label)
	}
	if label[0] == '-' || label[len(label)-1] == '-' {
		return fmt.Errorf("invalid label %q", label)
	}
	for i := 0; i < len(label); i++ {
		if !isNameChar(label[i]) {
			return fmt.Errorf("invalid label %q", label)
		}
	}
	return nil
}

func isNameChar(c byte) bool {
	return c == '-' || c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

// encodeName writes a presentation name as an uncompressed DNS name.
// A trailing dot is optional. The result always ends with the root label.
func encodeName(name string) ([]byte, error) {
	name = strings.TrimSpace(name)
	if name == "." {
		return []byte{0}, nil
	}
	name = strings.TrimSuffix(name, ".")
	if name == "" {
		return nil, fmt.Errorf("empty DNS name")
	}
	var buf []byte
	for _, label := range strings.Split(name, ".") {
		if err := checkLabel(label); err != nil {
			return nil, err
		}
		buf = append(buf, byte(len(label)))
		buf = append(buf, label...)
	}
	buf = append(buf, 0)
	if len(buf) > 255 {
		return nil, fmt.Errorf("DNS name %q is too long", name)
	}
	return buf, nil
}
