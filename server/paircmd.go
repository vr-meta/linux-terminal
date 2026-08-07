package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// `linux-terminal-server --pair` — open a pairing window from a terminal.
//
// The console has a button for this, and on a machine with a screen that is the
// better way. This exists for the machine without one: a box in a cupboard
// reached over ssh, where the console is only available through a tunnel and
// asking somebody to set one up before they can pair a headset is a poor answer.
//
// It talks to the running server rather than doing the work itself, because the
// code has to live in the process that will be asked to check it. A second
// process generating codes into a config file would be a second source of truth
// and would race with the first.

// The console's usual port. Only used when --pair was given without --http,
// which is the ordinary case: the running server has a console somewhere and
// this command has to guess where before making the person say it twice.
const defaultConsolePort = 9104

func openPairing(httpPort int) int {
	if httpPort <= 0 {
		httpPort = defaultConsolePort
	}

	url := fmt.Sprintf("http://127.0.0.1:%d/api/pairing", httpPort)
	response, err := http.Post(url, "application/json", nil)
	if err != nil {
		fmt.Fprintf(os.Stderr,
			"no server answered on %s.\n\n"+
				"A running server needs its console on for this — start it with --http %d,\n"+
				"or say which port it is on: --pair --http <port>.\n", url, defaultConsolePort)
		return 1
	}
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != http.StatusOK {
		fmt.Fprintf(os.Stderr, "the server refused: %s\n", strings.TrimSpace(string(body)))
		return 1
	}

	var state struct {
		Code        string `json:"code"`
		Seconds     int    `json:"seconds"`
		Fingerprint string `json:"fingerprint"`
	}
	if json.Unmarshal(body, &state) != nil || state.Code == "" {
		fmt.Fprintln(os.Stderr, "the server answered, but with no code")
		return 1
	}

	printPairing(state.Code, state.Fingerprint, time.Duration(state.Seconds)*time.Second)
	return 0
}

// printPairing lays the code out to be read across a room and typed once.
//
// Spaced in pairs because that is how a person reads six digits back to
// themselves, and boxed because a code that has scrolled into the middle of a
// log is a code nobody can find.
func printPairing(code, fingerprint string, left time.Duration) {
	spaced := code
	if len(code) == 6 {
		spaced = code[0:2] + " " + code[2:4] + " " + code[4:6]
	}

	line := strings.Repeat("─", 34)
	fmt.Println()
	fmt.Println("  ┌" + line + "┐")
	fmt.Printf("  │%s│\n", center("", 34))
	fmt.Printf("  │%s│\n", center(spaced, 34))
	fmt.Printf("  │%s│\n", center("", 34))
	fmt.Printf("  │%s│\n", center(fmt.Sprintf("expires in %s", left.Round(time.Second)), 34))
	fmt.Println("  └" + line + "┘")
	fmt.Println()
	fmt.Println("  Type it into the headset when it asks.")
	fmt.Println("  It works once, and five wrong answers end it.")
	fmt.Println()
	fmt.Println("  The headset will show a fingerprint. It should read:")
	fmt.Printf("  %s\n", fingerprint)
	fmt.Println()
}

func center(text string, width int) string {
	runes := []rune(text)
	if len(runes) >= width {
		return text
	}
	left := (width - len(runes)) / 2
	right := width - len(runes) - left
	return strings.Repeat(" ", left) + text + strings.Repeat(" ", right)
}
