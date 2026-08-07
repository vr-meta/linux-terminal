# Who is allowed to open a shell

Today: anybody who can reach tcp/9103. The bytes are in the clear, there is no
password, and the first thing the server does with a new connection is start a
shell as the user running it. On a home LAN that is a defensible trade for a
tool one person wrote for themselves. For something strangers install from a
store it is not, and `docs/publishing.md` already lists it as the question
review is most likely to ask.

This is the design for fixing it, and the reasoning behind each choice, because
the wrong instinct here is cheap to have and expensive to ship.

## The problem is two problems

**Authentication** — the server must know the client is allowed in.
**Confidentiality** — nobody on the network may read what goes past.

They have to be solved together. A password sent over a plain socket is read by
anyone listening, so authentication without a channel is theatre: it changes who
*feels* safe, not who *is*.

## What was ruled out, and why

**TLS-PSK.** The obvious fit — both sides know a secret, no certificates to
manage. Go's standard library does not implement external pre-shared keys; its
PSK support covers session resumption only
([crypto/tls](https://pkg.go.dev/crypto/tls),
[golang/go#6379](https://github.com/golang/go/issues/6379)). The alternative is
a third-party TLS stack, and replacing the TLS implementation to avoid managing
one self-signed certificate is a bad trade.

**A PAKE (SPAKE2, CPace, OPAQUE) on its own.** The right primitive for a short
human password: it lets both sides prove they know it without either revealing
it, and it denies an eavesdropper the offline guessing attack that a hashed
password over the wire hands them. But it is not a substitute for the channel.
The IETF draft on carrying PAKEs in TLS states the trap plainly: the exchange
must be bound to whatever protected channel ends up being used, or an attacker
waits for it to finish and takes the channel
([draft-barnes-tls-pake](https://datatracker.ietf.org/doc/html/draft-barnes-tls-pake-02)).
So a PAKE is something to put *inside* TLS later, not instead of it.

**Rolling our own.** Not considered. This is the one area where inventing is
worse than not acting.

## The design

**TLS 1.3, with a certificate the server makes for itself.** Generated on first
run, stored beside the transcription key at `~/.config/linux-terminal/`, mode
0600, and never leaving the machine. The Go standard library does all of it —
`crypto/tls` and `crypto/x509`, no cgo, so the single static binary survives.

**The client pins that certificate.** A self-signed certificate proves nothing
on its own; what makes it an identity is that the client remembers it. On first
pairing the client stores the certificate's fingerprint against that server and
refuses anything else from then on. A machine that suddenly presents a different
certificate is not that machine, and the client says so rather than connecting.

**A token proves the client is allowed.** Generated with the certificate,
displayed in the web console, typed into the headset once. Compared in constant
time; a wrong one is answered slowly and the same way every time, so the reply
carries no information about how wrong it was.

**Pairing is where trust is established.** The console shows the token and the
fingerprint; the headset shows the fingerprint it received. If they match, that
is a machine you are looking at rather than one that answered first.

## What discovery says, and to whom

The discovery answer currently includes the hostname, the login name, the
operating system and the working directory — to anyone who broadcasts a probe.
That is a description of a machine and its user handed out unauthenticated, and
it should shrink to what a headset needs in order to offer a card: a name, a
port, and whether it wants a token. The rest arrives after the client has proved
who it is.

## Order of work

1. Certificate and token generated on first run; both in the config file at 0600.
2. Server listens with TLS; the token is checked before a pty is allocated.
3. Console shows the token and the fingerprint, and can rotate the token.
4. Client: pin on pairing, refuse a changed certificate loudly, store per server.
5. Discovery trimmed to what an unauthenticated stranger may know.
6. A grace mode — accept plain connections while a version of the client that
   cannot speak TLS is still installed — off by default, and loud in the log.

Step 6 exists because the alternative is a client in a headset that stops
working with no way to say why. It is a migration, not a setting, and it should
be removed once both halves are past this version.

## What this does not solve

A token in the headset is a token on a device that other people can pick up.
Nothing here defends against someone wearing your headset. That is the same
threat model as an unlocked laptop, and the answer is the same one.
