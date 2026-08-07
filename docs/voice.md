# Dictation

Press the microphone, speak, and the text lands at the cursor in the terminal
without being submitted. A misheard word is fixed on the line it landed on;
pressing Enter stays a decision.

## Where the work happens

The headset records. The **server** transcribes and hands the text back over the
connection that already exists. Two reasons it is that way round:

- **The key.** A key shipped to a headset is a key on a device you carry into
  other people's houses, and there is no good way to protect it there. On the
  server it sits in a file only your account can read, and the client never
  learns it.
- **One address.** The client is already connected to this machine. A second
  endpoint would mean a second port, a second firewall rule and a second thing
  to get wrong. Audio rides the session.

An earlier version did the opposite — a separate agent on port 9102 that the
headset talked to directly — and every one of those costs showed up.

## Setting it up

Open the web console on the server machine:

```
http://localhost:9104
```

It is on by default in the systemd unit and bound to localhost. From another
machine, tunnel it rather than widening the bind address:

```sh
ssh -L 9104:localhost:9104 you@your-machine
```

Two ways to fill the form:

| | |
|---|---|
| **OpenAI** | paste the key, leave the endpoint blank — it fills itself in |
| **Your own Whisper** | put its URL in the endpoint; the key may be blank |

Anything OpenAI-compatible works: the endpoint is
`POST /v1/audio/transcriptions` with a `file` and a `model` field. Self-hosted
`faster-whisper`, `whisper.cpp`'s server, LiteLLM and Speaches all answer it.

The **language hint** is worth filling in. Whisper detects the language on its
own, and gets it wrong often enough on short utterances that a two-letter code
noticeably improves the result.

## Without the console

Flags:

```sh
linux-terminal-server --asr-url https://your-whisper/v1/audio/transcriptions \
                      --asr-model whisper-large-v3-turbo --asr-language ru
```

The key comes from the environment or from the settings file, never from the
command line — an argument is visible in `ps` to every user on the machine:

```sh
LINUX_TERMINAL_ASR_KEY=sk-... linux-terminal-server
```

Settings file, `~/.config/linux-terminal/config.json`, mode 0600:

```json
{
  "asr": {
    "url": "https://api.openai.com/v1/audio/transcriptions",
    "model": "whisper-1",
    "key": "sk-...",
    "language": "ru"
  }
}
```

The environment wins over the file, so a key can be handed to a systemd unit
without being written down anywhere.

## In the headset

The microphone is on the fixed half of the bar, always in the same place.
Pressing it hands the whole bar over to dictation: the level meter across the
width, a timer, and one stop button in the middle. Everything else disappears —
while you are talking there is exactly one decision to make, and a row of live
buttons around it is a hazard rather than a convenience.

Recording stops at five minutes.

## When it does not work

The server says whether dictation is configured at all, on startup and in its
discovery announcement:

```
dictation is off — no endpoint configured, see docs/voice.md
```

Otherwise, the failure is reported with the endpoint's own words — a wrong key
or a wrong model name announces itself there. Never the key: it is not in the
response, and it is not put into a log either.

A **moving meter** and no text means audio reached the endpoint and came back
empty. A **flat meter** means the microphone never delivered samples — check the
runtime permission.
