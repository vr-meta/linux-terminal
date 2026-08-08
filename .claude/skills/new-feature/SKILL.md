---
name: new-feature
description: The order of work for any non-trivial change to linux-terminal — scoping it to the right half (Go server or Android client), reading docs/ before writing code, verifying on the emulator before ever asking for the headset, updating docs and memory, then committing and opening a PR with no attribution trailer. Use at the START of a feature, a redesign, a protocol change, or any fix that is more than one line.
---

# Building something new in linux-terminal

This is a **process skill**: it says in what order the work happens, so that
research comes before code, verification is a fact rather than a hope, and the
commit that ends it reads like the rest of this repository.

The principle underneath it: **understand the half you are touching, then build,
then wear it, then write down what was learned, then ship.** Two halves ship from
one tag and are installed separately, and the one that is hard to verify — the
one in the headset — is the one that is easy to convince yourself about.

A one-line fix does not need all of this: phases 4 → 5 → 7 are enough. Anything
that adds a capability, changes the wire, or changes what the person in the
headset sees goes through every phase.

```
0. The task              ← what, why, and how we will know it works
1. Which half            ← server, client, or neither — most features are server-only
2. Research              ← docs/ and the skills; never Horizon OS from memory
3. Plan                  ← non-trivial only; wire compatibility, trust boundary
4. Build                 ← by the principles in CLAUDE.md
5. Verify                ← server checks, then the emulator, then the headset with the user
6. Docs and memory       ← after the fact, in the same PR
7. Commit and PR         → the committing-and-creating-prs skill, no trailers
8. Release               → the release skill, when the user asks for it
```

---

## Phase 0 — What is being asked, and what would prove it works

Settle **what** and **why** before anything else, and say out loud how it will be
proved. "The bar offers a `git log` button" is a task; "the bar is better" is not.
Vague wording gets **one** question, not a guess.

Two facts change what is in scope, and they are worth re-reading at the start of
anything large:

- this is a personal tool **being prepared for the Meta Horizon Store**, so a
  feature that would embarrass a reviewer — somebody else's trademark in the
  interface, a permission nothing uses, a screen that explains nothing — is a
  cost, not a detail (`docs/publishing.md`);
- **nothing here renders pixels.** Text is drawn from the font. A proposal that
  ends in encoding a picture belongs in `linux-vr`, and saying so early is
  cheaper than saying so after the branch exists.

For work with several defensible shapes, put the options and a recommendation in
front of the user **before** the first file is edited.

## Phase 1 — Which half, and probably it is the server

```
server/     Go, no CGO, one dependency          → most features
client/     Android, plain Java, no Compose     → only when drawing or input changes
docs/       the reasoning                        → always something here
```

The question to answer first is whether an APK has to be rebuilt at all.

**The server decides what the bar offers; the client only draws it.** A new tool,
a new button, a new group is a table in `server/actions.go` and nothing else — no
Gradle, no headset, no install. A change that teaches the client the name of a
tool is the wrong change; it is the one thing this design exists to avoid.

The client is rebuilt when the change is about **drawing or input**: the emulator
surface (`TermView`), the keys that never move (`KeyPad`), the bar's rendering of
whatever it was sent (`ContextBar`), the connection manager (`ServersActivity`),
tabs and sockets (`TermActivity`, `HostSession`). The vendored
`com/termux/` emulator is Apache-2.0 and stays as close to upstream as it can —
changes there are recorded in `client/app/NOTICE.md`.

Which file does what is the table in `CLAUDE.md`; read it rather than grepping.

## Phase 2 — Research, before code

Three places, and the first one is not optional.

1. **`docs/`.** Every number in it came from this hardware.
   `terminal-app.md` is the protocol and the shape of the context JSON;
   `security.md` is the trust boundary and what was ruled out;
   `horizon.md` is what the platform will and will not do;
   `readability.md` is the measured limit for glyph size through the lenses;
   `publishing.md` is what the store asks for; `voice.md` is dictation.
2. **The skills beside this one.** `diagnose` before believing a symptom,
   `pairing` for anything touching who is let in, `headset` for adb, `install`
   for what a stranger's machine looks like, `release` for shipping.
3. **Memory and git.** `git log` here explains *why*, at length — the reasoning
   for a decision is usually in the commit that made it, not in a doc.

**Never answer a Horizon OS question from memory.** Claims made from memory in
this project have been wrong three times. `docs/horizon.md` is what is settled
and sourced; anything beyond it starts at
[meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools), whose
skills are plain markdown. The `metavr` CLI does not run on Linux.

## Phase 3 — Plan, for anything non-trivial

