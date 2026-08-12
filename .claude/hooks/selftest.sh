#!/usr/bin/env bash
# Prove the gates still say yes to healthy commands and no to the two shapes
# that have actually bitten this repository.
#
# It exists because both blocking gates got their first false positive within a
# minute of being wired up: the commit that introduced them names `pkill -f
# linux-terminal` and quotes `Co-Authored-By` while explaining why they are
# blocked, and the first versions read the whole command string and refused it.
# The rule that came out of that is in both scripts now — a heredoc is data, and
# a trailer is a line, not a word — and this is what keeps it true.
#
# The cases are assembled from fragments on purpose. A file containing the
# literal forbidden strings would be caught by the very hooks it tests, which is
# the same trap one level up.
#
# Run: .claude/hooks/selftest.sh          (expects 7 passed, 0 failed)
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 1
command -v jq >/dev/null 2>&1 || { echo "jq missing — cannot self-test"; exit 0; }

tmp=$(mktemp -d) || exit 1
trap 'rm -rf "$tmp"' EXIT

TRAILER="Co-""Authored-By"
KILL="pk""ill"

emit() { # name hook want command
    jq -nc --arg cmd "$4" '{tool_input: {command: $cmd}}' > "$tmp/$1.json"
    printf '%s %s %s\n' "$1" "$2" "$3" >> "$tmp/plan"
}

emit trailer_in_heredoc   git-no-attribution.sh 2 "git commit -F - <<EOF
Subject line

$TRAILER: Claude <x@y>
EOF"
emit author_flag          git-no-attribution.sh 2 'git commit --author="Claude" -m x'
emit prose_about_trailer  git-no-attribution.sh 0 "git commit -F - <<EOF
Gates

27 commits carry \`$TRAILER: Claude Opus 5\`, all from before the rule.
EOF"
emit ordinary_commit      git-no-attribution.sh 0 "git commit -q -F - <<EOF
The commands stay on the panel
EOF"
emit kill_in_message      bash-guard.sh 0 "git commit -F - <<EOF
Gates

  $KILL -f linux-terminal   blocked, with ss -ltnp
EOF"
emit kill_run             bash-guard.sh 2 "$KILL -f linux-terminal-server"
emit kill_after_and       bash-guard.sh 2 "make server && $KILL -f linux-terminal"

pass=0; fail=0
while read -r name hook want; do
    "$(dirname "$0")/$hook" < "$tmp/$name.json" >/dev/null 2>&1
    got=$?
    if [ "$got" = "$want" ]; then
        pass=$((pass + 1))
        printf '  ok   %-22s %-24s exit=%s\n' "$name" "$hook" "$got"
    else
        fail=$((fail + 1))
        printf '  FAIL %-22s %-24s want=%s got=%s\n' "$name" "$hook" "$want" "$got"
    fi
done < "$tmp/plan"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
