package srvdns

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestParseMessageAndCompressedTarget(t *testing.T) {
	qname, err := encodeName("_wechat._tcp.example.com")
	if err != nil {
		t.Fatal(err)
	}
	const exampleAt = 25 // header + _wechat + _tcp
	if exampleAt-12 >= len(qname) || qname[exampleAt-12] != 7 {
		t.Fatalf("example label offset = %d, name %v", exampleAt, qname)
	}

	var msg []byte
	msg = append(msg, 0x00, 0x2a, 0x81, 0x80)
	msg = append(msg, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00)
	msg = append(msg, qname...)
	msg = append(msg, 0x00, 33, 0x00, 1)

	// An A record, ignored. Owner is a pointer at the question.
	msg = append(msg, 0xC0, 0x0C)
	msg = append(msg, 0x00, 1, 0x00, 1)
	msg = append(msg, 0, 0, 0, 30)
	msg = append(msg, 0x00, 4, 127, 0, 0, 1)

	// SRV whose target is "egress" plus a pointer at example.com.
	msg = append(msg, 0xC0, 0x0C)
	msg = append(msg, 0x00, 33, 0x00, 1)
	msg = append(msg, 0, 0, 0, 30)
	target := []byte{6, 'e', 'g', 'r', 'e', 's', 's', 0xC0, byte(exampleAt)}
	rdlen := 6 + len(target)
	msg = append(msg, byte(rdlen>>8), byte(rdlen))
	msg = append(msg, 0, 1, 0, 5, 0x20, 0xFB)
	msg = append(msg, target...)

	got, err := ParseMessage(msg)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 {
		t.Fatalf("records = %d, want 1", len(got))
	}
	if got[0].Priority != 1 || got[0].Weight != 5 || got[0].Port != 8443 {
		t.Fatalf("record = %+v", got[0])
	}
	if got[0].Target != "egress.example.com." {
		t.Fatalf("target = %q", got[0].Target)
	}
}

func TestParseRejectsMalformedMessages(t *testing.T) {
	if _, err := ParseMessage([]byte{0x00, 0x01}); err == nil {
		t.Fatal("short message was accepted")
	}
	query := make([]byte, 12)
	query[2] = 0x01 // RD, not a response
	if _, err := ParseMessage(query); err == nil {
		t.Fatal("query was accepted as a response")
	}
	nx := make([]byte, 12)
	nx[2] = 0x81
	nx[3] = 0x03
	if _, err := ParseMessage(nx); err == nil || !strings.Contains(err.Error(), "nxdomain") {
		t.Fatalf("nxdomain error = %v", err)
	}

	qname, err := encodeName("_wechat._tcp.example.com")
	if err != nil {
		t.Fatal(err)
	}
	var msg []byte
	msg = append(msg, 0x00, 0x01, 0x81, 0x80)
	msg = append(msg, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)
	msg = append(msg, qname...)
	msg = append(msg, 0x00, 33, 0x00, 1)
	msg = append(msg, 0xC0, 0x0C, 0x00, 33, 0x00, 1, 0, 0, 0, 1)
	ptrAt := len(msg) + 2 + 6
	msg = append(msg, 0x00, 8, 0, 0, 0, 0, 0, 1)
	if len(msg) != ptrAt {
		t.Fatalf("pointer offset = %d, len = %d", ptrAt, len(msg))
	}
	msg = append(msg, 0xC0, byte(ptrAt))
	if _, err := ParseMessage(msg); err == nil || !strings.Contains(err.Error(), "pointer loop") {
		t.Fatalf("loop error = %v", err)
	}

	truncated := make([]byte, 12)
	truncated[2] = 0x81
	truncated[3] = 0x80
	binary.BigEndian.PutUint16(truncated[6:8], 1)
	if _, err := ParseMessage(truncated); err == nil {
		t.Fatal("truncated answer was accepted")
	}
}