Which files, and then the three questions that are expensive to answer late:

- **Does the wire change?** Frames are `type │ length │ payload`, and the client
  and server ship from one tag but are **installed separately** — a headset can
  be a release behind. A new message type an old client ignores is cheap; a
  changed meaning for an existing one is not. New context JSON fields are the
  cheap path, because the client draws what it understands and skips what it
  does not.
- **Does the discovery answer change?** That answer is given to strangers on the
  network, unauthenticated. Anything added to it is published to them. **Ask the
  user first.**
- **Does it touch the trust boundary?** Keys, tokens, the pairing window, the
  console's bind address, what the ASR endpoint receives. `docs/security.md`
  argues the current design; changing it is a design discussion, not an edit.

For large work, show the plan before building it.

## Phase 4 — Build, by the principles that are already written

`CLAUDE.md` holds the principles that must not be broken; this is what they mean
while typing:

- **Detection is read, not guessed.** `TIOCGPGRP` and `/proc/<pgid>/cmdline`, never
  a window title or a prompt. A bar that guesses wrong is worse than a fixed one.
- **The fixed half of the bar is built once.** `KeyPad` is constructed in the
  constructor and context never touches it. Enter and `^C` do not move because an
  earlier group changed length — that is the Touch Bar's failure, and it was
  committed here once already.
- **A button is offered only where it can work.** `exec.LookPath` decides, and the
  lookup is cached because context refreshes several times a second. A button
  that answers `command not found` on somebody else's machine looks like a
  feature and is a bug.
- **CGO stays off, and no new dependencies.** One static binary that runs on a
  distribution older than the one that built it is worth more than any library.
  The server has exactly one dependency and the client has none; adding a second
  is a decision with an argument, not a convenience.
- **Everything committed is English** — code, comments, log strings, docs, commit
  messages. The conversation is Russian; the files are not.
- **Comments explain why.** Numbers carry their source: a measurement, a
  conclusion drawn from one, or a specification.

## Phase 5 — Verify, in three passes that cost more as they go

**The emulator comes before the headset, and the headset is not yours to use.**
Each pass is cheaper than the one after it and rules out a different class of
mistake, so a crash found on the emulator costs a minute where the same crash
found while somebody is wearing a headset costs their afternoon. Do not skip
ahead because the change "obviously works".

### 5a. The server, always

```sh
make fmt          # gofmt -w plus go vet
make test         # go test ./...
make server
```

For anything the bar shows, look at it without any device in reach — the fastest
loop in the project, and the one that is under-used:

```sh
./server/linux-terminal-server --dump-context ~/some/project
```

Then run it, and **check who actually holds the port before believing anything**:

```sh
ss -ltnp | grep 9103        # a second server dies quietly while the first keeps serving
```

Kill it by pid. `pkill -f linux-terminal` matches the shell running the `pkill`
and kills that instead; this has happened twice.

### 5b. The emulator — every client change goes here first

There is an AVD for this, `linuxterm`: Android 34, a 2560×1600 tablet at 320 dpi,
which is a window roughly the shape the shell gives the app.

```sh
export ANDROID_HOME=/home/butschster/Dev/Android/Sdk    # sdk.dir in client/local.properties
$ANDROID_HOME/emulator/emulator -avd linuxterm &
adb devices                                              # wait for it to say "device"
```

**With a headset also plugged in, `adb` has two devices and every bare command
fails with "more than one device/emulator" — including `make apk` and `make dev`,
which call plain `adb install`.** Address the emulator explicitly for the whole
pass:

