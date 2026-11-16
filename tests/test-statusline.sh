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

declare_sl '{:defaults {:segments [:model :model]}}'
for narrow in "-t claude" "-k mcps"; do
  plan $narrow
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "an invalid statusline stopped apply $narrow, which cannot write it"; }
  has "this run does not write it" "apply $narrow does not mention the invalid statusline"
done

declare_sl '{:defaults {:context-style :full :context_style :compact}}'
validate
[ "$code" = 1 ] || { cat "$SB/out.txt"; fail "exit $code, want 1: two EDN spellings of one JSON key were not refused"; }
has "keep one" "two EDN spellings of one JSON key were not refused"

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
has "+ color: ∅" "a null override previews as an empty agent"
[ ! -e "$FILE" ] || fail "apply (read-only) wrote the file"
set +e; run apply -f "$CFG" --all --json > "$SB/plan.json" 2>/dev/null; set -e
python3 - "$SB/plan.json" <<'PY' || fail "--json does not carry the null override as null"
import json, sys
ops = [o for o in json.load(open(sys.argv[1]))["ops"] if o["kind"] == "statusline"]
assert ops[0]["diffs"][0]["after"]["agents"]["cc"] == {"color": None}, ops[0]["diffs"]
PY

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
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: no update planned"; }
has "~ statusline/config" "no update planned"
has "warning_percent: 50 -> 60" "the update does not show the field diff"
lacks "∅" "an unchanged null override reads as a change"
apply
[ "$(json "$FILE" 'd["defaults"]["warning_percent"]')" = 60 ] || fail "the update was not written"

# --- import reads the file back, so --replace does not drop it
set +e; run import -f "$CFG" --replace > "$SB/out.txt" 2>&1; set -e
has ":statusline" "import --replace dropped the status line"
has ":context-style \"compact\"" "import did not read the file back in kebab-case"

# --- a hand edit is drift
python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); d['defaults']['color']='never'; json.dump(d, open(p,'w'))" "$FILE"
plan
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: a hand edit was not reported as drift"; }
has 'color: "never" -> "auto"' "a hand edit was not reported as drift"
apply

# --- -k selects it; -t, which names CLIs, leaves it out
declare_sl "${GOOD/:warning-percent 50/:warning-percent 55}"
plan -k statusline
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: -k statusline did not select it"; }
has "statusline/config" "-k statusline did not select it"
plan -t claude
lacks "statusline/config" "-t claude planned the shared file"
plan -k statusline -t claude
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: -k statusline -t claude hid a pending write"; }
has "statusline/config" "-k statusline -t claude hid a pending write"

# --- dropping the declaration deletes the file agentctl owns, backed up
echo '{}' > "$CFG"
plan
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: no removal planned"; }
has "- statusline/config" "no removal planned"
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
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: replacing an unowned file is not said"; }
has "replaces a file agentctl did not write" "replacing an unowned file is not said"
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
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "exit $code, want 0: an old renderer was not recognised"; }
has "does not implement --check" "an old renderer was not recognised"
lacks "✗ config" "an old renderer's refusal was taken as invalid"
rm "$SB/bin/agent-statusline"

# --- with none on PATH, the renderer a Claude :status-line command runs
mkdir -p "$SB/tools"
ln -s "$RENDERER" "$SB/tools/agent-statusline"
cat > "$CFG" <<EDN
{:executors {:claude {:status-line {:type "command" :command "\"$SB/tools/agent-statusline\" --agent cc"}}}
 :statusline {:defaults {:segments [:model :model]}}}
EDN
validate
has "DuplicateSegment" "the renderer a Claude :status-line command names was not used"

# --- :bin and AGENT_STATUSLINE_BIN find a renderer that is not on PATH
declare_sl "{:bin \"$RENDERER\" :defaults {:segments [:model :model]}}"
validate
[ "$code" = 1 ] || { cat "$SB/out.txt"; fail "exit $code, want 1: :bin was not used to validate"; }
has "DuplicateSegment" ":bin was not used to validate"
declare_sl '{:defaults {:segments [:model]}}'
set +e; AGENT_STATUSLINE_BIN="$RENDERER" run validate -f "$CFG" > "$SB/out.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "exit $code, want 0: AGENT_STATUSLINE_BIN was not used"; }
has "✓ config" "AGENT_STATUSLINE_BIN was not used"
declare_sl "{:bin \"$RENDERER\" :defaults {:segments [:model]}}"
apply
if grep -q '"bin"' "$FILE"; then fail ":bin was written into the JSON"; fi
ln -s "$RENDERER" "$SB/bin/agent-statusline"

