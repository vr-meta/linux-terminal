package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Speech recognition, proxied by the server.
//
// The headset records; the server transcribes and hands the text back over the
// same session. Two reasons it belongs here rather than in the client:
//
//   - The key. A key shipped to a headset is a key on a device you carry into
//     other people's houses. On the server it sits in a file only you can read,
//     and the client never learns it.
//   - One address. The client already has a connection to this machine; adding a
//     second endpoint would mean a second port, a second firewall rule and a
//     second thing to configure. Audio rides the connection that exists.
//
// Any OpenAI-compatible transcription endpoint works — the official one, or a
// Whisper you run yourself. The difference is a URL.

const (
	openAIEndpoint = "https://api.openai.com/v1/audio/transcriptions"
	defaultModel   = "whisper-1"
)

// ASR is where to send audio and how to authenticate.
type ASR struct {
	URL      string `json:"url"`
	Model    string `json:"model"`
	Key      string `json:"key"`
	Language string `json:"language"`
}

// Config is the server's own settings file.
type Config struct {
	ASR ASR `json:"asr"`
}

func configPath() string {
	if path := os.Getenv("LINUX_TERMINAL_CONFIG"); path != "" {
		return path
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".config/linux-terminal/config.json")
}

// loadConfig reads the settings file, then lets flags and the environment
// override it. The environment wins over the file so a key can be handed to a
// systemd unit without writing it down anywhere.
func loadConfig() Config {
	var config Config
	if data, err := os.ReadFile(configPath()); err == nil {
		if err := json.Unmarshal(data, &config); err != nil {
			// A malformed config is a mistake worth hearing about: silently
			// falling back looks identical to the file being ignored.
			fmt.Fprintf(os.Stderr, "warning: %s is not valid JSON: %v\n", configPath(), err)
		}
	}

	if *flagASRURL != "" {
		config.ASR.URL = *flagASRURL
	}
	if *flagASRModel != "" {
		config.ASR.Model = *flagASRModel
	}
	if *flagASRLanguage != "" {
		config.ASR.Language = *flagASRLanguage
	}
	if key := os.Getenv("LINUX_TERMINAL_ASR_KEY"); key != "" {
		config.ASR.Key = key
	}

	// A key on its own means the official endpoint: that is what someone who
	// pasted an OpenAI key expects, and making them also paste a URL is a step
	// with exactly one possible answer.
	if config.ASR.URL == "" && config.ASR.Key != "" {
		config.ASR.URL = openAIEndpoint
	}
	if config.ASR.Model == "" {
		config.ASR.Model = defaultModel
	}
	return config
}

// configured reports whether dictation can work at all, so the headset can say
// so on the button instead of failing after you have already spoken.
func (a ASR) configured() bool {
	return a.URL != ""
}

// transcribe sends raw PCM to the endpoint and returns what it heard.
func (a ASR) transcribe(pcm []byte, rate, channels int) (string, error) {
	if !a.configured() {
		return "", fmt.Errorf("no transcription endpoint configured — see docs/voice.md")
	}

	body := &bytes.Buffer{}
	form := multipart.NewWriter(body)

	// Whisper resamples everything internally, so the wrapper only has to be
	// honest about what it contains.
	part, err := form.CreateFormFile("file", "speech.wav")
	if err != nil {
		return "", err
	}
	if err := writeWAV(part, pcm, rate, channels); err != nil {
		return "", err
	}
	_ = form.WriteField("model", a.Model)
	_ = form.WriteField("response_format", "text")
	if a.Language != "" {
		_ = form.WriteField("language", a.Language)
	}
	if err := form.Close(); err != nil {
		return "", err
	}

	request, err := http.NewRequest("POST", a.URL, body)
	if err != nil {
		return "", err
	}
	request.Header.Set("Content-Type", form.FormDataContentType())
	if a.Key != "" {
		request.Header.Set("Authorization", "Bearer "+a.Key)
	}

	// Long enough for a five-minute recording on a slow endpoint, short enough
	// that a dead one is reported rather than waited on forever.
	client := &http.Client{Timeout: 120 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()

	answer, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if response.StatusCode != http.StatusOK {
		// The endpoint's own words, not a generic failure — this is where a
		// wrong key or a wrong model name actually announces itself. Never the
		// key: it is not in the response and must not be put into a log either.
		return "", fmt.Errorf("%s: %s", response.Status,
			strings.TrimSpace(trim(string(answer), 300)))
	}

	text := strings.TrimSpace(string(answer))
	// response_format=text returns a bare string; some compatible servers ignore
	// that and answer with JSON anyway.
	if strings.HasPrefix(text, "{") {
		var wrapped struct {
			Text  string `json:"text"`
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		if json.Unmarshal([]byte(text), &wrapped) == nil {
			if wrapped.Error.Message != "" {
				return "", fmt.Errorf("%s", wrapped.Error.Message)
			}
			text = strings.TrimSpace(wrapped.Text)
		}
	}
	return text, nil
}

// writeWAV wraps raw signed 16-bit little-endian samples in the smallest header
// that makes them a file. Nothing here needs a library.
func writeWAV(out io.Writer, pcm []byte, rate, channels int) error {
	const bitsPerSample = 16
	byteRate := rate * channels * bitsPerSample / 8
	blockAlign := channels * bitsPerSample / 8

	header := &bytes.Buffer{}
	header.WriteString("RIFF")
	binary.Write(header, binary.LittleEndian, uint32(36+len(pcm)))
	header.WriteString("WAVEfmt ")
	binary.Write(header, binary.LittleEndian, uint32(16))            // subchunk size
	binary.Write(header, binary.LittleEndian, uint16(1))             // PCM
	binary.Write(header, binary.LittleEndian, uint16(channels))      //
	binary.Write(header, binary.LittleEndian, uint32(rate))          //
	binary.Write(header, binary.LittleEndian, uint32(byteRate))      //
	binary.Write(header, binary.LittleEndian, uint16(blockAlign))    //
	binary.Write(header, binary.LittleEndian, uint16(bitsPerSample)) //
	header.WriteString("data")
	binary.Write(header, binary.LittleEndian, uint32(len(pcm)))

	if _, err := out.Write(header.Bytes()); err != nil {
		return err
	}
	_, err := out.Write(pcm)
	return err
}
