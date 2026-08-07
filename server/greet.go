package main

import (
	"bufio"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"time"
)

// What happens between a socket being accepted and a shell existing.
//
// Two things have to be true before a pty is allocated: the connection is
// encrypted, and whoever is on it knows the token. Neither was true before, and
// the second is meaningless without the first — a token sent over a plain socket
// is read by anyone listening, which changes who feels safe rather than who is.
//
// The first byte still decides whether this is TLS — a record always opens 0x16
// — but only so that a client speaking the old plain protocol gets a clear line
// in the log rather than a handshake error nobody can read. Nothing is served
// over it.

// tlsRecordHandshake is the first byte of every TLS connection there has ever
// been: ContentType 22, handshake.
const tlsRecordHandshake = 0x16

// authWindow is how long a client has to present its token. Long enough for a
// headset waking up on a slow network, short enough that holding sockets open
// costs an attacker something.
const authWindow = 15 * time.Second

// greet decides what kind of connection this is and whether it is allowed to
// become a session.
func (s *Server) greet(conn net.Conn, settings *tls.Config) {
	peeker := bufio.NewReader(conn)
	first, err := peeker.Peek(1)
	if err != nil {
		_ = conn.Close()
		return
	}

	if first[0] != tlsRecordHandshake {
		log.Printf("refused an unencrypted connection from %s", conn.RemoteAddr())
		_ = conn.Close()
		return
	}

	secured := tls.Server(&peeked{Conn: conn, reader: peeker}, settings)
	if err := secured.SetDeadline(time.Now().Add(authWindow)); err == nil {
		if err := secured.Handshake(); err != nil {
			log.Printf("handshake with %s failed: %v", conn.RemoteAddr(), err)
			_ = secured.Close()
			return
		}
	}

	switch s.authorised(secured) {
	case authPaired:
		// The six digits were right. Hand over the long token and close: the client
		// stores it and comes back with it, so the code is never what a session
		// runs on.
		s.sendFrame(secured, msgPaired, fmt.Sprintf("{%q:%q}", "token", s.Identity.Token))
		log.Printf("paired with %s", conn.RemoteAddr())
		// Said as an event rather than left for the console to notice: "a headset
		// just paired" is the thing a person is waiting to be told, and a state
		// that quietly stops showing a code does not tell them.
		s.Events.publish("paired", map[string]any{"remote": conn.RemoteAddr().String()})
		s.announce("pairing")
		time.Sleep(200 * time.Millisecond)
		_ = secured.Close()
		return
	case authPeeked:
		// Handshake completed, nothing sent, gone. That is a client reading the
		// certificate so a person can compare its fingerprint before pairing —
		// the one connection that legitimately has no token yet. Logging it as a
		// rejection made every pairing look like an attack on the log.
		_ = secured.Close()
		return
	case authRefused:
		// The same answer to every wrong token, after the same delay. A reply
		// that varies tells whoever is trying how close they got.
		time.Sleep(time.Second)
		log.Printf("rejected %s: wrong token", conn.RemoteAddr())
		_ = secured.Close()
		return
	}

	_ = secured.SetDeadline(time.Time{})
	s.serveSession(secured)
}

type authResult int

const (
	authRefused authResult = iota
	authAccepted
	authPeeked
	authPaired
)

// authorised reads the first frame and expects it to be the token.
func (s *Server) authorised(conn net.Conn) authResult {
	header := make([]byte, 5)
	if _, err := io.ReadFull(conn, header); err != nil {
		// Nothing at all before the socket closed: a fingerprint read, not a
		// guess. Told apart from a wrong token because it is a different act and
		// deserves a different entry in the log.
		return authPeeked
	}
	if header[0] != msgAuth {
		return authRefused
	}
	length := binary.BigEndian.Uint32(header[1:])
	if length == 0 || length > 4096 {
		return authRefused
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(conn, payload); err != nil {
		return authRefused
	}

	var offered struct {
		Token string `json:"token"`
		Pair  string `json:"pair"`
	}
	if json.Unmarshal(payload, &offered) != nil {
		return authRefused
	}
	if offered.Pair != "" {
		if s.Pairing.accepts(offered.Pair) {
			return authPaired
		}
		return authRefused
	}
	if s.Identity.tokenMatches(offered.Token) {
		return authAccepted
	}
	return authRefused
}

// sendFrame writes one frame outside a session, which pairing needs because it
// answers and hangs up without ever allocating a pty.
func (s *Server) sendFrame(conn net.Conn, kind byte, payload string) {
	header := make([]byte, 5)
	header[0] = kind
	binary.BigEndian.PutUint32(header[1:], uint32(len(payload)))
	if _, err := conn.Write(header); err != nil {
		return
	}
	_, _ = conn.Write([]byte(payload))
}

// peeked hands back the bytes that were read to work out what this connection
// was, so whoever reads next sees the stream from the beginning.
type peeked struct {
	net.Conn
	reader *bufio.Reader
}

func (p *peeked) Read(into []byte) (int, error) {
	return p.reader.Read(into)
}
