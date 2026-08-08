package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base32"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"math/big"
	"strings"
	"time"
)

// The machine's own identity: a certificate it made for itself, and a token a
// client has to know.
//
// Why a self-signed certificate rather than a pre-shared key is argued in
// docs/security.md, and the short version is that Go's TLS does not implement
// external PSKs at all — only session resumption — so the choice was between one
// certificate to manage and a third-party TLS stack. A certificate the client
// pins is an identity; unpinned it proves nothing, which is why pairing exists.
//
// Both live in the config file at 0600 beside the transcription key, and neither
// is ever sent anywhere. The token is shown in the console and typed into the
// headset once; the fingerprint is shown in both places so that pairing is a
// comparison rather than an act of faith.

// Identity is the persisted half. Generated on first run and then left alone.
type Identity struct {
	CertPEM string `json:"cert_pem,omitempty"`
	KeyPEM  string `json:"key_pem,omitempty"`
	Token   string `json:"token,omitempty"`
}

// tokenBytes is 20 bytes — 160 bits — rendered as base32 without padding, which
// gives 32 characters a person can read aloud without ambiguity. Long enough
// that guessing is not a strategy, short enough to type once into a headset.
const tokenBytes = 20

func newToken() string {
	raw := make([]byte, tokenBytes)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand failing is not a condition to carry on through: every
		// secret this process will ever make comes from here.
		panic("no randomness available: " + err.Error())
	}
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw))
}

// ensureIdentity fills in whatever is missing and reports whether anything was
// written, so the caller can persist it exactly once.
func ensureIdentity(identity *Identity, hostname string) (bool, error) {
	changed := false

	if identity.Token == "" {
		identity.Token = newToken()
		changed = true
	}

	if identity.CertPEM == "" || identity.KeyPEM == "" {
		certPEM, keyPEM, err := selfSigned(hostname)
		if err != nil {
			return changed, err
		}
		identity.CertPEM, identity.KeyPEM = certPEM, keyPEM
		changed = true
	}

	return changed, nil
}

// selfSigned makes the certificate this machine will be known by.
//
// P-256 rather than RSA: a headset does this handshake on every reconnect, and
// the elliptic curve is an order of magnitude cheaper on both ends for the same
// strength. Ten years rather than ninety days because nothing renews it — the
// client trusts the fingerprint it pinned, not the dates, and a certificate that
// expires would break a working setup for a reason no one would guess.
func selfSigned(hostname string) (string, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", err
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return "", "", err
	}

	template := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: hostname},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{hostname},
	}

	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return "", "", err
	}

	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return "", "", err
	}

	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	return string(certPEM), string(keyPEM), nil
}

// tlsConfig builds the listener's side of the handshake.
//
// TLS 1.3 only. There is exactly one client, it is ours, and it is new — so
// there is nobody to be compatible with and no reason to carry the older
// protocol's weaker halves.
func (identity Identity) tlsConfig() (*tls.Config, error) {
	pair, err := tls.X509KeyPair([]byte(identity.CertPEM), []byte(identity.KeyPEM))
	if err != nil {
		return nil, fmt.Errorf("the stored certificate is unusable: %w", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{pair},
		MinVersion:   tls.VersionTLS13,
	}, nil
}

// Fingerprint is what a person compares between the console and the headset:
// SHA-256 of the certificate, in hex pairs. Of the certificate rather than of
// the public key, because that is what the client can compute from what it was
// handed without parsing anything further.
func (identity Identity) Fingerprint() string {
	block, _ := pem.Decode([]byte(identity.CertPEM))
	if block == nil {
		return ""
	}
	sum := sha256.Sum256(block.Bytes)
	hexed := hex.EncodeToString(sum[:])

	var out strings.Builder
	for i := 0; i < len(hexed); i += 2 {
		if i > 0 {
			out.WriteByte(':')
		}
		out.WriteString(hexed[i : i+2])
	}
	return out.String()
}

// tokenMatches compares in constant time.
//
// The obvious == returns as soon as two bytes differ, and how long that took is
// a measurement anyone can make over a network: a few thousand attempts and the
// token has been read one character at a time without ever being guessed.
func (identity Identity) tokenMatches(offered string) bool {
	// A server with no token must let nobody in, not everybody. The comparison
	// below says two empty strings are equal — correctly, it is comparing
	// nothing to nothing — so without this line an identity that never got
	// generated would accept {"token":""} from anyone who asked. ensureIdentity
	// makes that unreachable today; it costs one branch to keep it unreachable
	// when somebody adds a second way to build a Server.
	if identity.Token == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(identity.Token), []byte(offered)) == 1
}
