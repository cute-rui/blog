package srvdns

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"strings"
	"time"
)

// Lookup queries one SRV name and returns dial targets in priority and weight
// order. server is host:port of the DNS server; an empty server uses
// /etc/resolv.conf. domain is both the suffix of the lookup name and the
// origin used for a relative target.
func Lookup(ctx context.Context, service, domain, server string) ([]Endpoint, error) {
	qname, err := QueryName(service, domain)
	if err != nil {
		return nil, err
	}
	origin := strings.TrimSuffix(strings.TrimSpace(domain), ".")
	query, err := buildQuery(qname)
	if err != nil {
		return nil, err
	}
	resp, err := resolveQuery(ctx, server, query)
	if err != nil {
		return nil, fmt.Errorf("lookup %s: %w", qname, err)
	}
	records, err := ParseMessage(resp)
	if err != nil {
		return nil, fmt.Errorf("lookup %s: %w", qname, err)
	}
	if len(records) == 0 {
		return nil, fmt.Errorf("lookup %s: no SRV records", qname)
	}
	return Order(records, origin, secureIntn)
}

func resolveQuery(ctx context.Context, server string, query []byte) ([]byte, error) {
	if server != "" {
		return exchange(ctx, server, query)
	}
	servers, err := systemNameservers()
	if err != nil {
		return nil, err
	}
	var last error
	for _, candidate := range servers {
		resp, err := exchange(ctx, candidate, query)
		if err == nil {
			return resp, nil
		}
		last = err
	}
	return nil, last
}

func systemNameservers() ([]string, error) {
	f, err := os.Open("/etc/resolv.conf")
	if err != nil {
		return nil, fmt.Errorf("read resolv.conf: %w", err)
	}
	defer f.Close()
	return NameserversFrom(f)
}

// NameserversFrom parses resolv.conf nameserver lines into host:port values.
func NameserversFrom(r io.Reader) ([]string, error) {
	sc := bufio.NewScanner(r)
	var out []string
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if i := strings.IndexAny(line, "#;"); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		fields := strings.Fields(line)
		if len(fields) < 2 || fields[0] != "nameserver" {
			continue
		}
		host := fields[1]
		if _, _, err := net.SplitHostPort(host); err == nil {
			out = append(out, host)
			continue
		}
		if ip := net.ParseIP(host); ip != nil {
			out = append(out, net.JoinHostPort(ip.String(), "53"))
			continue
		}
		return nil, fmt.Errorf("invalid nameserver %q", host)
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no nameserver in resolv.conf")
	}
	return out, nil
}

func buildQuery(name string) ([]byte, error) {
	encoded, err := encodeName(name)
	if err != nil {
		return nil, err
	}
	var id [2]byte
	if _, err := rand.Read(id[:]); err != nil {
		return nil, err
	}
	msg := make([]byte, 0, 12+len(encoded)+4)
	msg = append(msg, id[:]...)
	msg = append(msg, 0x01, 0x00) // RD
	msg = append(msg, 0x00, 0x01) // one question
	msg = append(msg, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
	msg = append(msg, encoded...)
	msg = append(msg, 0x00, 33) // SRV
	msg = append(msg, 0x00, 1)  // IN
	return msg, nil
}

func exchange(ctx context.Context, server string, query []byte) ([]byte, error) {
	resp, err := exchangeUDP(ctx, server, query)
	if err != nil {
		return nil, err
	}
	if len(resp) >= 4 && resp[2]&0x02 != 0 {
		resp, err = exchangeTCP(ctx, server, query)
		if err != nil {
			return nil, err
		}
	}
	if err := matchID(query, resp); err != nil {
		return nil, err
	}
	if len(resp) >= 4 && resp[2]&0x02 != 0 {
		return nil, fmt.Errorf("dns response is still truncated")
	}
	return resp, nil
}

func matchID(query, resp []byte) error {
	if len(resp) < 2 || resp[0] != query[0] || resp[1] != query[1] {
		return fmt.Errorf("dns response id mismatch")
	}
	return nil
}

func exchangeUDP(ctx context.Context, server string, query []byte) ([]byte, error) {
	conn, err := dial(ctx, "udp", server)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func exchangeTCP(ctx context.Context, server string, query []byte) ([]byte, error) {
	if len(query) > 65535 {
		return nil, fmt.Errorf("dns query is too long")
	}
	conn, err := dial(ctx, "tcp", server)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	var prefix [2]byte
	binary.BigEndian.PutUint16(prefix[:], uint16(len(query)))
	if _, err := conn.Write(append(prefix[:], query...)); err != nil {
		return nil, err
	}
	if _, err := io.ReadFull(conn, prefix[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(prefix[:]))
	if n < 12 {
		return nil, fmt.Errorf("dns tcp response is too short")
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func dial(ctx context.Context, network, server string) (net.Conn, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, network, server)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(5 * time.Second)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := conn.SetDeadline(deadline); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func secureIntn(n int) int {
	if n <= 1 {
		return 0
	}
	v, err := rand.Int(rand.Reader, big.NewInt(int64(n)))
	if err != nil {
		return 0
	}
	return int(v.Int64())
}
