---
name: pairing
description: Let a headset into a server, take one back out, or work out why a connection is refused — the six-digit code, the certificate fingerprint, tokens and revocation. Use when pairing fails, a headset stops connecting, a machine must be unpaired, or somebody asks what protects this server.
---

# Letting a headset in

A machine on the network can be seen by anyone on it. It can only be used by a
headset that was deliberately let in, and this is how that happens and how it
goes wrong.

## What is actually enforced

Three things, each closing a different hole. Knowing which one is failing is
most of diagnosing it.

| | |
|---|---|
| **TLS 1.3, always** | a plain socket is refused outright, not quietly accepted |
| **The headset pins the server's certificate** | anything else is a different machine and is refused |
| **The server checks a 160-bit token** | traded for six digits, once, and compared in constant time |

The short code is safe only because it is fragile on purpose: five minutes,
five wrong answers, spent by the first success, and not existing at all until
somebody at the machine opened a window. Anyone proposing to relax one of those
should be shown this line.

## Pairing

**On a machine with a screen** — the console, `http://localhost:9104`, section
**Headsets**, button *Pair a headset*.

**On a machine without one** — over ssh:

```sh
linux-terminal-server --pair            # console on the usual 9104
linux-terminal-server --pair --http 9500  # or wherever it is
```

Either way six digits appear with a countdown and a fingerprint. In the headset
the card offers **pair** instead of **connect**; type the digits and compare the
fingerprint shown there with the one printed. They must match.

`--pair` asks the running server to open the window rather than doing it
itself — the code has to live in the process that will check it, and a second
process minting codes would be a second source of truth. So the console must be
on for `--pair` to work; if it is not, the command says which flag turns it on.

## When it does not work

**The card shows `pair` when you expected `connect`.** Nothing is wrong: that
machine has no token yet on this headset. It is also what you see after
`unpair`, and after the server's token was revoked.

**The code is refused.** All the ways it can be refused look identical from the
outside, deliberately — a reply that distinguished them would help somebody
guessing. Check, in this order: is the window still open (the console shows a
countdown), has it been used already (it is single use), and have five wrong
answers already killed it. Open a new one; it costs nothing.

**"this is not the machine that was paired".** The certificate changed. Either
the server's config was wiped and it made a new identity, or something is
answering on that address that is not your machine. The headset refuses either
way and prints both fingerprints. If you know why it changed, `unpair` the card
and pair again — and if you do not know, find out before pairing.

**Connections stopped working after a server restart.** They should not: the
identity lives in `~/.config/linux-terminal/config.json`, mode 0600, and
survives restarts. If it did not, that file was replaced or deleted.

```sh
ls -la ~/.config/linux-terminal/config.json     # expect -rw------- and a size
journalctl --user -u linux-terminal-server | grep fingerprint
```

The fingerprint is printed at every startup. If it changed between two starts,
the identity was lost, and every headset must pair again.

**A wrong token in the log.** `rejected <address>: wrong token` is a headset
whose token no longer matches — usually one that was paired before a revoke.
Pair it again. A `refused an unencrypted connection` line is an old client, or
something that is not our client at all.

## Taking access away

**One headset, from the headset:** `unpair` on the machine's card. Removes the
address, the token and the pinned fingerprint together.

**Every headset, from the server:** revoke the token in the console. There is no
way to revoke one and not the others — a single token is shared by every paired
headset, and pretending otherwise would be a worse answer than saying so plainly.

The certificate is deliberately left alone when a token is revoked. Rotating it
would make every headset refuse the machine as an impostor, which is the right
response to a changed identity and the wrong outcome for a token you meant to
replace.

## Reading the wire

Discovery answers a broadcast from anyone, so it says almost nothing: protocol,
name, port, version. Who is logged in, which distribution and where shells start
travel over the session instead, after the headset has proved who it is. If you
are adding a field to that answer, ask first whether a stranger may have it.

```sh
# what the whole network can learn without pairing
python3 - <<'EOF'
import socket, json
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1); s.settimeout(1.5)
s.sendto(b'LINUXVR-DISCOVER 1', ('255.255.255.255', 9103))
try:
    while True:
        data, addr = s.recvfrom(2048); print(addr[0], json.loads(data))
except socket.timeout:
    pass
EOF
```

## What none of this protects against

Somebody wearing your headset. The token is on the device; a device in someone
else's hands opens a shell. Same threat model as an unlocked laptop, same answer.
