#!/usr/bin/env bash
# End-to-end: agentctl converges a scratch HOME, stays converged, and prunes.
# Uses only the file-driven adapters (pi, omp) so no external CLI is required.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-e2e.XXXXXX")
trap 'rm -rf "$SB"' EXIT

export AGENTCTL_HOME="$SB"
run() { "$ROOT/agentctl" "$@"; }

# agentctl skips a tool whose CLI is not installed, so without these the suite
# would quietly plan nothing on a machine that has no agent installed — which
# is every CI runner. The adapters exercised here write files directly; the
# stubs only have to exist and succeed.
mkdir -p "$SB/bin"
for cli in claude codex pi omp llm; do
  printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"
  chmod +x "$SB/bin/$cli"
done
export PATH="$SB/bin:$PATH"

mkdir -p "$SB/packs/demo/skills/demo-skill" "$SB/brain"
printf -- '---\nname: demo-skill\ndescription: Demo.\n---\n\nbody\n' \
  > "$SB/packs/demo/skills/demo-skill/SKILL.md"
printf '# memory\n' > "$SB/brain/AGENTS.md"

cat > "$SB/agents.edn" <<EDN
{:executors {:pi {:model "m1" :provider "prov"}
            :omp {:personality "pragmatic" :model-roles {:default "prov/m1"}}}
 :mcps {:demo {:command "/bin/echo" :args ["hi"] :tools [:pi :omp]}}
 :skill-packs {:demo {:uri "file://$SB/packs/demo" :dir "skills"}}
 :skills {:demo-skill {:from :demo :tools [:pi :omp]}}
 :memory {:shared {:from "$SB/brain/AGENTS.md" :tools [:pi :omp]}}
 :extra-providers {:prov {:url "http://127.0.0.1:9999" :models ["m1"] :tools [:pi :omp]}}
 :projects {:proj {:path "$SB/proj" :trusted true}}}
EDN

fail() { echo "FAIL: $1" >&2; exit 1; }

echo "1. dry apply reports drift and exits 2"
set +e
run apply -f "$SB/agents.edn" -t pi -t omp > "$SB/plan1.txt"; code=$?
set -e
[ "$code" = 2 ] || fail "expected exit 2 from dry apply, got $code"
grep -q '+ skills/demo-skill' "$SB/plan1.txt" || fail "skill op missing from plan"
[ -e "$SB/.pi/agent/settings.json" ] && fail "dry apply must not write anything"

echo "2. apply! converges"
run apply! -f "$SB/agents.edn" -t pi -t omp -y > "$SB/apply.txt"
[ -L "$SB/.pi/agent/skills/demo-skill" ] || fail "skill not linked"
[ -L "$SB/.omp/agent/AGENTS.md" ] || fail "memory not linked"
grep -q '"m1"' "$SB/.pi/agent/settings.json" || fail "pi settings not written"
grep -q 'personality' "$SB/.omp/agent/config.yml" || fail "omp settings not written"
grep -q 'demo' "$SB/.pi/agent/mcp.json" || fail "pi mcp not written"

echo "3. second apply is a no-op (idempotence)"
set +e
run apply -f "$SB/agents.edn" -t pi -t omp > "$SB/plan2.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan2.txt"; fail "expected converged state, got exit $code"; }

echo "4. dropping a resource produces a delete"
python3 - "$SB/agents.edn" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
s = s.replace(' :skills {:demo-skill {:from :demo :tools [:pi :omp]}}\n', ' :skills {}\n')
open(p, 'w').write(s)
PY
set +e
run apply -f "$SB/agents.edn" -t pi -t omp > "$SB/plan3.txt"; code=$?
set -e
grep -q -- '- skills/demo-skill' "$SB/plan3.txt" || { cat "$SB/plan3.txt"; fail "prune not planned"; }
run apply! -f "$SB/agents.edn" -t pi -t omp -y > /dev/null
[ -e "$SB/.pi/agent/skills/demo-skill" ] && fail "pruned skill still present"

echo "5. unmanaged resources are never touched"
mkdir -p "$SB/.pi/agent/skills/handmade"
printf -- '---\nname: handmade\ndescription: d\n---\n' > "$SB/.pi/agent/skills/handmade/SKILL.md"
run apply! -f "$SB/agents.edn" -t pi -t omp -y > /dev/null
[ -d "$SB/.pi/agent/skills/handmade" ] || fail "agentctl deleted an unmanaged skill"

echo "6. validate reports config errors with exit 1"
cat > "$SB/broken.edn" <<'EDN'
{:mcps {:broken {}}}
EDN
set +e
run validate -f "$SB/broken.edn" > "$SB/validate.txt"; code=$?
set -e
[ "$code" = 1 ] || fail "expected exit 1 from validate on a broken config"

