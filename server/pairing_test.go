package main

import (
	"testing"
	"time"
)

// Six digits is a million possibilities, which a network walks through in
// minutes if it is allowed to. Four things stop it: the window, the attempt
// count, single use, and not existing until somebody asks. Each one is a test
// here, because taking any one away leaves something that still looks like it
// works.

func TestSixDigitsIsAlwaysSixDigits(t *testing.T) {
	// Including the ones with leading zeros, which a naive %d would print short
	// and a person would then type as five characters.
	for i := 0; i < 2000; i++ {
		code := sixDigits()
		if len(code) != 6 {
			t.Fatalf("got %q, which is %d characters", code, len(code))
		}
		for _, r := range code {
			if r < '0' || r > '9' {
				t.Fatalf("got %q, which is not all digits", code)
			}
		}
	}
}

func TestCodeIsAcceptedOnce(t *testing.T) {
	var p pairing
	code := p.begin()

	if !p.accepts(code) {
		t.Fatal("the code it just issued was refused")
	}
	// Single use: what continues from here is the long token, and a code that
	// still works is a code left lying on a screen.
	if p.accepts(code) {
		t.Error("the same code was accepted twice")
	}
}

func TestNoCodeMeansNothingIsAccepted(t *testing.T) {
	var p pairing

	// Nobody has asked to pair. Not "wrong code" — there is no code.
	if p.accepts("000000") {
		t.Error("a code was accepted before any window was opened")
	}
	if code, seconds := p.state(); code != "" || seconds != 0 {
		t.Errorf("state reports %q with %ds left before anything began", code, seconds)
	}
}

func TestFiveWrongAnswersEndTheWindow(t *testing.T) {
	var p pairing
	code := p.begin()

	wrong := "000000"
	if wrong == code {
		wrong = "111111"
	}
	for i := 0; i < pairAttempts; i++ {
		if p.accepts(wrong) {
			t.Fatalf("a wrong code was accepted on attempt %d", i+1)
		}
	}

	// The right code, after the budget is spent. It has to fail: an attacker who
	// guesses on the last attempt must not be rewarded, and the honest person
	// simply asks for another code.
	if p.accepts(code) {
		t.Error("the window survived five wrong answers")
	}
	if code, _ := p.state(); code != "" {
		t.Error("the console would still be showing a dead code")
	}
}

func TestWrongAnswersOnlyCountWithinOneWindow(t *testing.T) {
	var p pairing
	p.begin()
	for i := 0; i < pairAttempts-1; i++ {
		p.accepts("000000")
	}

	// A person who mistyped four times and asked for a new code gets a full
	// budget, not one attempt.
	code := p.begin()
	for i := 0; i < pairAttempts-1; i++ {
		p.accepts("000000")
	}
	if !p.accepts(code) {
		t.Error("attempts carried over from the previous window")
	}
}

func TestExpiredCodeIsRefused(t *testing.T) {
	var p pairing
	code := p.begin()

	// Reaching in rather than waiting five minutes. Same field the clock moves.
	p.mu.Lock()
	p.expires = time.Now().Add(-time.Second)
	p.mu.Unlock()

	if p.accepts(code) {
		t.Error("an expired code was accepted")
	}
	if shown, seconds := p.state(); shown != "" || seconds != 0 {
		t.Errorf("state still offers %q with %ds left after expiry", shown, seconds)
	}
}

func TestStopEndsItImmediately(t *testing.T) {
	var p pairing
	code := p.begin()
	p.stop()

	if p.accepts(code) {
		t.Error("a stopped window still accepted its code")
	}
}

func TestStateCountsDown(t *testing.T) {
	var p pairing
	code := p.begin()

	shown, seconds := p.state()
	if shown != code {
		t.Errorf("state shows %q, want the live code", shown)
	}
	if seconds <= 0 || seconds > int(pairWindow.Seconds()) {
		t.Errorf("state reports %ds left, want between 1 and %d", seconds, int(pairWindow.Seconds()))
	}
}

func TestBeginReplacesTheOldCode(t *testing.T) {
	var p pairing
	first := p.begin()
	second := p.begin()

	if first == second {
		// Not impossible — one in a million — but worth knowing if it ever fires.
		t.Skip("the same code was drawn twice; run again")
	}
	if p.accepts(first) {
		t.Error("the previous code still works after a new one was issued")
	}
	if !p.accepts(second) {
		t.Error("the current code was refused")
	}
}

// The window is read by the console and written by whoever is pairing, from
// different goroutines. Run with -race, which CI does.
func TestPairingIsSafeUnderConcurrentUse(t *testing.T) {
	var p pairing
	code := p.begin()

	done := make(chan struct{})
	for i := 0; i < 8; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			for j := 0; j < 50; j++ {
				p.state()
				p.accepts("000000")
			}
		}()
	}
	for i := 0; i < 8; i++ {
		<-done
	}

	// Four hundred wrong answers later, the code must be long gone.
	if p.accepts(code) {
		t.Error("the code survived four hundred wrong answers")
	}
}
