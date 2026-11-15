package srvdns

import (
	"encoding/binary"
	"fmt"
	"strings"
)

// Record is one SRV answer. Target is a presentation name. A trailing dot
// means the name is absolute.
type Record struct {
	Priority uint16
	Weight   uint16
	Port     uint16
	Target   string
}

// ParseMessage reads SRV records from a DNS response.
// Compression pointers in owner names and targets are followed.
// A target of "." is returned as-is; callers treat it as unavailable.
func ParseMessage(msg []byte) ([]Record, error) {
	if len(msg) < 12 {
		return nil, fmt.Errorf("dns message is too short")
	}
	flags := binary.BigEndian.Uint16(msg[2:4])
	if flags&0x8000 == 0 {
		return nil, fmt.Errorf("dns message is not a response")
	}
	if opcode := (flags >> 11) & 0xF; opcode != 0 {
		return nil, fmt.Errorf("dns opcode %d", opcode)
	}
	if rcode := flags & 0xF; rcode != 0 {
		return nil, rcodeError(int(rcode))
	}
	qd := int(binary.BigEndian.Uint16(msg[4:6]))
	an := int(binary.BigEndian.Uint16(msg[6:8]))
	if an > 64 {
		return nil, fmt.Errorf("dns response has %d answers", an)
	}
	off := 12
	for i := 0; i < qd; i++ {
		_, next, err := readName(msg, off)
		if err != nil {
			return nil, err
		}
		if next+4 > len(msg) {
			return nil, fmt.Errorf("dns question is truncated")
		}
		off = next + 4
	}
	var records []Record
	for i := 0; i < an; i++ {
		_, next, err := readName(msg, off)
		if err != nil {
			return nil, err
		}
		off = next
		if off+10 > len(msg) {
			return nil, fmt.Errorf("dns answer is truncated")
		}
		typ := binary.BigEndian.Uint16(msg[off : off+2])
		class := binary.BigEndian.Uint16(msg[off+2 : off+4])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8 : off+10]))
		rdata := off + 10
		if rdata+rdlen > len(msg) {
			return nil, fmt.Errorf("dns rdata is truncated")
		}
		if typ == 33 && class == 1 {
			rec, err := parseSRV(msg, rdata, rdlen)
			if err != nil {
				return nil, err
			}
			records = append(records, rec)
		}
		off = rdata + rdlen
	}
	return records, nil
}

func parseSRV(msg []byte, start, rdlen int) (Record, error) {
	if rdlen < 7 {
		return Record{}, fmt.Errorf("srv rdata is too short")
	}
	target, next, err := readName(msg, start+6)
	if err != nil {
		return Record{}, err
	}
	if next != start+rdlen {
		return Record{}, fmt.Errorf("srv target length does not match rdlength")
	}
	return Record{
		Priority: binary.BigEndian.Uint16(msg[start : start+2]),
		Weight:   binary.BigEndian.Uint16(msg[start+2 : start+4]),
		Port:     binary.BigEndian.Uint16(msg[start+4 : start+6]),
		Target:   target,
	}, nil
}

// readName returns the presentation form, including a trailing dot, and the
// offset of the first byte after this name on the wire. Pointers do not
// advance that offset past the pointer itself.
func readName(msg []byte, off int) (string, int, error) {
	if off < 0 || off >= len(msg) {
		return "", 0, fmt.Errorf("dns name is out of range")
	}
	var b strings.Builder
	seen := make([]int, 0, 8)
	jumped := false
	end := off
	for steps := 0; steps < 128; steps++ {
		if off >= len(msg) {
			return "", 0, fmt.Errorf("dns name is truncated")
		}
		for _, prev := range seen {
			if prev == off {
				return "", 0, fmt.Errorf("dns name pointer loop")
			}
		}
		seen = append(seen, off)
		n := int(msg[off])
		switch n & 0xC0 {
		case 0x00:
			if n == 0 {
				if !jumped {
					end = off + 1
				}
				if b.Len() == 0 {
					return ".", end, nil
				}
				return b.String(), end, nil
			}
			if off+1+n > len(msg) {
				return "", 0, fmt.Errorf("dns label is truncated")
			}
			label := msg[off+1 : off+1+n]
			if err := checkLabel(string(label)); err != nil {
				return "", 0, err
			}
			b.Write(label)
			b.WriteByte('.')
			off += 1 + n
			if !jumped {
				end = off
			}
		case 0xC0:
			if off+1 >= len(msg) {
				return "", 0, fmt.Errorf("dns pointer is truncated")
			}
			ptr := int(n&0x3F)<<8 | int(msg[off+1])
			if ptr >= len(msg) {
				return "", 0, fmt.Errorf("dns pointer is out of range")
			}
			if !jumped {
				end = off + 2
				jumped = true
			}
			off = ptr
		default:
			return "", 0, fmt.Errorf("dns name has a reserved label type")
		}
	}
	return "", 0, fmt.Errorf("dns name is too long")
}

func rcodeError(code int) error {
	switch code {
	case 1:
		return fmt.Errorf("dns rcode formerr")
	case 2:
		return fmt.Errorf("dns rcode servfail")
	case 3:
		return fmt.Errorf("dns rcode nxdomain")
	case 5:
		return fmt.Errorf("dns rcode refused")
	default:
		return fmt.Errorf("dns rcode %d", code)
	}
}
