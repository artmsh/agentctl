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

echo "2. a config.toml this python cannot read is an error naming the fix, and stays untouched"
# what tomllib before python 3.15 makes of Codex's TOML 1.1; a repeated key
# is rejected by every version, so the check does not depend on which one runs
printf 'model = "m-a"\nmodel = "m-a"\n' > "$CONF"
cp "$CONF" "$SB/before.toml"
for cmd in apply apply!; do
  c=$(code_of run $cmd -f "$SB/a.edn" --all -t codex -y)
  [ "$c" = 1 ] || { cat "$SB/out.txt"; fail "$cmd on an unreadable config.toml exited $c, not 1"; }
  grep -q 'config.toml does not parse' "$SB/out.txt" && grep -q 'AGENTCTL_PYTHON' "$SB/out.txt" \
    || { cat "$SB/out.txt"; fail "$cmd does not name the cause and the fix"; }
  cmp -s "$CONF" "$SB/before.toml" || fail "$cmd changed a config.toml it could not read"
done

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

echo "4. a declared rule joins a hand-written inline table, and leaves it alone when dropped"
cat > "$CONF" <<'TOML'
default_permissions = "local-dev"

[permissions.local-dev]
extends = ":workspace"

[permissions.local-dev.network]
enabled = true
domains = { "127.0.0.1" = "allow", localhost = "allow" }
TOML
cat > "$SB/c.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :sandbox "local-dev"
             :permission-profiles {:local-dev {:network {:domains {"example.test" "allow"}}}}}]}
EDN
c=$(code_of run apply! -f "$SB/c.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! exited $c"; }
toml 'd = t["permissions"]["local-dev"]["network"]["domains"]; assert d == {"127.0.0.1": "allow", "localhost": "allow", "example.test": "allow"}, d' \
  || fail "the hand-written domains were not kept beside the declared one"
c=$(code_of run apply -f "$SB/c.edn" --all -t codex)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "not converged after a state.edn round trip ($c)"; }
cat > "$SB/c.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :sandbox "local-dev"
             :permission-profiles {:local-dev {:extends ":workspace"}}}]}
EDN
c=$(code_of run apply! -f "$SB/c.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! of the prune exited $c"; }
toml 'd = t["permissions"]["local-dev"]["network"]["domains"]; assert d == {"127.0.0.1": "allow", "localhost": "allow"}, d' \
  || fail "the prune did not remove exactly the declared domain"

echo "5. a preset slot is owned whole: what Codex saved there is replaced, and the slot goes on a prune"
cat > "$CONF" <<'TOML'
model = "m-a"

[tui.presets.5]
input = "/status"
label = "saved in codex"
TOML
cat > "$SB/d.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a" :presets {5 {:input "/review"}}}]}
EDN
c=$(code_of run apply! -f "$SB/d.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! exited $c"; }
toml 'assert t["tui"]["presets"]["5"] == {"input": "/review"}, t' || fail "the saved label survived the declared slot"
c=$(code_of run apply -f "$SB/d.edn" --all -t codex)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "not converged after a state.edn round trip ($c)"; }
cat > "$SB/d.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"}]}
EDN
c=$(code_of run apply! -f "$SB/d.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! of the prune exited $c"; }
toml 'assert "presets" not in t.get("tui", {}), t' || fail "the dropped slot was not removed whole"
grep -q 'tui.presets' "$CONF" && fail "a bare [tui.presets.5] header was left behind"

echo "6. a plan that would leave codex unable to start is refused, and config.toml stays as it was"
printf 'model = "m-a"\n' > "$CONF"
cat > "$SB/e.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a" :sandbox "dev"
             :permission-profiles {:dev {:extends ":read-only" :filesystem {"/tmp/x" "write"}}}}]}
EDN
c=$(code_of run apply! -f "$SB/e.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! exited $c"; }
toml 'assert t["default_permissions"] == "dev" and t["permissions"]["dev"]["extends"] == ":read-only", t' \
  || fail "profile and default not written"
cp "$CONF" "$SB/before.toml"
refused() {  # edn-without, expected message
  printf '%s\n' "$1" > "$SB/e2.edn"
  for cmd in apply apply!; do
    c=$(code_of run $cmd -f "$SB/e2.edn" --all -t codex -y)
    [ "$c" = 1 ] || { cat "$SB/out.txt"; fail "$cmd exited $c, not 1, for: $2"; }
    grep -q "$2" "$SB/out.txt" || { cat "$SB/out.txt"; fail "$cmd does not say: $2"; }
    cmp -s "$CONF" "$SB/before.toml" || fail "$cmd changed config.toml for: $2"
  done
  c=$(code_of run validate -f "$SB/e2.edn")
  [ "$c" = 1 ] || { cat "$SB/out.txt"; fail "validate exited $c, not 1, for: $2"; }
  grep -q "$2" "$SB/out.txt" || { cat "$SB/out.txt"; fail "validate does not say: $2"; }
}
# dropping :sandbox would prune the default agentctl wrote; the profile stays
refused '{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"
             :permission-profiles {:dev {:extends ":read-only" :filesystem {"/tmp/x" "write"}}}}]}' \
  'no default_permissions'
