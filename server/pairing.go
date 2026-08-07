package main

import (
	"crypto/rand"
	"crypto/subtle"
	"fmt"
	"math/big"
	"sync"
	"time"
)

// The six digits a person types, and why they are safe to be only six.
//
// A token long enough to resist guessing is a token nobody wants to type into a
// headset. So there are two secrets rather than one, which is how Bluetooth and
// every set-top box have always done it: a short code a human enters once, and a
// long one the machines agree on and the human never sees.
//
// Six digits is a million possibilities, which a network can walk through in
// minutes if allowed to. It is not allowed to:
//
//	the code expires,
//	it survives a fixed, small number of wrong answers and then dies,
//	it is single use — pairing consumes it,
//	and it exists only while somebody is deliberately pairing.
//
// Those four together are what make a million enough. Take any one away and it
// is not.

const (
	// Long enough to walk to the other room, short enough that a code left on a
	// screen is not a standing invitation.
	pairWindow = 5 * time.Minute

	// A person mistypes; nobody mistypes five times. An attacker needs hundreds
	// of thousands of guesses, so this costs the honest case nothing and the
	// dishonest one everything.
	pairAttempts = 5
)

// pairing is the live, unsaved half of the identity. Deliberately not persisted:
// a code that outlives a restart is a code nobody is watching.
type pairing struct {
	mu       sync.Mutex
	code     string
	expires  time.Time
	attempts int
}

// begin issues a code and starts the clock, replacing whatever was there.
func (p *pairing) begin() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.code = sixDigits()
	p.expires = time.Now().Add(pairWindow)
	p.attempts = 0
	return p.code
}

// stop ends the window immediately.
func (p *pairing) stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.code = ""
}

// state is what the console shows: the code if one is live, and how long is left.
func (p *pairing) state() (string, int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.code == "" || time.Now().After(p.expires) {
		return "", 0
	}
	return p.code, int(time.Until(p.expires).Seconds())
}

// accepts reports whether this is the code, and burns an attempt if it is not.
//
// The comparison is constant time for the same reason the token's is: an answer
// that takes longer the closer you get is an answer that can be walked to.
func (p *pairing) accepts(offered string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.code == "" || time.Now().After(p.expires) {
		return false
	}
	if subtle.ConstantTimeCompare([]byte(p.code), []byte(offered)) != 1 {
		p.attempts++
		if p.attempts >= pairAttempts {
			// Not a lockout with a timer — the code is simply gone, and a new one
			// has to be asked for by somebody at the machine. That turns an online
			// guessing attack into a request for physical presence.
			p.code = ""
		}
		return false
	}
	// Single use: pairing is over, and the long token is what continues.
	p.code = ""
	return true
}

func sixDigits() string {
	limit := big.NewInt(1000000)
	n, err := rand.Int(rand.Reader, limit)
	if err != nil {
		panic("no randomness available: " + err.Error())
	}
	return fmt.Sprintf("%06d", n.Int64())
}