echo "7. import round-trips into an applyable config"
run import -f "$SB/none.edn" | grep -v '^;;' > "$SB/imported.edn"
set +e
run apply -f "$SB/imported.edn" -t pi -t omp > "$SB/plan4.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan4.txt"; fail "imported config should already be converged"; }

echo "8. settings schema violation on an agentctl-managed key is an error"
mkdir -p "$SB/.config/agentctl/cache" "$SB/sproj/.claude"
cat > "$SB/.config/agentctl/cache/claude-code-settings.schema.json" <<'JSON'
{"properties": {"effortLevel": {"type": "string", "enum": ["low", "medium", "high", "xhigh"]}}}
JSON
printf '{"effortLevel": "max"}\n' > "$SB/sproj/.claude/settings.json"
cat > "$SB/schema.edn" <<EDN
{:projects {:sproj {:path "$SB/sproj" :executors {:claude {:effort "max"}}}}}
EDN
set +e
run validate -f "$SB/schema.edn" > "$SB/validate2.txt"; code=$?
set -e
[ "$code" = 1 ] || { cat "$SB/validate2.txt"; fail "expected exit 1 from managed-key schema violation"; }
grep -q 'effortLevel' "$SB/validate2.txt" || { cat "$SB/validate2.txt"; fail "effortLevel violation not reported"; }

echo "9. no schema cache and no --deep reports unknown, not a failure"
rm -f "$SB/.config/agentctl/cache/claude-code-settings.schema.json"
set +e
run validate -f "$SB/schema.edn" > "$SB/validate3.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/validate3.txt"; fail "missing schema cache should not fail validate"; }
grep -q 'schema unavailable' "$SB/validate3.txt" || { cat "$SB/validate3.txt"; fail "unknown-schema finding missing"; }

echo "10. a pack fetched by apply! is linked in the same run"
GITPACK="$SB/gitpack"
mkdir -p "$GITPACK/skills/fresh-skill"
printf -- '---\nname: fresh-skill\ndescription: Fresh.\n---\n\nbody\n' \
  > "$GITPACK/skills/fresh-skill/SKILL.md"
git -C "$GITPACK" init -q
git -C "$GITPACK" -c user.email=t@e -c user.name=t add -A
git -C "$GITPACK" -c user.email=t@e -c user.name=t commit -qm init
cat > "$SB/pack.edn" <<EDN
{:executors {:pi {:model "m1" :provider "prov"}}
 :skill-packs {:fresh {:uri "file://$GITPACK" :type :git :dir "skills"}}
 :skills {:fresh-skill {:from :fresh :tools [:pi]}}
 :extra-providers {:prov {:url "http://127.0.0.1:9999" :models ["m1"] :tools [:pi]}}}
EDN
run apply! -f "$SB/pack.edn" -t pi -y > "$SB/apply10.txt"
[ -d "$SB/.agents/skill-packs/fresh/.git" ] || { cat "$SB/apply10.txt"; fail "pack not cloned"; }
[ -L "$SB/.pi/agent/skills/fresh-skill" ] || { cat "$SB/apply10.txt"; fail "skill from a freshly cloned pack not linked in the same run"; }
set +e
run apply -f "$SB/pack.edn" -t pi > "$SB/plan10.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan10.txt"; fail "two-pass apply left drift behind"; }

echo "11. a project's pack skills are cloned, linked and repointed in one run"
mkdir -p "$SB/pproj" "$SB/elsewhere/fresh-skill"
ln -s "$SB/elsewhere/fresh-skill" "$SB/pproj/.claude-stale" 2>/dev/null || true
mkdir -p "$SB/pproj/.claude/skills"
ln -s "$SB/elsewhere/fresh-skill" "$SB/pproj/.claude/skills/fresh-skill"
cat > "$SB/pskills.edn" <<EDN
{:skill-packs {:fresh2 {:uri "file://$GITPACK" :type :git :dir "skills"}}
 :projects {:pproj {:path "$SB/pproj" :executors {:claude {}} :skills [:fresh2]}}}
