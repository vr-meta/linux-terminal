package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// What happens between accept() and a shell existing. These are the checks that
// decide whether a stranger on the network gets one, so they are written against
// the wire — a real TLS handshake, real frames — rather than against the
// functions' insides.

func testServer(t *testing.T) *Server {
	t.Helper()
	server := &Server{Name: "test-host", Events: newBroker()}
	if _, err := ensureIdentity(&server.Identity, "test-host"); err != nil {
		t.Fatal(err)
	}
	return server
}

// authFrame is what the client sends first: type, big-endian length, JSON.
func authFrame(t *testing.T, body any) []byte {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	frame := make([]byte, 5, 5+len(payload))
	frame[0] = msgAuth
	binary.BigEndian.PutUint32(frame[1:], uint32(len(payload)))
	return append(frame, payload...)
}

// offer runs one authorisation against a pipe and reports the verdict.
func offer(t *testing.T, server *Server, send []byte) authResult {
	t.Helper()
	ours, theirs := net.Pipe()
	defer ours.Close()

	go func() {
		defer theirs.Close()
		if len(send) > 0 {
			_ = theirs.SetWriteDeadline(time.Now().Add(2 * time.Second))
			_, _ = theirs.Write(send)
		}
	}()

	_ = ours.SetDeadline(time.Now().Add(2 * time.Second))
	return server.authorised(ours)
}

func TestTheRightTokenIsAccepted(t *testing.T) {
	server := testServer(t)
	got := offer(t, server, authFrame(t, map[string]string{"token": server.Identity.Token}))
	if got != authAccepted {
		t.Errorf("verdict %v, want accepted", got)
	}
}

func TestWrongTokensAreRefused(t *testing.T) {
	server := testServer(t)
	token := server.Identity.Token

	cases := map[string][]byte{
		"a different token":  authFrame(t, map[string]string{"token": newToken()}),
		"one character out":  authFrame(t, map[string]string{"token": token[:len(token)-1] + "z"}),
		"a truncated token":  authFrame(t, map[string]string{"token": token[:8]}),
		"an empty token":     authFrame(t, map[string]string{"token": ""}),
		"no token field":     authFrame(t, map[string]string{"hello": "there"}),
		"not JSON at all":    append([]byte{msgAuth, 0, 0, 0, 4}, []byte("{{{{")...),
		"the wrong frame":    append([]byte{msgData, 0, 0, 0, 4}, []byte("ping")...),
		"a zero-length body": {msgAuth, 0, 0, 0, 0},
		// 4097 bytes claimed, which the reader refuses before allocating it.
		"an absurd length": {msgAuth, 0, 0, 0x10, 0x01},
	}
	for name, frame := range cases {
		if got := offer(t, server, frame); got != authRefused {
			t.Errorf("%s: verdict %v, want refused", name, got)
		}
	}
}

// A client that completes the handshake, says nothing and leaves is reading the
// certificate so a person can compare its fingerprint. It is not a rejection,
// and logging it as one made every pairing look like an attack.
func TestSayingNothingIsAPeekNotAnAttack(t *testing.T) {
	server := testServer(t)
	if got := offer(t, server, nil); got != authPeeked {
		t.Errorf("verdict %v, want peeked", got)
	}
}

func TestPairingCodeIsAcceptedOnceAndBurnsAttempts(t *testing.T) {
	server := testServer(t)
	code := server.Pairing.begin()

	if got := offer(t, server, authFrame(t, map[string]string{"pair": code})); got != authPaired {
		t.Fatalf("verdict %v, want paired", got)
	}
	// Consumed. The long token is what continues.
	if got := offer(t, server, authFrame(t, map[string]string{"pair": code})); got != authRefused {
		t.Error("the same code paired twice")
	}

	// And a wrong code spends the budget, so guessing over the wire runs out.
	code = server.Pairing.begin()
	wrong := "000000"
	if wrong == code {
		wrong = "111111"
	}
	for i := 0; i < pairAttempts; i++ {
		offer(t, server, authFrame(t, map[string]string{"pair": wrong}))
	}
	if got := offer(t, server, authFrame(t, map[string]string{"pair": code})); got != authRefused {
		t.Error("the window survived five wrong codes over the wire")
	}
}

// A pairing attempt must never be a way to skip having a token: it can only ever
// produce authPaired, which hands over the token and hangs up.
func TestPairingFieldNeverFallsThroughToTokenCheck(t *testing.T) {
	server := testServer(t)
	// No window open, and a "pair" value that is also the correct token.
	frame := authFrame(t, map[string]string{"pair": server.Identity.Token})
	if got := offer(t, server, frame); got != authRefused {
		t.Errorf("verdict %v, want refused — a pair attempt fell through to the token check", got)
	}
}

// ------------------------------------------------------------------- the wire

// listening starts a real listener speaking the server's own TLS settings, and
// returns its address.
func listening(t *testing.T, server *Server, handle func(net.Conn)) string {
	t.Helper()
	settings, err := server.Identity.tlsConfig()
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go handle(tls.Server(conn, settings))
		}
	}()
	return listener.Addr().String()
}

