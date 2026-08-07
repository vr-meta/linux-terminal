package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
)

// The console is told things rather than asked to keep asking.
//
// It used to poll twice a second, which is why a headset finishing its pairing
// left the page showing a code that had already stopped working: the truth was
// two seconds old and nothing announced the moment itself. Polling can show a
// state; it cannot report an event, and "that worked" is an event.
//
// Server-Sent Events rather than WebSockets, and that is the whole reason this
// file is fifty lines. Everything here goes one way — the console watches, it
// does not talk back over this channel — and SSE is one-way by design: plain
// HTTP, EventSource in the browser, http.Flusher in Go, no dependency at either
// end. A WebSocket would buy a return path nobody uses and cost a library.
//
// Nothing is stored per subscriber. Each gets a small buffered channel and is
// dropped if it cannot keep up, because a console that has stopped reading must
// never be able to block a session from starting.

const eventBuffer = 8

type broker struct {
	mu          sync.Mutex
	subscribers map[chan []byte]struct{}
}

func newBroker() *broker {
	return &broker{subscribers: make(map[chan []byte]struct{})}
}

func (b *broker) subscribe() chan []byte {
	channel := make(chan []byte, eventBuffer)
	b.mu.Lock()
	b.subscribers[channel] = struct{}{}
	b.mu.Unlock()
	return channel
}

func (b *broker) unsubscribe(channel chan []byte) {
	b.mu.Lock()
	delete(b.subscribers, channel)
	b.mu.Unlock()
	close(channel)
}

// publish sends to everyone listening and skips anyone who is behind.
func (b *broker) publish(name string, value any) {
	payload, err := json.Marshal(value)
	if err != nil {
		return
	}
	message := []byte(fmt.Sprintf("event: %s\ndata: %s\n\n", name, payload))

	b.mu.Lock()
	defer b.mu.Unlock()
	for channel := range b.subscribers {
		select {
		case channel <- message:
		default:
			// Behind, and not worth waiting for. The next event it does receive
			// carries the whole state, so a dropped one costs nothing but
			// timeliness.
		}
	}
}

// handleEvents streams to one console.
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	// Buffering by anything in the middle would defeat the point; there is no
	// proxy here today, and saying so costs one header.
	w.Header().Set("X-Accel-Buffering", "no")

	channel := s.Events.subscribe()
	defer s.Events.unsubscribe(channel)

	// The current state first, so a console that has just opened is correct
	// before anything happens rather than blank until something does.
	if payload, err := json.Marshal(s.snapshot()); err == nil {
		fmt.Fprintf(w, "event: state\ndata: %s\n\n", payload)
		flusher.Flush()
	}

	for {
		select {
		case message, open := <-channel:
			if !open {
				return
			}
			if _, err := w.Write(message); err != nil {
				return
			}
			flusher.Flush()
		case <-r.Context().Done():
			return
		}
	}
}

// announce publishes the whole state. Callers say what happened; everyone
// receives the same complete picture, so no listener has to reassemble one from
// a sequence it might have missed part of.
func (s *Server) announce(name string) {
	s.Events.publish(name, s.snapshot())
}
