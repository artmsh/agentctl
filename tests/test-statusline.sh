#!/usr/bin/env bash
# End-to-end: the shared status-line config, `:statusline` in agents.edn,
# written to agent-statusline/config.json and validated by the renderer's own
# `--check`. tests/statusline-stub stands in for the renderer; set
# AGENTCTL_TEST_STATUSLINE_BIN to a built agent-statusline to run the same
# assertions against the real one.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-statusline.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB" HOME="$SB" NO_COLOR=1
unset DSH_HOME DSH_AGENTS_HOME XDG_CONFIG_HOME AGENT_STATUSLINE_BIN AGENT_STATUSLINE_CONFIG

# A PATH built here: whether agent-statusline (or awl) is found must not
# depend on what the machine running the tests has installed.
mkdir -p "$SB/bin" "$SB/sysbin"
for t in bb python3; do ln -s "$(command -v "$t")" "$SB/sysbin/$t"; done
export PATH="$SB/bin:$SB/sysbin:/usr/bin:/bin"
RENDERER=${AGENTCTL_TEST_STATUSLINE_BIN:-$ROOT/tests/statusline-stub}
ln -s "$RENDERER" "$SB/bin/agent-statusline"
export STUB_LOG="$SB/stub.log"

bb -cp "$ROOT/src" "$ROOT/tests/statusline_test.clj"

run() { (cd "$SB" && "$ROOT/agentctl" "$@"); }
fail() { echo "FAIL: $*" >&2; exit 1; }
CFG="$SB/agents.edn"
FILE="$SB/.config/agent-statusline/config.json"
declare_sl() { printf '{:statusline %s}\n' "$1" > "$CFG"; }
plan() { set +e; run apply -f "$CFG" --all "$@" > "$SB/out.txt" 2>&1; code=$?; set -e; }
apply() { set +e; run apply! -f "$CFG" --all -y "$@" > "$SB/out.txt" 2>&1; code=$?; set -e; }
validate() { set +e; run validate -f "$CFG" > "$SB/out.txt" 2>&1; code=$?; set -e; }
has() { grep -qF -- "$1" "$SB/out.txt" || { cat "$SB/out.txt"; fail "$2"; }; }
lacks() { if grep -qF -- "$1" "$SB/out.txt"; then cat "$SB/out.txt"; fail "$2"; fi; }
json() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$1"; }

GOOD='{:defaults {:segments [:model :limits :context :directory :branch] :separator " · "
             :color :auto :context-style :compact :short-model true :show-pacing true
             :warning-percent 50 :critical-percent 80}
  :agents {:pi {:segments [:model :context :directory :branch :cost]}
           :codex {:context-style :percent}
           :cc {:color nil}}}'

# --- validate catches what the renderer rejects, and apply refuses it
while IFS='|' read -r defaults diag; do
  declare_sl "{:defaults {$defaults}}"
  validate
  [ "$code" = 1 ] || { cat "$SB/out.txt"; fail "validate passed {$defaults}"; }
  has "✗ config" "{$defaults}: no error under STATUSLINE"
  has "$diag" "{$defaults}: diagnostic $diag not reported"
  plan
  [ "$code" = 1 ] || { cat "$SB/out.txt"; fail "apply planned {$defaults}"; }
  has "refusing to plan" "apply did not refuse {$defaults}"
  [ ! -e "$FILE" ] || fail "an invalid config was written"
done <<'CASES'
:segments [:model :bogus]|InvalidEnumTag
:segments [:model :context :model]|DuplicateSegment
:color :sometimes|InvalidEnumTag
:warning-percent 90 :critical-percent 50|InvalidThresholds
CASES

declare_sl '{:defaults {:context-style :full :context_style :compact}}'
validate
[ "$code" = 1 ] && has "keep one" "two EDN spellings of one JSON key were not refused"

# --- a valid config: validate, preview, write, converge
declare_sl "$GOOD"
validate
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "a valid config failed validate"; }
has "✓ config" "the renderer's verdict is not reported"
plan
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "apply on a fresh machine exited $code, not 2"; }
has "+ statusline/config" "no create planned"
has "write ~/.config/agent-statusline/config.json" "the target is not named"
has "+ warning_percent: 50" "the create does not preview the JSON"
has '+ segments: ["model" "context" "directory" "branch" "cost"]' "the pi override is not previewed"
[ ! -e "$FILE" ] || fail "apply (read-only) wrote the file"

apply
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "apply! failed"; }
[ -f "$FILE" ] || fail "apply! did not write $FILE"
[ "$(json "$FILE" 'sorted(d)')" = "['agents', 'defaults']" ] || fail "top-level keys"
[ "$(json "$FILE" '(d["defaults"]["context_style"], d["defaults"]["short_model"], d["defaults"]["separator"])')" = "('compact', True, ' · ')" ] \
  || { cat "$FILE"; fail "defaults were not translated"; }
[ "$(json "$FILE" 'sorted((k, sorted(v.items())) for k, v in d["agents"].items())')" = "[('cc', [('color', None)]), ('codex', [('context_style', 'percent')]), ('pi', [('segments', ['model', 'context', 'directory', 'branch', 'cost'])])]" ] \
  || { cat "$FILE"; fail "per-agent overrides are not each under their own agent"; }
[ "$(json "$FILE" '"cost" in d["defaults"]["segments"]')" = False ] || fail "the pi override leaked into defaults"