// This is what the headset does: connect, ignore the fact that the certificate
// signs itself, and compare its SHA-256 with the one it pinned.
func TestPinningTheCertificateIsWhatIdentifiesTheMachine(t *testing.T) {
	server := testServer(t)
	address := listening(t, server, func(conn net.Conn) {
		_ = conn.(*tls.Conn).Handshake()
		conn.Close()
	})

	var presented string
	client, err := tls.Dial("tcp", address, &tls.Config{
		InsecureSkipVerify: true, // the pin below is the check, not the CA chain
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			sum := sha256.Sum256(raw[0])
			presented = hex.EncodeToString(sum[:])
			return nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	want := strings.ReplaceAll(server.Identity.Fingerprint(), ":", "")
	if presented != want {
		t.Errorf("the certificate on the wire hashes to %s, but the console shows %s — pairing would be a comparison of two different things",
			presented[:16], want[:16])
	}
}

// Another machine answering on that address is a different fingerprint, which is
// the whole point: "this is not the machine that was paired".
func TestADifferentMachineHasADifferentFingerprint(t *testing.T) {
	first := testServer(t)
	second := testServer(t)
	if first.Identity.Fingerprint() == second.Identity.Fingerprint() {
		t.Fatal("two servers produced the same fingerprint")
	}
}

func TestAClientStuckOnTLS12IsRefused(t *testing.T) {
	server := testServer(t)
	address := listening(t, server, func(conn net.Conn) {
		_ = conn.(*tls.Conn).Handshake()
		conn.Close()
	})

	_, err := tls.Dial("tcp", address, &tls.Config{
		InsecureSkipVerify: true,
		MaxVersion:         tls.VersionTLS12,
	})
	if err == nil {
		t.Error("a TLS 1.2 client completed the handshake")
	}
}

// greet() is where a plain socket is turned away. The client speaks first in
// this protocol, so a connection that opens with anything but 0x16 is either an
// old client or not our client at all — and either way gets nothing.
func TestAPlainConnectionIsRefusedBeforeAnythingElse(t *testing.T) {
	server := testServer(t)
	settings, err := server.Identity.tlsConfig()
	if err != nil {
		t.Fatal(err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		server.greet(conn, settings)
	}()

	client, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	// The old plain protocol's first frame: a resize, not a TLS record.
	if _, err := client.Write([]byte{msgResize, 0, 0, 0, 2, '{', '}'}); err != nil {
		t.Fatal(err)
	}

	_ = client.SetReadDeadline(time.Now().Add(3 * time.Second))
	buffer := make([]byte, 1)
	n, err := client.Read(buffer)
	if err == nil && n > 0 {
		t.Fatalf("the server answered a plain connection with %d bytes", n)
	}
	if err != io.EOF && !strings.Contains(fmt.Sprint(err), "reset") {
		t.Errorf("expected the connection to be closed, got %v", err)
	}
}

// The full ceremony over a real socket: pair with the six digits, receive the
// long token, and come back with it.
func TestPairingOverTheWireHandsBackTheToken(t *testing.T) {
	server := testServer(t)
	settings, err := server.Identity.tlsConfig()
	if err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			// greet() would go on to allocate a pty for an accepted token; this
			// test is about the pairing branch, which answers and hangs up.
			go func() {
				secured := tls.Server(conn, settings)
				if err := secured.Handshake(); err != nil {
					return
				}
				if server.authorised(secured) == authPaired {
					server.sendFrame(secured, msgPaired,
						fmt.Sprintf("{%q:%q}", "token", server.Identity.Token))
				}
				secured.Close()
			}()
		}
	}()

	code := server.Pairing.begin()
	client, err := tls.Dial("tcp", listener.Addr().String(), &tls.Config{InsecureSkipVerify: true})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	if _, err := client.Write(authFrame(t, map[string]string{"pair": code})); err != nil {
		t.Fatal(err)
	}

	_ = client.SetReadDeadline(time.Now().Add(5 * time.Second))
	header := make([]byte, 5)
	if _, err := io.ReadFull(client, header); err != nil {
		t.Fatalf("no answer to a correct code: %v", err)
	}
	if header[0] != msgPaired {
		t.Fatalf("frame type %#x, want msgPaired", header[0])
	}
	body := make([]byte, binary.BigEndian.Uint32(header[1:]))
	if _, err := io.ReadFull(client, body); err != nil {
		t.Fatal(err)
	}

	var handed struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(body, &handed); err != nil {
		t.Fatal(err)
	}
	if handed.Token != server.Identity.Token {
		t.Error("the token handed over is not the one the server checks")
	}
	// And it is the long one, not the six digits.
	if len(handed.Token) != 32 {
		t.Errorf("handed over %d characters — pairing must not leave the short code as the secret", len(handed.Token))
	}
}
