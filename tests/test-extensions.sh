#!/usr/bin/env bash
# End-to-end: an extension declared in agents.edn is cloned, built, linked into
# pi and has its tools switched on; it follows its remote, and is taken back
# out without touching what it did not put there.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-ext.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB"
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
plan() { set +e; run apply -f "$SB/agents.edn" --all "$@" > "$SB/plan.txt" 2>&1; code=$?; set -e; }
apply() { set +e; run apply! -f "$SB/agents.edn" --all -y "$@" > "$SB/apply.txt" 2>&1; code=$?; set -e; }

mkdir -p "$SB/bin"
printf '#!/bin/sh\nexit 0\n' > "$SB/bin/pi"
chmod +x "$SB/bin/pi"
export PATH="$SB/bin:$PATH"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
G() { git -C "$SB/upstream" -c user.name=t -c user.email=t@example.com "$@"; }

# the remote: sources plus a build that turns them into the artifact pi loads
mkdir -p "$SB/upstream"
git init -q -b main "$SB/upstream"
printf 'export default 1\n' > "$SB/upstream/index.ts"
printf 'built\n' > "$SB/upstream/.gitignore"
cat > "$SB/upstream/build.sh" <<'SH'
#!/bin/sh
set -e
[ -f fail-build ] && { echo "boom: compiler exploded" >&2; exit 3; }
pwd > built
SH
chmod +x "$SB/upstream/build.sh"
G add -A && G commit -qm one

EXT="$SB/.agents/extensions/demo-ext"
LINK="$SB/.pi/agent/extensions/demo-ext"
SETTINGS="$SB/.pi/agent/settings.json"
STAMP="$SB/.agents/extensions/.built/demo-ext"

config() { # config <extension-map-body>
  cat > "$SB/agents.edn" <<EDN
{:extensions {$1}}
EDN
}
DECL="[:uri \"file://$SB/upstream\"] {:type :git :alias demo-ext :acli [:pi] :build \"./build.sh\" :tools [\"+demo_tool\"]}"
config "$DECL"

# someone else's entries in the array agentctl appends to, and a hand-made
# link where the extension goes, pointing at a working copy that must survive
mkdir -p "$SB/.pi/agent/extensions" "$SB/dev-copy"
printf 'precious\n' > "$SB/dev-copy/work.txt"
ln -s "$SB/dev-copy" "$LINK"
printf '{"theme":"dark","defaultTools":["+foreign_tool"]}\n' > "$SETTINGS"

echo "1. dry apply plans clone, build, link and defaultTools; writes nothing"
plan
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "expected drift (exit 2), got $code"; }
grep -q 'clone' "$SB/plan.txt" || fail "clone missing from plan"
grep -q 'build' "$SB/plan.txt" || fail "build missing from plan"
grep -q 'extensions/demo-ext' "$SB/plan.txt" || fail "link missing from plan"
grep -q '+demo_tool' "$SB/plan.txt" || fail "defaultTools entry missing from plan"
[ -e "$EXT" ] && fail "dry apply must not clone"
[ "$(readlink "$LINK")" = "$SB/dev-copy" ] || fail "dry apply must not relink"

echo "2. apply! clones, builds, links, and merges defaultTools"
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code"; }
[ -d "$EXT/.git" ] || fail "extension not cloned"
[ "$(cat "$EXT/built")" = "$EXT" ] || fail "build did not run inside the checkout"
[ "$(readlink "$LINK")" = "$EXT" ] || fail "extension not linked into pi"
[ -f "$LINK/index.ts" ] || fail "link does not resolve to the extension"
[ "$(cat "$SB/dev-copy/work.txt")" = precious ] || fail "replacing a hand-made link destroyed what it pointed at"
python3 - "$SETTINGS" <<'PY' || fail "defaultTools not merged alongside foreign entries"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["defaultTools"] == ["+foreign_tool", "+demo_tool"], d
assert d["theme"] == "dark", d
PY

echo "3. second apply is a no-op"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "expected converged state, got exit $code"; }

echo "4. a new upstream commit plans update and rebuild"
printf 'export default 2\n' > "$SB/upstream/index.ts"
G commit -qam two
plan
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "expected drift after upstream commit"; }
grep -q 'fetch' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "update missing from plan"; }
grep -q 'build' "$SB/plan.txt" || fail "rebuild missing from plan"
rm "$EXT/built"
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code"; }
grep -q 'default 2' "$EXT/index.ts" || fail "checkout not updated"
[ -f "$EXT/built" ] || fail "extension not rebuilt after update"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after update"; }

echo "5. a failing build is reported, leaves no stamp, and is retried"
touch "$SB/upstream/fail-build"
G add -A && G commit -qm three
apply
[ "$code" != 0 ] || { cat "$SB/apply.txt"; fail "a failed build must fail apply!"; }
grep -q 'compiler exploded' "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "build error not shown"; }
[ -e "$STAMP" ] && fail "failed build left a stamp"
plan
grep -q 'build' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "failed build not planned again"; }
G rm -q fail-build && G commit -qm four
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code after the build was fixed"; }
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after the build was fixed"; }

echo "6. a tool dropped from the declaration is taken out of defaultTools"
config "${DECL/\"+demo_tool\"/\"+other_tool\"}"
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code"; }
python3 - "$SETTINGS" <<'PY' || fail "defaultTools not swapped"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["defaultTools"] == ["+foreign_tool", "+other_tool"], d
PY

echo "7. a first install whose build fails links nothing and owns nothing"
git init -q -b main "$SB/broken"
printf '#!/bin/sh\nexit 1\n' > "$SB/broken/build.sh"
chmod +x "$SB/broken/build.sh"
git -C "$SB/broken" add -A
git -C "$SB/broken" -c user.name=t -c user.email=t@example.com commit -qm one
config "${DECL/\"+demo_tool\"/\"+other_tool\"}
              [:uri \"file://$SB/broken\"] {:type :git :build \"./build.sh\" :tools [\"+broken_tool\"]}"
apply
[ "$code" != 0 ] || fail "a failed first build must fail apply!"
[ -e "$SB/.pi/agent/extensions/broken" ] && fail "unbuilt extension was linked"
run state | grep -q 'broken' && fail "unbuilt extension recorded as owned"
grep -q 'broken_tool' "$SETTINGS" && fail "tools of an unbuilt extension switched on"
[ "$(readlink "$LINK")" = "$EXT" ] || fail "a neighbour's failure disturbed a working extension"

echo "8. removing the entry unlinks and unsets, keeping foreign entries"
config ""
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code"; }
[ -e "$LINK" ] || [ -L "$LINK" ] && fail "extension link not removed"
python3 - "$SETTINGS" <<'PY' || fail "defaultTools not restored"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["defaultTools"] == ["+foreign_tool"], d
PY
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after removal"; }

echo "9. a local directory is used where it stands"
config "[:uri \"file://$SB/dev-copy\"] {:alias local-ext :tools [\"+local_tool\"]}"
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! exited $code"; }
[ "$(readlink "$SB/.pi/agent/extensions/local-ext")" = "$SB/dev-copy" ] || fail "local extension not linked in place"
[ -e "$SB/.agents/extensions/local-ext" ] && fail "a local directory must not be cloned"

echo "10. malformed declarations are refused"
config "[:uri \"file://$SB/dev-copy\"] {:tools [:pi]}"
set +e; run validate -f "$SB/agents.edn" > "$SB/validate.txt" 2>&1; code=$?; set -e
grep -q ':acli picks the CLIs' "$SB/validate.txt" || { cat "$SB/validate.txt"; fail "keyword :tools not reported"; }

echo "extensions: all passed"
