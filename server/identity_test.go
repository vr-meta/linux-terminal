package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"strings"
	"testing"
	"time"
)

// The certificate and the token: the two things that decide whether a stranger
// on the network gets a shell. Everything here is a property somebody could
// weaken without noticing — a shorter token, a wider TLS version, a comparison
// that returns early — and none of it fails loudly on its own.

func testIdentity(t *testing.T) Identity {
	t.Helper()
	var identity Identity
	if _, err := ensureIdentity(&identity, "test-host"); err != nil {
		t.Fatal(err)
	}
	return identity
}

func TestTokenIsLongAndUnguessable(t *testing.T) {
	token := newToken()

	// 20 bytes in base32 without padding. If this changes, it is a decision, not
	// a refactor: the whole argument for the short pairing code is that the thing
	// it hands over is long.
	if len(token) != 32 {
		t.Errorf("token is %d characters, want 32 (%d bytes of base32)", len(token), tokenBytes)
	}
	for _, r := range token {
		if !strings.ContainsRune("abcdefghijklmnopqrstuvwxyz234567", r) {
			t.Errorf("token contains %q, which is not lowercase base32", r)
		}
	}

	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		next := newToken()
		if seen[next] {
			t.Fatal("newToken repeated itself within 200 draws")
		}
		seen[next] = true
	}
}

func TestEnsureIdentityFillsOnceAndThenLeavesItAlone(t *testing.T) {
	var identity Identity

	changed, err := ensureIdentity(&identity, "test-host")
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("first run reported nothing to save")
	}
	if identity.Token == "" || identity.CertPEM == "" || identity.KeyPEM == "" {
		t.Fatal("first run left part of the identity empty")
	}

	before := identity
	changed, err = ensureIdentity(&identity, "test-host")
	if err != nil {
		t.Fatal(err)
	}
	// This is what makes a restart survivable. Regenerating here would hand every
	// paired headset a machine it refuses as an impostor.
	if changed {
		t.Error("second run wanted to write again")
	}
	if identity != before {
		t.Error("second run changed the identity")
	}
}

func TestCertificateIsTheShapeTheClientExpects(t *testing.T) {
	identity := testIdentity(t)

	block, _ := pem.Decode([]byte(identity.CertPEM))
	if block == nil {
		t.Fatal("the certificate is not PEM")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}

	if _, ok := cert.PublicKey.(*ecdsa.PublicKey); !ok {
		t.Errorf("public key is %T, want ECDSA — a headset does this handshake on every reconnect", cert.PublicKey)
	}
	if key := cert.PublicKey.(*ecdsa.PublicKey); key.Curve != elliptic.P256() {
		t.Errorf("curve is %v, want P-256", key.Curve.Params().Name)
	}
	if cert.Subject.CommonName != "test-host" {
		t.Errorf("common name is %q, want the hostname", cert.Subject.CommonName)
	}

	// Nothing renews this. An expiry inside a few years is a working setup that
	// breaks one day for a reason nobody would guess.
	years := cert.NotAfter.Sub(time.Now()).Hours() / 24 / 365
	if years < 9 {
		t.Errorf("certificate expires in %.1f years, want about ten", years)
	}
	if !cert.NotBefore.Before(time.Now()) {
		t.Error("certificate is not valid yet — a clock skew of minutes would break pairing")
	}
}

func TestTLSConfigRefusesAnythingBelow13(t *testing.T) {
	identity := testIdentity(t)

	settings, err := identity.tlsConfig()
	if err != nil {
		t.Fatal(err)
	}
	if settings.MinVersion != tls.VersionTLS13 {
		t.Errorf("MinVersion is %#x, want TLS 1.3 — there is exactly one client and it is ours", settings.MinVersion)
	}
	if len(settings.Certificates) != 1 {
		t.Fatalf("got %d certificates, want 1", len(settings.Certificates))
	}
}

func TestTLSConfigRejectsAnUnusableStoredCertificate(t *testing.T) {
	identity := Identity{CertPEM: "not a certificate", KeyPEM: "nor a key"}
	if _, err := identity.tlsConfig(); err == nil {
		t.Error("a corrupt certificate was accepted — the server would start and refuse every headset instead")
	}
}

// The fingerprint is the thing a person reads off one screen and compares with
// another. It has to be the hash of what the client is actually handed.
func TestFingerprintIsTheHashOfTheCertificate(t *testing.T) {
	identity := testIdentity(t)

	block, _ := pem.Decode([]byte(identity.CertPEM))
	sum := sha256.Sum256(block.Bytes)
	want := hex.EncodeToString(sum[:])

	got := identity.Fingerprint()
	if strings.ReplaceAll(got, ":", "") != want {
		t.Errorf("fingerprint is %q, which is not SHA-256 of the certificate", got)
	}
	if parts := strings.Split(got, ":"); len(parts) != 32 {
		t.Errorf("fingerprint has %d groups, want 32 hex pairs — this is read aloud", len(parts))
	}

	other := testIdentity(t)
	if other.Fingerprint() == got {
		t.Error("two identities share a fingerprint")
	}
}

func TestFingerprintOfNothingIsEmpty(t *testing.T) {
	var identity Identity
	if got := identity.Fingerprint(); got != "" {
		t.Errorf("fingerprint of an empty identity is %q, want empty — the console must not print a hash of nothing", got)
	}
}

func TestTokenMatching(t *testing.T) {
	identity := testIdentity(t)
	token := identity.Token

	if !identity.tokenMatches(token) {
		t.Error("the right token was refused")
	}
	for _, wrong := range []string{
		"",
		token[:len(token)-1],       // one short
		token + "x",                // one long
		token[:len(token)-1] + "z", // one character out
		strings.ToUpper(token),     // case matters
	} {
		if identity.tokenMatches(wrong) {
			t.Errorf("token %q was accepted", wrong)
		}
	}
}

// A server that never generated a token must let nobody in. The obvious
// constant-time compare says two empty strings are equal, so without a guard an
// identity that failed to initialise would accept `{"token":""}` from anyone.
func TestEmptyStoredTokenMatchesNothing(t *testing.T) {
	var identity Identity
	if identity.tokenMatches("") {
		t.Error("a server with no token accepted an empty token")
	}
	if identity.tokenMatches("anything") {
		t.Error("a server with no token accepted a token")
	}
}