func TestResolveRelativeAndAbsoluteTargets(t *testing.T) {
	cases := []struct {
		target string
		domain string
		want   string
		ok     bool
	}{
		{target: "egress", domain: "example.com", want: "egress.example.com", ok: true},
		{target: "egress.dc1", domain: "example.com", want: "egress.dc1.example.com", ok: true},
		{target: "egress.example.com.", domain: "example.com", want: "egress.example.com", ok: true},
		{target: "vps.other.net.", domain: "example.com", want: "vps.other.net", ok: true},
		{target: "127.0.0.1", domain: "example.com", want: "127.0.0.1", ok: true},
		{target: "127.0.0.1.", domain: "example.com", want: "127.0.0.1", ok: true},
		{target: "localhost.", domain: "example.com", want: "localhost", ok: true},
		{target: ".", domain: "example.com", ok: false},
		{target: "egress", domain: "", ok: false},
	}
	for _, tc := range cases {
		got, err := Resolve(tc.target, tc.domain)
		if tc.ok && (err != nil || got != tc.want) {
			t.Errorf("Resolve(%q, %q) = %q, %v; want %q", tc.target, tc.domain, got, err, tc.want)
		}
		if !tc.ok && err == nil {
			t.Errorf("Resolve(%q, %q) = %q, want error", tc.target, tc.domain, got)
		}
	}
}

func TestOrderPriorityAndWeight(t *testing.T) {
	records := []Record{
		{Priority: 5, Weight: 100, Port: 9, Target: "late.example.com."},
		{Priority: 0, Weight: 1, Port: 8443, Target: "a.example.com."},
		{Priority: 0, Weight: 3, Port: 8444, Target: "b.example.com."},
	}
	calls := 0
	got, err := Order(records, "example.com", func(n int) int {
		calls++
		if n != 4 {
			t.Fatalf("intn(%d), want 4", n)
		}
		return 1
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"b.example.com:8444", "a.example.com:8443", "late.example.com:9"}
	if calls != 1 || !sameAddr(got, want) {
		t.Fatalf("calls=%d endpoints=%v", calls, addrs(got))
	}

	zeroFirst, err := Order([]Record{
		{Priority: 0, Weight: 0, Port: 1, Target: "z.example.com."},
		{Priority: 0, Weight: 5, Port: 2, Target: "p.example.com."},
	}, "example.com", func(n int) int {
		if n != 5 {
			t.Fatalf("intn(%d), want 5", n)
		}
		return 0
	})
	if err != nil {
		t.Fatal(err)
	}
	if !sameAddr(zeroFirst, []string{"p.example.com:2", "z.example.com:1"}) {
		t.Fatalf("weight 0 ordered %v", addrs(zeroFirst))
	}

	called := false
	lower, err := Order([]Record{
		{Priority: 1, Weight: 100, Port: 2, Target: "second.example.com."},
		{Priority: 0, Weight: 0, Port: 1, Target: "first.example.com."},
	}, "example.com", func(int) int {
		called = true
		return 0
	})
	if err != nil {
		t.Fatal(err)
	}
	if called || !sameAddr(lower, []string{"first.example.com:1", "second.example.com:2"}) {
		t.Fatalf("priority order %v called=%v", addrs(lower), called)
	}

	relative, err := Order([]Record{
		{Priority: 0, Weight: 1, Port: 8443, Target: "egress"},
	}, "example.com", func(int) int { return 0 })
	if err != nil {
		t.Fatal(err)
	}
	if !sameAddr(relative, []string{"egress.example.com:8443"}) {
		t.Fatalf("relative target ordered %v", addrs(relative))
	}

	if _, err := Order([]Record{{Target: ".", Port: 9}}, "example.com", func(int) int { return 0 }); err == nil {
		t.Fatal("unavailable target was usable")
	}
}

func TestQueryName(t *testing.T) {
	got, err := QueryName("", "example.com.")
	if err != nil || got != "_wechat._tcp.example.com" {
		t.Fatalf("QueryName = %q, %v", got, err)
	}
	if _, err := QueryName("_wechat._tcp.example.com", "example.com"); err == nil {
		t.Fatal("full name was accepted as the service prefix")
	}
	if _, err := QueryName("_wechat._tcp", ""); err == nil {
		t.Fatal("empty host was accepted")
	}
}

func TestNameserversFrom(t *testing.T) {
	in := "# comment\nnameserver 127.0.0.1\nsearch example.com\nnameserver 2001:db8::1\n"
	got, err := NameserversFrom(strings.NewReader(in))
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"127.0.0.1:53", "[2001:db8::1]:53"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("nameservers = %v", got)
	}
	if _, err := NameserversFrom(strings.NewReader("search example.com\n")); err == nil {
		t.Fatal("missing nameserver was accepted")
	}
}