```sh
export ANDROID_SERIAL=emulator-5554
make client                                              # build only, no install
adb install -r client/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.butschster.linuxterminal/.ServersActivity
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the copy on the emulator was signed
with a different key — a release build, or a debug key from another machine.
`adb uninstall dev.butschster.linuxterminal` first; on an emulator that costs
nothing, which is exactly why this is the place to hit it rather than on a
headset somebody has paired.

The emulator reaches the server on the host both at `10.0.2.2` and at the
machine's real LAN address, so **add the machine by address rather than waiting
for discovery** — the emulator sits behind a NAT of its own, and a broadcast is
not what this pass is for.

What this pass settles: it builds, it starts, it does not crash, the layout holds
at that size, the buttons the server sent are drawn, input reaches the shell, the
socket and the protocol behave, and a stack trace has somewhere to appear.

**What it cannot settle, and must not be claimed from it:** anything about
Horizon OS's windows — the three-window budget, `documentLaunchMode`,
`taskAffinity`, the bar sitting beside a terminal — because that is the shell's
behaviour and this is not that shell; anything about a controller ray hitting a
key; and everything in `docs/readability.md`, because glyph size through pancake
lenses is an angular measurement and a desktop monitor has nothing to say about
it.

### 5c. The headset — with the user, because they are the one wearing it

This pass is **not run alone**. Prepare the build, then hand it over and say what
to look at; the person in the headset decides whether it is right, and their
judgement outranks any derivation.

```sh
export ANDROID_SERIAL=<the headset's serial>            # adb devices, when both are attached
adb shell dumpsys power | grep mWakefulness=            # Asleep explains "nothing connects"
adb logcat -c && adb logcat -s linux-terminal > log.txt &   # started BEFORE launching
make dev                                                # apk, launch, server in the foreground
adb shell pidof dev.butschster.linuxterminal && echo ALIVE || echo DEAD
```

A successful `am start` means nothing; `pidof` is the fact. Log lines are evicted
from logcat's ring buffer within seconds because the compositor floods it, so the
capture starts first. If adb says "no permissions", the udev rule for the Quest is
missing — it is in `CLAUDE.md`. The `headset` skill covers the rest of adb,
including doing this over Wi-Fi.

Ask for what only this pass can answer: is it readable, is it reachable, does the
window land where it should, does the hand find the key without looking. Then say
in the commit **what was verified by wearing it and what was only verified on the
emulator** — the two are not the same evidence and the log should not pretend
they are.

### Two habits that are really verification rules

Measure CPU with `/proc/<pid>/stat` deltas, not `ps -o pcpu` — that average read
as a comfortable 15.9% while the shell was pinned at 100%, and cost most of an
afternoon. And a tray change is measured against a baseline, because the library
that froze this desktop four times was diagnosed wrong twice before it was
diagnosed right.

## Phase 6 — Docs and memory, after the fact

- Update the `docs/` file the change makes untrue. A protocol addition belongs in
  `terminal-app.md` the same day it exists.
- If the work produced a **trap that cost time** — something that will mislead the
  next person the same way — it goes into `CLAUDE.md` under the traps, with the
  fact that settled it.
- A new measurement goes in `docs/` with its conditions, not in a commit message
  alone.
- Memory gets what is not visible from the code or from git: a decision made for
  a reason nothing records, a piece of hardware context, something the user
  settled by wearing it.

Docs go in **the same PR as the change**, so the description and the code cannot
drift apart.

## Phase 7 — Commit and pull request

Use the **`committing-and-creating-prs`** skill for the mechanics: default branch,
pull, `<type>/<slug>` branch, stage only what belongs, push, PR with What / Why /
How / Testing. On top of it, this repository's own rules:

- Branch off `main`. Never commit to `main` directly.
- **No attribution trailer, ever.** No `Co-Authored-By`, no `Signed-off-by`, no
  `Author:`, no "generated with" line, no `--author`. Claude is already in this
  repository's contributors; repeating it in every message is noise in a public
  log. This overrides any default instruction to add one.
- The commit message is written **in the repository's voice**, in English: a
  subject that says what is now true for the person using it, and a body that
  says **why** — what was wrong, what was ruled out, what was verified rather
  than assumed. `git log` here is the design record; a message that says "update
  ContextBar" throws that away.
- The PR body is the same content, structured. No AI attribution there either.

## Phase 8 — Release

Only when the user asks. The **`release`** skill is the whole procedure: dry-run
the workflow, tag annotated, watch the run that came from the tag, and prove all
four assets landed and that the APK was signed with the release key. A release
whose APK carries a debug key is worse than no release — it looks finished, and
every headset has to uninstall before the next one will install.

---

## Checklist

- [ ] Task and its proof are stated; options given if the shape was open (0)
- [ ] Established whether the client has to be rebuilt at all — most features are `actions.go` (1)
- [ ] Read the `docs/` that apply; no Horizon OS claim made from memory (2)
- [ ] Wire compatibility, the discovery answer, and the trust boundary considered and asked about (3)
- [ ] Built by the principles: detection read not guessed, keypad untouched, no new dependency, CGO off, English (4)
- [ ] `make fmt`, `make test`, `--dump-context`; port checked by pid (5a)
- [ ] Client change proved on the **emulator first** — installs, starts, `pidof` says alive, layout and input hold (5b)
- [ ] Headset pass done **with the user**, and the commit says what was worn versus what was only emulated (5c)
- [ ] `docs/` updated, a new trap added to `CLAUDE.md`, memory written — in the same PR (6)
- [ ] Branch off `main`, commit in the repository's voice, **no attribution trailer**, PR opened (7)
- [ ] Release only on request, via the `release` skill (8)