# --- the target moves: XDG_CONFIG_HOME set over a file agentctl owns at ~/.config
declare_sl "$GOOD"
apply
XFILE="$SB/xdg/agent-statusline/config.json"
mkdir -p "$(dirname "$XFILE")"
printf '{"defaults": {"color": "always"}}\n' > "$XFILE"
set +e; XDG_CONFIG_HOME="$SB/xdg" run apply -f "$CFG" --all > "$SB/out.txt" 2>&1; code=$?; set -e
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: a hand-made file at the new target reads as agentctl's"; }
has "replaces a file agentctl did not write" "a hand-made file at the new target reads as agentctl's"
has "moved to ~/xdg/agent-statusline/config.json" "the file owned at the old path is not removed"
rm -rf "$SB/.config/agentctl/backups"
set +e; XDG_CONFIG_HOME="$SB/xdg" run apply! -f "$CFG" --all -y > "$SB/out.txt" 2>&1; set -e
grep -q '"segments"' "$XFILE" || { cat "$SB/out.txt"; fail "XDG_CONFIG_HOME was not followed"; }
[ ! -e "$FILE" ] || fail "the file agentctl owned at the old path stayed behind"
grep -q always "$SB/.config/agentctl/backups"/*/*xdg_agent-statusline_config.json \
  || fail "the hand-made file at the new target was not backed up"
# `..` does not climb out of the sandbox
set +e; XDG_CONFIG_HOME="$SB/../agentctl-test-outside" run apply -f "$CFG" --all -v > "$SB/out.txt" 2>&1; set -e
has "write ~/.config/agent-statusline/config.json" "an XDG base climbing out with .. was followed"
lacks "write $(dirname "$SB")" "an XDG base climbing out with .. was written to"
set +e; XDG_CONFIG_HOME=/agentctl-test-outside run validate -f "$CFG" > "$SB/out.txt" 2>&1; set -e
has "reads /agentctl-test-outside/agent-statusline/config.json" "the renderer/agentctl path split is not reported"
set +e; AGENT_STATUSLINE_CONFIG="$SB/other.json" run validate -f "$CFG" > "$SB/out.txt" 2>&1; set -e
has "! env" "AGENT_STATUSLINE_CONFIG pointing elsewhere is not a warning"
# ownership remembers where the file went: dropping it removes the XDG copy
echo '{}' > "$CFG"
apply
[ ! -e "$XFILE" ] || fail "the file written under XDG was not removed"

# --- a symlinked config.json: the link is replaced, the file behind it never written
mkdir -p "$SB/dotfiles" "$(dirname "$FILE")"
printf '{"defaults": {"color": "never"}}\n' > "$SB/dotfiles/sl.json"
ln -s "$SB/dotfiles/sl.json" "$FILE"
declare_sl "$GOOD"
plan
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: replacing a symlink is not said"; }
has "replaces the symlink to ~/dotfiles/sl.json" "replacing a symlink is not said"
rm -rf "$SB/.config/agentctl/backups"
apply
[ -L "$FILE" ] && fail "the symlink was written through, not replaced"
grep -q '"segments"' "$FILE" || fail "the regular file was not written"
[ "$(cat "$SB/dotfiles/sl.json")" = '{"defaults": {"color": "never"}}' ] || fail "the file behind the symlink was overwritten"
BK=$(ls "$SB/.config/agentctl/backups"/*/*agent-statusline_config.json)
[ -f "$BK" ] && [ ! -L "$BK" ] && grep -q never "$BK" || fail "the backup is not the linked content as a regular file"
# an owned path that has become a symlink is left alone on removal
rm "$FILE"
ln -s "$SB/dotfiles/sl.json" "$FILE"
echo '{}' > "$CFG"
plan --show-noop
has "is a symlink now" "an owned path turned symlink is not reported"
apply
[ -L "$FILE" ] || fail "a symlink at the owned path was removed"
rm "$FILE"