func TestLookupLoopback(t *testing.T) {
	question, err := encodeName("_wechat._tcp.example.com")
	if err != nil {
		t.Fatal(err)
	}
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	port := udp.LocalAddr().(*net.UDPAddr).Port
	tcp, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tcp.Close() })

	records := []Record{
		{Priority: 10, Weight: 1, Port: 9, Target: "backup.example.com."},
		{Priority: 0, Weight: 1, Port: 8443, Target: "egress.example.com."},
	}
	go serveUDP(udp, question, nil, true)
	go serveTCP(tcp, question, records)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	got, err := Lookup(ctx, "_wechat._tcp", "example.com", udp.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	if !sameAddr(got, []string{"egress.example.com:8443", "backup.example.com:9"}) {
		t.Fatalf("lookup = %v", addrs(got))
	}
}

func TestLookupUDPWithoutTruncation(t *testing.T) {
	question, err := encodeName("_wechat._tcp.example.com")
	if err != nil {
		t.Fatal(err)
	}
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udp.Close() })
	records := []Record{{Priority: 0, Weight: 10, Port: 8443, Target: "localhost."}}
	go serveUDP(udp, question, records, false)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	got, err := Lookup(ctx, "", "example.com", udp.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	if !sameAddr(got, []string{"localhost:8443"}) {
		t.Fatalf("lookup = %v", addrs(got))
	}
}

func serveUDP(conn net.PacketConn, question []byte, records []Record, tc bool) {
	buf := make([]byte, 2048)
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, addr, err := conn.ReadFrom(buf)
	if err != nil {
		return
	}
	resp := responseFor(buf[:n], question, records, tc)
	_, _ = conn.WriteTo(resp, addr)
}

func serveTCP(ln net.Listener, question []byte, records []Record) {
	_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(3 * time.Second))
	conn, err := ln.Accept()
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	var prefix [2]byte
	if _, err := io.ReadFull(conn, prefix[:]); err != nil {
		return
	}
	n := int(binary.BigEndian.Uint16(prefix[:]))
	buf := make([]byte, n)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return
	}
	resp := responseFor(buf, question, records, false)
	var out [2]byte
	binary.BigEndian.PutUint16(out[:], uint16(len(resp)))
	_, _ = conn.Write(append(out[:], resp...))
}

func responseFor(query, question []byte, records []Record, tc bool) []byte {
	id := []byte{0, 0}
	if len(query) >= 2 {
		id = query[:2]
	}
	if !bytes.Contains(query, question) {
		msg := append([]byte{}, id...)
		msg = append(msg, 0x81, 0x83) // QR + NXDOMAIN
		return append(msg, make([]byte, 8)...)
	}
	msg, err := packResponse(id, question, records, tc)
	if err != nil {
		return nil
	}
	return msg
}

func packResponse(id, question []byte, records []Record, tc bool) ([]byte, error) {
	var msg []byte
	msg = append(msg, id...)
	flags := byte(0x81)
	if tc {
		flags |= 0x02
	}
	msg = append(msg, flags, 0x80)
	msg = append(msg, 0x00, 0x01)
	msg = append(msg, byte(len(records)>>8), byte(len(records)))
	msg = append(msg, 0x00, 0x00, 0x00, 0x00)
	msg = append(msg, question...)
	msg = append(msg, 0x00, 33, 0x00, 1)
	for _, rec := range records {
		target, err := encodeName(rec.Target)
		if err != nil {
			return nil, err
		}
		msg = append(msg, 0xC0, 0x0C)
		msg = append(msg, 0x00, 33, 0x00, 1)
		msg = append(msg, 0, 0, 0, 60)
		rdata := make([]byte, 6+len(target))
		binary.BigEndian.PutUint16(rdata[0:2], rec.Priority)
		binary.BigEndian.PutUint16(rdata[2:4], rec.Weight)
		binary.BigEndian.PutUint16(rdata[4:6], rec.Port)
		copy(rdata[6:], target)
		msg = append(msg, byte(len(rdata)>>8), byte(len(rdata)))
		msg = append(msg, rdata...)
	}
	return msg, nil
}

func addrs(in []Endpoint) []string {
	out := make([]string, len(in))
	for i, ep := range in {
		out[i] = ep.Address()
	}
	return out
}

func sameAddr(got []Endpoint, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range want {
		if got[i].Address() != want[i] {
			return false
		}
	}
	return true
}
