# Publishing to the Meta Horizon Store

What Meta requires, what we do not have yet, and the order to do it in. Every
requirement below carries its source; nothing here is from memory, because
Horizon OS changes fast and this document is worthless if it is trusted while
being stale. Re-check the linked pages before acting on any of it.

## The thing that will get us rejected, before any of the artwork

**A reviewer will install this app and see nothing work.** The client is half a
product: without a Go server running on a Linux machine on the same network,
the connection manager finds no hosts, and there is nothing to look at. A
reviewer with a headset and no Linux box cannot evaluate a single feature.

This is the first problem to solve, not the last. The options, cheapest first:

1. **Reviewer instructions plus a reachable demo host.** A machine we run,
   exposed to the internet for the review window, with the address typed into
   the review notes. Truthful, but it hands strangers a shell — it would have to
   be a throwaway container with nothing in it, and the transport is currently
   unauthenticated and unencrypted, which is its own problem (below).
2. **A demo mode in the app.** A built-in fake session that replays a recorded
   transcript so every screen and every button can be seen without a server. No
   security exposure, and it doubles as the source of honest screenshots.
3. **A video that carries the review**, with the app itself shipping as-is. The
   trailer is a required asset anyway.

Option 2 is the recommendation: it is the only one that makes the app
self-evidently functional to someone who has nothing else, and it costs a day.

## Which track we are on

A **2D app** — native Android, rendered by Horizon OS as a flat panel that
multitasks with other windows. This is deliberate and already true of the
manifest: declaring VR features is what makes the OS take over the headset, and
the whole point of this client is to sit beside a browser.

