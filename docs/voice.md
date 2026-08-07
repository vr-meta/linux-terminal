# Dictation

Speak, and the text lands at the cursor in the terminal without being submitted.

That last part is the design. A misheard word is fixed on the line it landed on,
which takes a second; a review step in between turns two seconds of dictation
into four. Pressing Enter stays a decision.

## Why it is simple here and was not before

Over a streamed desktop the transcript had to be pushed into somebody else's
window. On GNOME Wayland that means the clipboard plus `Ctrl+V`, because
`uinput` speaks scancodes tied to the keyboard layout and mutter does not
implement `zwp_virtual_keyboard`. Anything non-ASCII came out as garbage or
depended on which layout happened to be active.

Here the client owns the pty and writes the characters into it. The whole class
of layout problems does not get solved — it stops existing.

## What it needs

A recognition endpoint, which is **not** part of this server. The client sends
raw 16 kHz mono PCM to port 9102 and expects a line of text back:

```
"asr <rate> <channels>\n" + raw signed 16-bit samples   ->   the transcript
```

The reference implementation is `host/voice-agent.py` in
[linux-vr](https://github.com/butschster/linux-vr), which forwards to a
self-hosted Whisper behind an OpenAI-compatible gateway. Anything that answers
that protocol will do.

The gateway key comes from the environment only. It never enters a config file
or this repository.

## In the headset

The microphone button is on the fixed half of the bar, so it is always in the
same place. Pressing it hands the whole bar over to dictation: the level meter
across the width, a timer, and one stop button in the middle. Everything else
disappears, because while you are talking there is exactly one decision to make,
and a row of live buttons around it is a hazard rather than a convenience.

Recording stops at five minutes.

A moving meter with "nothing recognised" means audio reached the endpoint and
came back empty — look at the endpoint. A flat meter means the microphone never
delivered samples — look at the runtime permission.