plan
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "second apply is not converged"; }
has "no changes" "second apply planned something"
run state > "$SB/out.txt"
grep -qE 'agentctl +statusline +config' "$SB/out.txt" || { cat "$SB/out.txt"; fail "the file is not owned"; }
grep -q -- '--check --config' "$STUB_LOG" 2>/dev/null || [ -n "${AGENTCTL_TEST_STATUSLINE_BIN:-}" ] || fail "the stub was never asked"
if grep -q STDIN "$STUB_LOG" 2>/dev/null; then fail "--check was fed stdin"; fi

# --- a change previews as a field diff
declare_sl "${GOOD/:warning-percent 50/:warning-percent 60}"
plan
[ "$code" = 2 ] && has "~ statusline/config" "no update planned"
has "warning_percent: 50 -> 60" "the update does not show the field diff"
apply
[ "$(json "$FILE" 'd["defaults"]["warning_percent"]')" = 60 ] || fail "the update was not written"

# --- a hand edit is drift
python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); d['defaults']['color']='never'; json.dump(d, open(p,'w'))" "$FILE"
plan
[ "$code" = 2 ] && has 'color: "never" -> "auto"' "a hand edit was not reported as drift"
apply

# --- -k selects it; -t, which names CLIs, leaves it out
declare_sl "${GOOD/:warning-percent 50/:warning-percent 55}"
plan -k statusline
[ "$code" = 2 ] && has "statusline/config" "-k statusline did not select it"
plan -t claude
lacks "statusline/config" "-t claude planned the shared file"

# --- dropping the declaration deletes the file agentctl owns, backed up
echo '{}' > "$CFG"
plan
[ "$code" = 2 ] && has "- statusline/config" "no removal planned"
apply
[ ! -e "$FILE" ] || fail "the owned file was not removed"
ls "$SB/.config/agentctl/backups"/*/*agent-statusline_config.json >/dev/null 2>&1 || fail "no backup of the removed file"
run state > "$SB/out.txt"
lacks "statusline" "ownership survived the removal"

# --- a file agentctl did not write is never removed, and replaced only with a backup
mkdir -p "$(dirname "$FILE")"
printf '{"defaults": {"color": "never"}}\n' > "$FILE"
plan
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "an unowned file drew a plan"; }
[ -f "$FILE" ] || fail "an unowned file was touched"
declare_sl "$GOOD"
plan
[ "$code" = 2 ] && has "replaces a file agentctl did not write" "replacing an unowned file is not said"
rm -rf "$SB/.config/agentctl/backups"
apply
grep -q never "$SB/.config/agentctl/backups"/*/*agent-statusline_config.json || fail "the hand-made file was not backed up"

# --- no renderer: unknown, said out loud, never a pass
rm "$SB/bin/agent-statusline"
declare_sl "${GOOD/:warning-percent 50/:warning-percent 45}"
validate
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "a missing renderer is an error"; }
has "? config" "a missing renderer is not unknown"
has "not validated" "a missing renderer passes silently"
lacks "✓ config" "a missing renderer reads as success"
plan
has "not validated" "the plan does not say the write is unvalidated"

# --- a renderer that predates --check: unknown, not a verdict on the config
printf '#!/bin/sh\necho "agent-statusline: UnknownArgument" >&2\nexit 1\n' > "$SB/bin/agent-statusline"
chmod +x "$SB/bin/agent-statusline"
validate
[ "$code" = 0 ] && has "does not implement --check" "an old renderer was not recognised"
lacks "✗ config" "an old renderer's refusal was taken as invalid"
rm "$SB/bin/agent-statusline"

# --- :bin and AGENT_STATUSLINE_BIN find a renderer that is not on PATH
declare_sl "{:bin \"$RENDERER\" :defaults {:segments [:model :model]}}"
validate
[ "$code" = 1 ] && has "DuplicateSegment" ":bin was not used to validate"
declare_sl '{:defaults {:segments [:model]}}'
set +e; AGENT_STATUSLINE_BIN="$RENDERER" run validate -f "$CFG" > "$SB/out.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] && has "✓ config" "AGENT_STATUSLINE_BIN was not used"
declare_sl "{:bin \"$RENDERER\" :defaults {:segments [:model]}}"
apply
if grep -q '"bin"' "$FILE"; then fail ":bin was written into the JSON"; fi
ln -s "$RENDERER" "$SB/bin/agent-statusline"

# --- the renderer's lookup: XDG_CONFIG_HOME, and a pointer elsewhere
declare_sl "$GOOD"
set +e; XDG_CONFIG_HOME="$SB/xdg" run apply! -f "$CFG" --all -y > "$SB/out.txt" 2>&1; set -e
[ -f "$SB/xdg/agent-statusline/config.json" ] || { cat "$SB/out.txt"; fail "XDG_CONFIG_HOME was not followed"; }
set +e; XDG_CONFIG_HOME=/agentctl-test-outside run apply -f "$CFG" --all -v > "$SB/out.txt" 2>&1; set -e
has "~/.config/agent-statusline/config.json" "an XDG base outside the sandbox was followed"
set +e; XDG_CONFIG_HOME=/agentctl-test-outside run validate -f "$CFG" > "$SB/out.txt" 2>&1; set -e
has "reads /agentctl-test-outside/agent-statusline/config.json" "the renderer/agentctl path split is not reported"
set +e; AGENT_STATUSLINE_CONFIG="$SB/other.json" run validate -f "$CFG" > "$SB/out.txt" 2>&1; set -e
has "! env" "AGENT_STATUSLINE_CONFIG pointing elsewhere is not a warning"
# ownership remembers where the file went: dropping it removes the XDG copy
echo '{}' > "$CFG"
apply
[ ! -e "$SB/xdg/agent-statusline/config.json" ] || fail "the file written under XDG was not removed"
[ -f "$FILE" ] || fail "a file agentctl no longer owns was removed"

echo "all statusline e2e checks passed"