EDN
set +e
run apply -f "$SB/pskills.edn" > "$SB/plan11.txt"; code=$?
set -e
grep -q 'pack not fetched yet' "$SB/plan11.txt" || { cat "$SB/plan11.txt"; fail "unfetched pack not reported"; }
run apply! -f "$SB/pskills.edn" -y > "$SB/apply11.txt"
[ -d "$SB/.agents/skill-packs/fresh2/.git" ] || { cat "$SB/apply11.txt"; fail "project pack not cloned"; }
link=$(readlink "$SB/pproj/.claude/skills/fresh-skill")
case "$link" in
  */.agents/skill-packs/fresh2/*) ;;
  *) cat "$SB/apply11.txt"; fail "stale project skill link not repointed at the pack cache (-> $link)";;
esac
[ -f "$SB/pproj/.claude/skills/fresh-skill/SKILL.md" ] || fail "repointed link does not resolve"
set +e
run apply -f "$SB/pskills.edn" > "$SB/plan11b.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan11b.txt"; fail "project pack skills left drift behind"; }

echo "12. a server also written in .mcp.json is reported, not deleted"
mkdir -p "$SB/mproj"
printf '{"mcpServers": {"shared": {"command": "/bin/echo", "env": {"TOKEN": "sk-live-abc"}}}}\n' \
  > "$SB/mproj/.mcp.json"
cat > "$SB/mcp.edn" <<EDN
{:mcps {:shared {:cmd "/bin/echo hi" :tools [:claude]}}
 :projects {:mproj {:path "$SB/mproj" :executors {:claude {}} :mcp [:shared]}}}
EDN
set +e
run apply -f "$SB/mcp.edn" > "$SB/plan12.txt"; code=$?
set -e
grep -q 'also declared in' "$SB/plan12.txt" || { cat "$SB/plan12.txt"; fail "orphaned .mcp.json copy not reported"; }
grep -q 'sk-live-abc' "$SB/plan12.txt" && fail "plan printed a live credential"
run apply! -f "$SB/mcp.edn" -y > /dev/null
grep -q 'shared' "$SB/mproj/.mcp.json" || fail "agentctl deleted unmanaged .mcp.json content"
python3 - "$SB/.claude.json" <<'PY' || fail "local scope write missing"
import json, sys
d = json.load(open(sys.argv[1]))
hits = [p for p, v in d.get("projects", {}).items()
        if p.endswith("/mproj") and "shared" in v.get("mcpServers", {})]
assert hits, "local-scope server not written into ~/.claude.json"
PY

echo "13. a project's server dropped from the config is pruned from local scope"
mkdir -p "$SB/dproj"
cat > "$SB/drop-with.edn" <<EDN
{:mcps {:leaver {:cmd "/bin/echo hi" :tools [:claude]}}
 :projects {:dproj {:path "$SB/dproj" :executors {:claude {}} :mcp [:leaver]}}}
EDN
cat > "$SB/drop-without.edn" <<EDN
{:projects {:dproj {:path "$SB/dproj" :executors {:claude {}}}}}
EDN
run apply! -f "$SB/drop-with.edn" -y > /dev/null
python3 - "$SB/.claude.json" <<'PY' || fail "local-scope server not written"
import json, sys
d = json.load(open(sys.argv[1]))
assert [p for p, v in d.get("projects", {}).items()
        if p.endswith("/dproj") and "leaver" in v.get("mcpServers", {})]
PY
set +e
run apply -f "$SB/drop-without.edn" > "$SB/plan13.txt"; code=$?
set -e
grep -q -- '- projects/dproj mcps/leaver' "$SB/plan13.txt" \
  || { cat "$SB/plan13.txt"; fail "dropped project server not planned for prune"; }
run apply! -f "$SB/drop-without.edn" -y > /dev/null
python3 - "$SB/.claude.json" <<'PY' || fail "project server left behind in ~/.claude.json"
import json, sys
d = json.load(open(sys.argv[1]))
assert not [p for p, v in d.get("projects", {}).items()
            if "leaver" in v.get("mcpServers", {})]
PY
set +e
run apply -f "$SB/drop-without.edn" > "$SB/plan13b.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan13b.txt"; fail "prune left drift behind"; }

echo "14. a hand-made project server is never pruned"
mkdir -p "$SB/hproj"
cat > "$SB/hand.edn" <<EDN
{:projects {:hproj {:path "$SB/hproj" :executors {:claude {}}}}}
EDN
run apply! -f "$SB/hand.edn" -y > /dev/null
python3 - "$SB/.claude.json" "$SB/hproj" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d.setdefault("projects", {}).setdefault(sys.argv[2], {})["mcpServers"] = {
    "handmade": {"command": "/bin/echo"}}
json.dump(d, open(p, "w"))
PY
run apply! -f "$SB/hand.edn" -y > /dev/null
python3 - "$SB/.claude.json" <<'PY' || fail "agentctl pruned a server it never installed"
import json, sys
d = json.load(open(sys.argv[1]))
assert [p for p, v in d.get("projects", {}).items()
        if "handmade" in v.get("mcpServers", {})]
PY

echo "all agentctl e2e checks passed"
