---
name: release
description: Cut a release of linux-terminal — pick the version, dry-run the workflow, tag it, write the notes, and verify every artifact actually landed. Use when asked to release, ship, cut a version, tag a build, or publish binaries and the APK.
---

# Releasing linux-terminal

A tag starting with `v` builds both halves and attaches them to a GitHub
release. The work is not "push a tag" — it is **proving that what came out the
other end is installable**. A release whose APK is signed with a debug key, or
whose binary reports the wrong version, is worse than no release: it looks
finished.

Four assets must exist when this is done, and nothing else counts as success:

| Asset | What it is |
|---|---|
| `linux-terminal-server-linux-amd64` | static server, no CGO |
| `linux-terminal-server-linux-arm64` | same, for an ARM box |
| `checksums.txt` | sha256 of both binaries |
| `linux-terminal.apk` | the client, signed with the release key |

## 1. Preconditions, checked not assumed

```sh
git branch --show-current          # main
git status --porcelain             # empty
git log origin/main..HEAD          # empty — the tag must point at pushed code
gh secret list                     # all four ANDROID_* must be there
```

**If a secret is missing the build still succeeds** and quietly falls back to a
debug key. That APK cannot upgrade an install signed by the release key, and
every headset would have to uninstall first. Missing secrets is a stop, not a
warning.

## 2. Dry-run before spending the tag

The workflow is buildable on demand for exactly this reason. A tag that fails
cannot be reused — deleting and re-pushing it confuses anyone who already
fetched it.

```sh
gh workflow run release.yml
gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Wait for it to go green. Only then continue.

## 3. Tag it

Annotated, never lightweight — the message is what `git show` and the release
page carry.

```sh
git tag -a v1.2.3 -m "one line saying what changed, in the repository's voice"
git push origin v1.2.3
```

Version comes from what actually changed: a protocol or wire change that an old
client cannot speak is a major, a new capability is a minor, a fix is a patch.
The client and the server ship from one tag and share its number.

## 4. Watch the run that matters

```sh
run=$(gh run list --workflow=release.yml --event push --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$run"
```

Then read what signed the APK — the workflow says so on purpose:

```sh
gh run view "$run" --log | grep -iE "Signed with|debug key"
```

`Signed with the release key.` is the only acceptable line.

## 5. Verify the artifacts landed — the whole point

`generate_release_notes: true` produces a release even if an upload failed, so
a release page existing proves nothing. Check the assets by name and size:

```sh
gh release view v1.2.3 --json assets \
  --jq '.assets[] | "\(.name)  \(.size) bytes  \(.state)"'
```

All four names, every size non-zero, every state `uploaded`. A 0-byte asset has
happened here before — an interrupted write leaves a file that debugs as
silence.

Then prove the binary is the one you think it is, by running it:

```sh
tmp=$(mktemp -d)
gh release download v1.2.3 -p "linux-terminal-server-linux-amd64" -D "$tmp"
chmod +x "$tmp/linux-terminal-server-linux-amd64"
"$tmp/linux-terminal-server-linux-amd64" --version     # must print the tag without the v
```

The version is baked in with `-ldflags -X main.version=`, so a mismatch means
the release was built from something other than the tag.

And the checksums must describe the files that are actually published:

```sh
gh release download v1.2.3 -D "$tmp" --clobber
(cd "$tmp" && sha256sum -c checksums.txt)
```

## 6. Say what it is

`generate_release_notes: true` lists the merged commits, which is a changelog,
not a description. Add the two or three sentences a person needs: what is now
possible that was not, and anything that requires action on their side — a
config file that moved, a flag that changed default, a reinstall.

```sh
gh release edit v1.2.3 --notes-file notes.md      # keep the generated list below your prose
```

## Traps that have already cost time here

**The signing key lives outside the repository**, at
`~/.config/linux-terminal/release.keystore` with its passwords in `signing.env`
beside it, both 0600. The same key is in the repository secrets so CI signs
identically. Losing it means every headset uninstalls before the next release
installs.

**A tag is not a branch.** `gh workflow run` runs on a branch and never
produces a release; only `refs/tags/v*` reaches the release job. If the run you
are watching says `workflow_dispatch`, you are watching the dry run.

**Do not delete a pushed tag to "redo" a release.** Cut the next patch version
instead. Anyone who fetched the old one keeps a tag that no longer matches what
is published.