# dropping the profile :sandbox names would prune the profile agentctl wrote
refused '{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a" :sandbox "dev"}]}' \
  '"dev" names no profile'
# a name nothing defines: the error lists what could be named
refused '{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a" :sandbox "nope"
             :permission-profiles {:dev {:extends ":read-only" :filesystem {"/tmp/x" "write"}}}}]}' \
  'declared profiles: dev; built-in: :danger-full-access, :read-only, :workspace'
# dropping both leaves nothing that needs a default
printf '%s\n' '{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"}]}' > "$SB/e2.edn"
c=$(code_of run apply! -f "$SB/e2.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "dropping profile and default together exited $c"; }
toml 'assert "permissions" not in t and "default_permissions" not in t, t' || fail "profile or default left behind"

echo "7. declaring a vendor's hook adopts its group instead of adding a second one"
HOOKS="$SB/.codex/hooks.json"
cat > "$HOOKS" <<'JSON'
{"hooks": {"SessionStart": [{"matcher": null, "hooks": [{"type": "command", "command": "vendor-session", "timeout": 10}]}]}}
JSON
cat > "$SB/f.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"}]
 :hooks [{:id :vendor-session :event :SessionStart :command "vendor-session" :timeout 10
          :acli [:codex] :in :all}]}
EDN
c=$(code_of run apply! -f "$SB/f.edn" --all -t codex -y)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! exited $c"; }
python3 -c 'import json,sys; g=json.load(open(sys.argv[1]))["hooks"]["SessionStart"]; assert len(g) == 1, g' "$HOOKS" \
  || fail "the vendor's SessionStart group was duplicated"
c=$(code_of run apply -f "$SB/f.edn" --all -t codex)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "not converged ($c)"; }

echo "8. a hooks.json that does not parse is refused, not replaced"
printf '%s\n' '{"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "vendor-stop"}]},]}}' > "$HOOKS"
cp "$HOOKS" "$SB/before.json"
cat > "$SB/g.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"}]
 :hooks [{:id :note :event :SessionStart :command "note" :acli [:codex] :in :all}]}
EDN
c=$(code_of run apply -f "$SB/g.edn" --all -t codex)
grep -q 'hooks.json is not valid JSON' "$SB/out.txt" || { cat "$SB/out.txt"; fail "the plan does not say hooks.json is broken"; }
c=$(code_of run apply! -f "$SB/g.edn" --all -t codex -y)
[ "$c" = 1 ] || { cat "$SB/out.txt"; fail "apply! on a broken hooks.json exited $c, not 1"; }
cmp -s "$HOOKS" "$SB/before.json" || fail "a hooks.json that does not parse was rewritten"

echo "9. a broken permissions setup refuses runs that write codex settings, and only warns others"
rm -f "$HOOKS"
printf 'model = "m-a"\n' > "$CONF"
cp "$CONF" "$SB/before.toml"
cat > "$SB/h.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-b"
             :permission-profiles {:dev {:extends ":read-only"}}}]
 :mcps [{:id :docs :cmd "/bin/echo docs" :in :all :acli [:codex]}]}
EDN
c=$(code_of run apply -f "$SB/h.edn" --all -k mcps -t codex)
[ "$c" != 1 ] || { cat "$SB/out.txt"; fail "-k mcps -t codex was refused on a permissions finding"; }
grep -q 'no default_permissions' "$SB/out.txt" || { cat "$SB/out.txt"; fail "-k mcps -t codex does not warn about the permissions finding"; }
grep -q 'docs' "$SB/out.txt" || { cat "$SB/out.txt"; fail "-k mcps -t codex does not plan the MCP server"; }
c=$(code_of run apply! -f "$SB/h.edn" --all -t codex -y)
[ "$c" = 1 ] || { cat "$SB/out.txt"; fail "a run that writes codex settings exited $c, not 1"; }
grep -q 'no default_permissions' "$SB/out.txt" || { cat "$SB/out.txt"; fail "the refusal does not name the finding"; }
cmp -s "$CONF" "$SB/before.toml" || fail "config.toml changed on a refused run"

echo "10. a hook whose :event changes moves: the old element goes in the same apply!"
rm -f "$HOOKS"
printf 'model = "m-a"\n' > "$CONF"
for ev in Stop SessionStart; do
  cat > "$SB/i.edn" <<EDN
{:settings [{:id :user-codex :acli [:codex] :in :all :model "m-a"}]
 :hooks [{:id :note :event :$ev :command "note" :acli [:codex] :in :all}]}
EDN
  c=$(code_of run apply! -f "$SB/i.edn" --all -t codex -y)
  [ "$c" = 0 ] || { cat "$SB/out.txt"; fail "apply! ($ev) exited $c"; }
done
c=$(code_of run apply -f "$SB/i.edn" --all -t codex)
[ "$c" = 0 ] || { cat "$SB/out.txt"; fail "a moved codex hook does not converge ($c)"; }
python3 -c 'import json,sys; h=json.load(open(sys.argv[1]))["hooks"]; assert not h.get("Stop"), h; assert len(h["SessionStart"]) == 1, h' "$HOOKS" \
  || { cat "$HOOKS"; fail "a moved codex hook left its old element"; }

echo "all codex e2e checks passed"
