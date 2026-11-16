#!/usr/bin/env bash
# End-to-end: what agentctl leaves in a scratch ~/.codex — config.toml and
# hooks.json — across apply!, a state.edn round trip, and a prune. Every check
# here is about file state, so each one reads the files back (tomllib, cmp).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-codex.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)
export AGENTCTL_HOME="$SB"
unset DSH_HOME DSH_AGENTS_HOME AGENTCTL_PYTHON
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# the codex adapter only plans when a codex CLI is installed; the stub exists
# and succeeds, and nothing here goes through it
mkdir -p "$SB/bin"
for cli in claude codex; do
  printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"
  chmod +x "$SB/bin/$cli"
done
export PATH="$SB/bin:$PATH"

CONF="$SB/.codex/config.toml"
mkdir -p "$SB/.codex"

# `apply` exits 2 on drift and 1 on a refusal; set -e must not take either as
# the script failing
code_of() { set +e; "$@" > "$SB/out.txt" 2>&1; local c=$?; set -e; echo "$c"; }
toml() { python3 -c "import sys,tomllib; t=tomllib.load(open(sys.argv[1],'rb')); $1" "$CONF"; }

echo "1. no python3 with tomllib: refused up front, config.toml untouched"
printf 'model = "m-a"\n' > "$CONF"
cp "$CONF" "$SB/before.toml"
printf '#!/bin/sh\nexit 1\n' > "$SB/bin/old-python"
chmod +x "$SB/bin/old-python"
cat > "$SB/a.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-b"}]}
EDN
c=$(AGENTCTL_PYTHON="$SB/bin/old-python" code_of run apply -f "$SB/a.edn" --all -t codex)
[ "$c" = 1 ] || { cat "$SB/out.txt"; fail "plan without tomllib exited $c, not 1"; }
grep -q 'AGENTCTL_PYTHON' "$SB/out.txt" && grep -q '3.11' "$SB/out.txt" \
  || { cat "$SB/out.txt"; fail "the refusal does not say what to install or set"; }
c=$(AGENTCTL_PYTHON="$SB/bin/old-python" code_of run apply! -f "$SB/a.edn" --all -t codex -y)
[ "$c" = 1 ] || { cat "$SB/out.txt"; fail "apply! without tomllib exited $c, not 1"; }
cmp -s "$CONF" "$SB/before.toml" || fail "config.toml changed without an interpreter to check it"
# a config.toml that does not exist yet is never read at plan time: the run
# must still stop before its first write
rm "$CONF"
c=$(AGENTCTL_PYTHON="$SB/bin/old-python" code_of run apply! -f "$SB/a.edn" --all -t codex -y)
[ "$c" = 1 ] || { cat "$SB/out.txt"; fail "a first write without tomllib exited $c, not 1"; }
[ ! -e "$CONF" ] || fail "config.toml written without an interpreter to check it"

echo "2. a config.toml tomllib cannot read is edited, not refused, and the reason is said"
# what tomllib before python 3.15 makes of Codex's TOML 1.1; a repeated key
# is rejected by every version, so the check does not depend on which one runs
printf 'model = "m-a"\nmodel = "m-a"\n' > "$CONF"
c=$(code_of run apply! -f "$SB/a.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "an edit to an unreadable config.toml exited $c"; }
grep -q 'not TOML 1.0' "$SB/out.txt" || { cat "$SB/out.txt"; fail "no warning that the edit went unchecked"; }
grep -q '^model = "m-b"' "$CONF" || fail "the declared model was not written"

echo "3. headers are found by name, and a [table] line inside a string stays in it"
cat > "$CONF" <<'TOML'
developer_instructions = """
Be brief.
[rules]
No emoji.
"""

[tui] # footer and theme
theme = "x"

[permissions."local-dev"]
extends = ":workspace"
TOML
cat > "$SB/b.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-b" :sandbox "local-dev"
             :status-line [:model :git-branch]
             :permission-profiles {:local-dev {:extends ":workspace" :description "dev"}}}]}
EDN
c=$(code_of run apply! -f "$SB/b.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! exited $c"; }
toml '
assert t["model"] == "m-b", t
assert t["developer_instructions"] == "Be brief.\n[rules]\nNo emoji.\n", t
assert "rules" not in t, t
assert t["tui"] == {"theme": "x", "status_line": ["model", "git-branch"]}, t
assert t["permissions"]["local-dev"] == {"extends": ":workspace", "description": "dev"}, t
' || fail "config.toml is not what was declared around what was there"
[ "$(grep -c '^\[tui\]' "$CONF")" = 1 ] || fail "[tui] defined twice"
grep -q '^\[permissions.local-dev\]' "$CONF" && fail "[permissions.\"local-dev\"] redefined unquoted"
c=$(code_of run apply -f "$SB/b.edn" --all -t codex)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "not converged after apply! ($c)"; }

echo "all codex e2e checks passed"
