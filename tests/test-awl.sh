#!/usr/bin/env bash
# End-to-end: :awl is a consumer of :skills and :skill-packs, valid there and
# provisioned never. Its own sandbox, since a leftover manifest would plan prunes.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-awl.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB"
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $*"; exit 1; }

mkdir -p "$SB/bin"
for cli in claude codex pi omp llm agy; do
  printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"
  chmod +x "$SB/bin/$cli"
done
export PATH="$SB/bin:$PATH"

AWL="$SB/awl"
mkdir -p "$AWL/repo/.git"
for pack in kit gitkit; do
  for s in wrap-up proj-skill; do
    mkdir -p "$AWL/$pack/skills/$s"
    printf -- '---\nname: %s\ndescription: Demo.\n---\n\nbody\n' "$s" > "$AWL/$pack/skills/$s/SKILL.md"
  done
done
git -C "$AWL/gitkit" init -q
git -C "$AWL/gitkit" add -A
git -C "$AWL/gitkit" -c user.name=t -c user.email=t@t commit -qm pack
awl_manifest() {
  cat > "$AWL/m.edn" <<EDN
{:skill-packs {[:uri "file://$AWL/kit"] {:alias kit}
               [:uri "file://$AWL/gitkit"] {:alias gk :type :git :dir "skills" :in :all $1}}
 :skills {[kit "wrap-up"] {:in :all $1}
          [kit "proj-skill"] {:in "awl/repo" $1}}}
EDN
}
awl_plan() { (cd "$SB" && run apply -f "$AWL/m.edn" --all --json) || true; }

awl_manifest ':acli [:awl]'
out=$(cd "$SB" && run validate -f "$AWL/m.edn" -t claude -k skills) || { echo "$out"; fail ":acli [:awl] does not validate"; }
if echo "$out" | grep -q 'awl\.\|tools.*unknown'; then echo "$out"; fail ":acli [:awl] drew a finding"; fi
awl_only=$(awl_plan)
echo "$awl_only" | python3 -c 'import json,sys; ops=json.load(sys.stdin)["ops"]; sys.exit(1 if ops else 0)' \
  || { echo "$awl_only"; fail ":acli [:awl] planned something"; }
(cd "$SB" && run apply! -f "$AWL/m.edn" --all -y) > "$AWL/apply.txt"
[ ! -e "$SB/.agents/skill-packs/gk" ] || { cat "$AWL/apply.txt"; fail ":acli [:awl] cloned its pack"; }
[ ! -e "$AWL/repo/.claude" ] && [ ! -e "$AWL/repo/.agents" ] || { cat "$AWL/apply.txt"; fail ":acli [:awl] wrote into the project"; }

awl_manifest ':acli [:claude]'
claude_only=$(awl_plan)
echo "$claude_only" | grep -q 'wrap-up' || { echo "$claude_only"; fail "fixture: :acli [:claude] planned no skill"; }
echo "$claude_only" | grep -q 'proj-skill' || { echo "$claude_only"; fail "fixture: :acli [:claude] planned no project skill"; }
echo "$claude_only" | grep -q 'skill-packs/gk' || { echo "$claude_only"; fail "fixture: :acli [:claude] did not clone the pack"; }
awl_manifest ':acli [:claude :awl]'
[ "$(awl_plan)" = "$claude_only" ] || { awl_plan; echo "$claude_only"; fail ":acli [:claude :awl] planned differently from [:claude]"; }

awl_manifest ''
if awl_plan | grep -q 'awl"\|"awl\|-a awl'; then awl_plan; fail "an entry with no :acli planned for awl"; fi

# a pack only a project uses goes to the skills CLI, which is told of claude alone
cat > "$AWL/cli.edn" <<EDN
{:skill-packs {[:uri "file://$AWL/gitkit"] {:alias gk2 :type :git :dir "skills"}}
 :skills {[gk2 "proj-skill"] {:in "awl/repo" :acli [:claude :awl]}}}
EDN
cli_plan=$( (cd "$SB" && run apply -f "$AWL/cli.edn" --all --json) || true)
echo "$cli_plan" | python3 -c '
import json, sys
argvs = [c for o in json.load(sys.stdin)["ops"] for c in o.get("cmds", []) if "add" in c]
assert argvs, "no skills add planned"
for a in argvs:
    agents = [a[i + 1] for i, x in enumerate(a) if x == "-a"]
    assert agents == ["claude-code"], agents
' || { echo "$cli_plan"; fail "skills add was not given exactly -a claude-code"; }

# a kind awl does not read stays an error, and is refused
cat > "$AWL/mcp.edn" <<EDN
{:mcps [{:id :x :cmd "/bin/echo x" :in :all :acli [:claude :awl]}]}
EDN
set +e
out=$(cd "$SB" && run apply -f "$AWL/mcp.edn" --all 2>&1); code=$?
set -e
[ "$code" = 1 ] && echo "$out" | grep -q 'awl reads only :skills and :skill-packs' \
  || { echo "$out"; fail ":acli [:awl] on :mcps was not refused"; }

for cmd in apply validate; do
  set +e
  out=$(cd "$SB" && run "$cmd" -f "$AWL/m.edn" -t awl 2>&1); code=$?
  set -e
  [ "$code" = 1 ] && echo "$out" | grep -q 'agentctl does not provision it' \
    || { echo "$out"; fail "$cmd -t awl was not rejected"; }
done
set +e
out=$(cd "$SB" && run apply -f "$AWL/m.edn" -t nope 2>&1); code=$?
set -e
[ "$code" = 1 ] && echo "$out" | grep -q 'unknown tool :nope' || { echo "$out"; fail "-t nope was not rejected"; }

# a host takes the same value, and is not probed or written for it
cat > "$AWL/host.edn" <<EDN
{:hosts {:box {:ssh "box" :os :linux :home "$AWL/far"
               :skill-packs {[:uri "file://$AWL/kit"] {:acli [:awl]}}}}}
EDN
mkdir -p "$AWL/far"
export AGENTCTL_SSH_CMD="$ROOT/tests/ssh-stub"
out=$(cd "$SB" && run apply -f "$AWL/host.edn" --host box) || { echo "$out"; fail ":acli [:awl] on a host planned something"; }
if echo "$out" | grep -q 'awl'; then echo "$out"; fail "a host was probed for awl"; fi
cat > "$AWL/host.edn" <<EDN
{:hosts {:box {:ssh "box" :os :linux :home "$AWL/far"
               :skill-packs {[:uri "file://$AWL/kit"] {:acli [:codex :awl]}}}}}
EDN
set +e
out=$(cd "$SB" && run apply -f "$AWL/host.edn" --host box); code=$?
set -e
[ "$code" = 2 ] && echo "$out" | grep -q 'skills/wrap-up' || { echo "$out"; fail ":acli [:codex :awl] on a host did not plan codex"; }
if echo "$out" | grep -q 'skipped'; then echo "$out"; fail "a host skipped a CLI for awl"; fi

echo "all awl e2e checks passed"