Meta supports this path explicitly and treats 2D apps as a distinct category
with a **reduced VRC set** — tracking, most input, streaming and several
performance and asset checks do not apply.
([2D apps on the Store](https://developers.meta.com/horizon/blog/building-2d-apps-on-the-meta-horizon-store),
[VRC guidelines](https://developers.meta.com/horizon/resources/publish-quest-req/))

Since July 31, 2024 all Quest apps go through the Meta Horizon Store; there is
no separate App Lab track to hide in.

## What Meta asks for

### Account and app

Already done: developer account, and the app created in the dashboard. The App
ID it generated is only needed in code if we use Platform SDK features, and we
use none.
([required configuration](https://developers.meta.com/horizon/resources/publish-overview-appID/))

### The build

Audited against the manifest as it stands, not assumed:

| Requirement | Where we stand |
|---|---|
| Signed release APK with a stable keystore | **done** — `~/.config/linux-terminal/release.keystore`, same key in CI |
| No prohibited permissions | **clean** — we declare `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, and none of the ~150 prohibited ones |
| No VR features declared | **clean** — zero `uses-feature` entries, which is what keeps us on the 2D track |
| Permissions needing justification | `RECORD_AUDIO`, allowed for "VOIP or other features that need microphone access" — ours is dictation, which is that |
| SDK levels | `compileSdk 34`, `targetSdk 34`, `minSdk 32` |
| **Version metadata** | **stale** — `versionName "0.1"`, `versionCode 1`, while the repository has just released v1.0.0 |

The version is not cosmetic. The store shows `versionName` to users, and every
upload needs a `versionCode` higher than the last one that was accepted — a
number that can only go up, forever, across the app's whole life. Decide now
whether the tag drives it (`v1.0.0` → `versionCode 10000`) or whether it is
bumped by hand, and make the release workflow do it, because doing it by hand is
how a build gets rejected at upload for a number nobody was watching.

Losing the keystore means never being able to update the app. It is already
outside the repository with its passwords beside it, both 0600, and duplicated
in the repository secrets.
([prohibited](https://developers.meta.com/horizon/resources/permissions-prohibited/),
[review-required](https://developers.meta.com/horizon/resources/permissions-review-required/))

### Legal and policy — the part with no shortcuts

**Privacy policy: a public URL, mandatory.** We do not have one. It cannot be
boilerplate, because this app has a real data flow to describe: the microphone
records, the audio goes to the server, and the server forwards it to whatever
transcription endpoint the user configured — which may be OpenAI or may be a
Whisper on their own machine. Both cases have to be stated plainly, including
that we never see the audio ourselves and that the key never leaves the user's
machine.
([privacy policy requirements](https://developers.meta.com/horizon/policy/privacy-policy/))

**Age group self-certification: mandatory for every app**, utilities included.
"Teens and Adults (13+)" is the honest answer here. All apps must prevent access
by users under 10.
([age groups](https://developers.meta.com/horizon/resources/age-groups/))

**IARC age rating: mandatory.** A questionnaire, free, results instant,
completed through a redirect from the dashboard.
([IARC transition](https://developers.meta.com/horizon/blog/oculus-store-transitions-to-iarc-age-and-content-ratings/))

**Data Use Checkup: probably not applicable.** It is required for apps using
Platform SDK features subject to the Developer Data Use Policy, and we use none.
Confirm in the dashboard rather than assuming — the privacy policy is reviewed
as part of a DUC when one is required.
([DUC](https://developers.meta.com/horizon/resources/publish-data-use/))

**Entitlement check: recommended, not required.** VRC.Quest.Security.1 is marked
`+` rather than `✓` for both 2D and immersive. Sources differ in emphasis —
Meta's own material elsewhere calls it a commonly failed check — and the app is
free, so piracy protection buys nothing. Skip it, and revisit only if review
asks.
([VRC guidelines](https://developers.meta.com/horizon/resources/publish-quest-req/),
[entitlement check](https://developers.meta.com/horizon/documentation/android-apps/ps-entitlement-check/))

### Store assets — the bulk of the work

All PNG, 24-bit unless stated.
([asset guidelines](https://developers.meta.com/horizon/resources/asset-guidelines/))

| Asset | Size | Notes |
|---|---|---|
| Store icon | 512 × 512 | squared corners, solid fill, must read when small |
| Cover, square | 1440 × 1440 | branding, no taglines or banners |
| Cover, landscape | 2560 × 1440 | same design as the other covers |
| Cover, portrait | 1008 × 1440 | |
| Hero cover | 3000 × 900 | same design again |
| Screenshots | 2560 × 1440 | **5, no duplicates**, top 20% and bottom 30% clear of text |
| Trailer | 1080p–2K, MP4/H.264/AAC | 30 s to 2 min, representative of real use |
| Trailer cover | 2560 × 1440 | |
| Logo, transparent | up to 9000 × 1440, 32-bit | optional |
| Spatialized icon | 180 × 180, transparent | optional, foreground layer on hover |

Screenshots must show what the user actually experiences, with no logos or
watermarks. For this app that means a real terminal with real output — which is
another argument for building the demo mode first, so the screenshots are
honest and reproducible rather than staged against a private machine.

The `>_` visor mark the console already uses is the obvious basis for the icon
and covers.

### Submission mechanics

Release channels exist for private test builds before anything goes public, and
are the right place to put the first upload. Target launch date should be **at
least two weeks out** — that is Meta's own advice for review headroom. Metadata
must show green checkmarks before the Submit button does anything.
([submitting](https://developers.meta.com/horizon/resources/publish-submit/))

## Risks worth naming before we spend a week

**The transport is unauthenticated and unencrypted.** Anyone on the network who
reaches tcp/9103 gets a shell as the user running the server. That is a
defensible choice for a personal tool on a home LAN and an indefensible one for
something strangers install from a store. Meta's app policies do not appear to
prohibit remote-access tools, but they do ban VPNs and password managers without
prior written approval and security certification, which shows where the line
sits for security-sensitive categories. Expect questions, and consider that
shipping a pairing step is the honest answer regardless of what review asks.
([app policies](https://developers.meta.com/horizon/policy/app-policies/))

**"Limited utility" does not apply to us.** That clause covers apps that are
paid, carry in-app purchases, serve ads, or request Platform SDK features. Ours
is free and does none of those.

**The server is not in the store and cannot be.** Users install it from GitHub.
The store listing has to say so in the first line of the description, or every
review will be one star from someone who installed half a product.

## Order to do this in

1. **Demo mode in the client** — a replayed session, no server needed. Unblocks
   review, screenshots and the trailer at once.
2. **Privacy policy** — written honestly around the dictation data flow, hosted
   somewhere permanent, URL into the dashboard.
3. **Permissions audit** — check the manifest against the prohibited list before
   an upload fails on it, and write the `RECORD_AUDIO` justification.
4. **Assets** — icon and covers from the existing mark, five screenshots from
   demo mode, then the trailer.
5. **Dashboard forms** — age group self-certification, IARC questionnaire,
   pricing (free), metadata, review notes explaining the server.
6. **Upload to a release channel** and install from it on a real headset, as a
   user would, before submitting anything.
7. **Submit**, with a launch date two weeks out.

## What this document does not know

- Whether a trailer is enforced for 2D utility apps or only for immersive ones —
  the asset guidelines do not separate them.
- Actual review turnaround. Meta advises two weeks of headroom; that is guidance,
  not a service level.
- Whether the reviewer-facing notes field allows enough room for setup
  instructions, or whether a demo host is expected instead.

Confirm each in the dashboard at submission time.
