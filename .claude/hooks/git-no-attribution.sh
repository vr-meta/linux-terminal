#!/usr/bin/env bash
# Block a commit or a PR body that carries an attribution trailer.
#
# CLAUDE.md says this in capitals and the harness's own default instruction says
# the opposite, which is exactly the situation a text rule loses: 27 commits in
# this history carry `Co-Authored-By: Claude Opus 5`, all of them from before the
# rule was written down. Since the rule landed (226988f, 2026-08-08) there have
# been none — so this gate is not fixing a present leak, it is removing the way
# the leak happened, because the instruction that produces it is still there and
# arrives fresh in every session.
#
# Measured: 0 findings against the working tree; 27 against the history before
# the rule existed, which is the evidence that it is worth having.
#
# PreToolUse on Bash. Exit 2 blocks and hands stderr back to the model.
set -uo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty')
[ -n "$cmd" ] || exit 0

# Only the commands that write a message somebody will read later.
case "$cmd" in
    *"git commit"*|*"gh pr create"*|*"gh pr edit"*|*"git tag"*|*"gh release"*) ;;
    *) exit 0 ;;
esac

# Anchored to the start of a line, because a trailer IS a line — and the message
# is where it appears, so unlike the other guards this one has to read the
# heredoc rather than cut it off. Naming the rule in prose is legal, which is not
# a nicety: the commit that introduced this hook quotes the trailer while
# explaining why 27 of them are in the log.
#
# `--author` is matched anywhere: it is the same claim made through a flag.
if printf '%s' "$cmd" | grep -qiE '^[[:space:]]*(co-authored-by|signed-off-by):|^[[:space:]]*🤖 generated|^[[:space:]]*generated with \[?claude|--author[= ]'; then
    cat >&2 <<'EOF'
Blocked: this repository takes no attribution trailer, ever.

No Co-Authored-By, no Signed-off-by, no "Generated with", no --author. CLAUDE.md
says this overrides the harness's own default instruction to append one — Claude
is already in the contributors list, and repeating it on every commit is noise in
a log that is public and is read for its reasoning.

Send the same command with the trailer removed.
EOF
    exit 2
fi

exit 0
