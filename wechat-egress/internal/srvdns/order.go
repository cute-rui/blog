package srvdns

import (
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"
)

// Endpoint is one host and port to dial, after SRV ordering.
type Endpoint struct {
	Host string
	Port uint16
}

func (e Endpoint) Address() string {
	return net.JoinHostPort(e.Host, strconv.Itoa(int(e.Port)))
}

// Resolve joins a relative SRV target to the lookup name's domain.
// A target that ends in '.' is absolute. An IP address is never joined.
// A target of "." means the service is not available.
func Resolve(target, domain string) (string, error) {
	target = strings.TrimSpace(target)
	if target == "" || target == "." {
		return "", fmt.Errorf("srv target is unavailable")
	}
	absolute := strings.HasSuffix(target, ".")
	host := strings.TrimSuffix(target, ".")
	if host == "" {
		return "", fmt.Errorf("srv target is unavailable")
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.String(), nil
	}
	if !absolute {
		domain = strings.TrimSuffix(strings.TrimSpace(domain), ".")
		if domain == "" {
			return "", fmt.Errorf("relative SRV target %q needs a lookup domain", target)
		}
		if err := validateDomain(domain); err != nil {
			return "", err
		}
		host = host + "." + domain
	}
	if err := validateDomain(host); err != nil {
		return "", err
	}
	return host, nil
}

// Order sorts records by ascending priority, then by RFC 2782 weight within
// a priority. intn returns a value in [0, n). Weight 0 is tried only after
// the positive weights in the same priority. Relative targets are resolved
// against domain, which is the domain of the SRV name.
func Order(records []Record, domain string, intn func(n int) int) ([]Endpoint, error) {
	if intn == nil {
		return nil, fmt.Errorf("rng is required")
	}
	usable := make([]Record, 0, len(records))
	for _, rec := range records {
		if rec.Target == "." || rec.Port == 0 {
			continue
		}
		usable = append(usable, rec)
	}
	if len(usable) == 0 {
		return nil, fmt.Errorf("no usable SRV targets")
	}
	sort.SliceStable(usable, func(i, j int) bool {
		if usable[i].Priority != usable[j].Priority {
			return usable[i].Priority < usable[j].Priority
		}
		if usable[i].Weight != usable[j].Weight {
			return usable[i].Weight < usable[j].Weight
		}
		if usable[i].Target != usable[j].Target {
			return usable[i].Target < usable[j].Target
		}
		return usable[i].Port < usable[j].Port
	})
	i := 0
	for j := 1; j <= len(usable); j++ {
		if j == len(usable) || usable[i].Priority != usable[j].Priority {
			shuffleByWeight(usable[i:j], intn)
			i = j
		}
	}
	out := make([]Endpoint, 0, len(usable))
	for _, rec := range usable {
		host, err := Resolve(rec.Target, domain)
		if err != nil {
			return nil, err
		}
		out = append(out, Endpoint{Host: host, Port: rec.Port})
	}
	return out, nil
}

// shuffleByWeight reorders one priority group. The running sum follows
// RFC 2782: pick r in [0, sum) and take the first record whose running
// weight exceeds r. Records with weight 0 never win that comparison while
// a positive weight remains.
func shuffleByWeight(addrs []Record, intn func(n int) int) {
	sum := 0
	for _, addr := range addrs {
		sum += int(addr.Weight)
	}
	for sum > 0 && len(addrs) > 1 {
		n := intn(sum)
		if n < 0 || n >= sum {
			n = 0
		}
		running := 0
		picked := 0
		for i := range addrs {
			running += int(addrs[i].Weight)
			if running > n {
				picked = i
				break
			}
		}
		if picked != 0 {
			addrs[0], addrs[picked] = addrs[picked], addrs[0]
		}
		weight := int(addrs[0].Weight)
		if weight <= 0 {
			return
		}
		sum -= weight
		addrs = addrs[1:]
	}
}