# --- awl reads the file only when built with -Dstatusline; nothing else says so
declare_sl '{:defaults {:segments [:model :context]} :agents {:awl {:context-style :full}}}'
validate
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "an awl override failed validate"; }
has "! awl" "awl absent from PATH is not a warning"
has "not on PATH" "awl absent from PATH is not said"
lacks "✓ awl" "an awl nobody can inspect reads as satisfied"
apply
plan
[ "$code" = 0 ] || { cat "$SB/out.txt"; fail "the awl case did not converge"; }
has "statusline awl" "a converged apply hides that awl may ignore the file"
printf '\xcf\xfa\xed\xfe\0\0awl' > "$SB/bin/awl"
chmod +x "$SB/bin/awl"
validate
has "built without -Dstatusline" "an awl built without the renderer is not reported"
lacks "✓ awl" "an awl built without the renderer reads as satisfied"
printf '\x7fELF\0\0AGENT_STATUSLINE_CONFIG' > "$SB/bin/awl"
validate
has "✓ awl" "an awl built with the renderer is not recognised"
has "built with -Dstatusline" "an awl built with the renderer is not named"
printf '#!/bin/sh\nexec /somewhere/awl "$@"\n' > "$SB/bin/awl"
validate
has "not a native executable" "a wrapper script was judged"
lacks "✓ awl" "a wrapper script reads as satisfied"
rm "$SB/bin/awl"
declare_sl '{:defaults {:segments [:model :context]} :agents {:pi {:context-style :full}}}'
validate
lacks "awl" "a statusline without an awl override asked about awl"

# --- --host: the file is this machine's; a host run neither checks nor mentions it
export AGENTCTL_SSH_CMD="$ROOT/tests/ssh-stub"
mkdir -p "$SB/far"
HOSTCFG="$SB/host.edn"
cat > "$HOSTCFG" <<EDN
{:hosts {:box {:ssh "box" :os :linux :home "$SB/far"}}
 :statusline {:defaults {:segments [:model :model]} :agents {:awl {:color :never}}}}
EDN
: > "$STUB_LOG"
set +e; run validate -f "$HOSTCFG" > "$SB/out.txt" 2>&1; code=$?; set -e
[ "$code" = 1 ] || { cat "$SB/out.txt"; fail "exit $code, want 1: fixture: the local run does not reject the config"; }
has "DuplicateSegment" "fixture: the local run does not reject the config"
[ -n "${AGENTCTL_TEST_STATUSLINE_BIN:-}" ] || [ -s "$STUB_LOG" ] || fail "fixture: the local run did not ask the renderer"
for cmd in apply validate; do
  : > "$STUB_LOG"
  set +e; XDG_CONFIG_HOME= run "$cmd" -f "$HOSTCFG" --host box > "$SB/out.txt" 2>&1; code=$?; set -e
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "$cmd --host was refused or failed on a local-only statusline"; }
  # the sandbox path itself contains "statusline": match the report's own words
  lacks "STATUSLINE" "$cmd --host reported the local statusline"
  lacks " statusline " "$cmd --host warned about the local statusline"
  lacks "awl" "$cmd --host scanned the local awl"
  lacks "renderer" "$cmd --host compared local paths"
  if [ -s "$STUB_LOG" ]; then cat "$STUB_LOG"; fail "$cmd --host ran the local renderer"; fi
done

# selectors: -k and --only name the file; --only on other kinds leaves it alone
rm -f "$FILE"
declare_sl '{:defaults {:segments [:model :context :model]}}'
plan -k statusline
[ "$code" = 1 ] || { cat "$SB/out.txt"; fail "exit $code, want 1: -k statusline did not check the file it plans"; }
has "DuplicateSegment" "-k statusline did not check the file it plans"
plan --only statusline/config
[ "$code" = 1 ] || { cat "$SB/out.txt"; fail "exit $code, want 1: --only statusline/config did not check the file it plans"; }
has "DuplicateSegment" "--only statusline/config did not check the file it plans"
plan --only mcps/x
[ "$code" != 1 ] || { cat "$SB/out.txt"; fail "--only mcps/x refused a run that cannot write the status-line file"; }
has "DuplicateSegment" "--only mcps/x hid the invalid status line instead of warning"
[ ! -e "$FILE" ] || fail "--only mcps/x touched the status-line file"
declare_sl '{:defaults {:segments [:model :context]}}'
plan --only statusline/config
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "--only statusline/config planned no write"; }
lacks "nothing declared or owned" "--only statusline/config was reported unmatched"

# a dangling config.json link: replaced by a regular file, with nothing to back up
mkdir -p "$(dirname "$FILE")"; rm -f "$FILE"; ln -s "$SB/gone/sl.json" "$FILE"
plan -k statusline
[ "$code" = 2 ] || { cat "$SB/out.txt"; fail "exit $code, want 2: a dangling link planned no write"; }
has "the link was dangling, nothing to back up" "a dangling link was described as backed up"
apply -k statusline
[ "$code" = 0 ] && [ -f "$FILE" ] && [ ! -L "$FILE" ] || { cat "$SB/out.txt"; fail "a dangling link was not replaced by a regular file"; }
[ ! -e "$SB/gone/sl.json" ] || fail "agentctl wrote through a dangling link"
rm -f "$FILE"

echo "all statusline e2e checks passed"
