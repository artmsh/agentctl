#!/usr/bin/env bash
# End-to-end: agentctl converges a scratch HOME, stays converged, and prunes.
# Uses only the file-driven adapters (pi, omp, antigravity) so no external CLI is required.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-e2e.XXXXXX")
trap 'rm -rf "$SB"' EXIT
# agentctl normalizes every path it writes; a TMPDIR with a trailing slash would
# otherwise make the fixture and the file disagree by one character
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB"
run() { "$ROOT/agentctl" "$@"; }

# agentctl skips a tool whose CLI is not installed, so without these the suite
# would quietly plan nothing on a machine that has no agent installed — which
# is every CI runner. The adapters exercised here write files directly; the
# stubs only have to exist and succeed.
mkdir -p "$SB/bin"
for cli in claude codex pi omp llm agy; do
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

echo "15. antigravity converges, and trust is unioned rather than overwritten"
mkdir -p "$SB/.gemini/antigravity-cli" "$SB/aproj"
printf '{"trustedWorkspaces": ["%s/handtrusted"], "model": "old"}\n' "$SB" \
  > "$SB/.gemini/antigravity-cli/settings.json"
cat > "$SB/agy.edn" <<EDN
{:executors {:antigravity {:model "Gemini 3.7 Flash (Medium)" :mode "accept-edits"}}
 :mcps {:agydemo {:command "/bin/echo" :args ["hi"] :tools [:antigravity]}
        :agyhttp {:url "https://example.com/sse" :headers {"Authorization" "Bearer y"}
                  :tools [:antigravity]}}
 :skill-packs {:demo15 {:uri "file://$SB/packs/demo" :dir "skills"}}
 :skills {:demo-skill {:from :demo15 :tools [:antigravity] :scope :global}}
 :memory {:shared {:from "$SB/brain/AGENTS.md" :tools [:antigravity]}}
 :projects {:aproj {:path "$SB/aproj" :trusted true :executors {:antigravity {}}
                    :skills [:demo15]}}}
EDN
run apply! -f "$SB/agy.edn" -t antigravity -y > "$SB/apply15.txt"
[ -L "$SB/.gemini/config/skills/demo-skill" ] || { cat "$SB/apply15.txt"; fail "antigravity skill not linked"; }
[ -L "$SB/.gemini/config/rules/AGENTS.md" ] || { cat "$SB/apply15.txt"; fail "antigravity memory not linked"; }
[ -L "$SB/aproj/.agents/skills/demo-skill" ] || { cat "$SB/apply15.txt"; fail "project skill not linked into .agents"; }
python3 - "$SB/.gemini/config/mcp_config.json" <<'PY' || fail "antigravity mcp_config.json wrong"
import json, sys
d = json.load(open(sys.argv[1]))["mcpServers"]
assert d["agydemo"] == {"command": "/bin/echo", "args": ["hi"], "disabled": False}, d["agydemo"]
h = d["agyhttp"]
assert h["serverUrl"] == "https://example.com/sse", h
assert h["headers"] == {"Authorization": "Bearer y"}, h
assert h["disabled"] is False and "url" not in h and "enabled" not in h, h
PY
python3 - "$SB/.gemini/antigravity-cli/settings.json" "$SB" <<'PY' || fail "antigravity settings/trust wrong"
import json, sys
d = json.load(open(sys.argv[1])); sb = sys.argv[2]
assert d["model"] == "Gemini 3.7 Flash (Medium)", d
assert d["agentMode"] == "accept-edits", d
assert sorted(d["trustedWorkspaces"]) == sorted([sb + "/handtrusted", sb + "/aproj"]), d
PY
set +e
run apply -f "$SB/agy.edn" -t antigravity > "$SB/plan15.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan15.txt"; fail "antigravity left drift behind"; }
run import -f "$SB/none.edn" | grep -v '^;;' > "$SB/imported15.edn"
grep -q 'serverUrl' "$SB/imported15.edn" && fail "import leaked antigravity's native key spelling"
# an http server's credential lives in a header, not in :env; unmodelled it would
# be round-tripped into the generated DSL in the clear
grep -q 'Bearer y' "$SB/imported15.edn" && fail "import wrote an http MCP credential in plaintext"
grep -q '!bw://agyhttp/Authorization' "$SB/imported15.edn" \
  || { cat "$SB/imported15.edn"; fail "http MCP header not redacted to a bw placeholder"; }
set +e
# import does not discover a project's own skills for any tool, so only the
# user-wide kinds are expected to come back converged
run apply -f "$SB/imported15.edn" -t antigravity -k settings -k mcps -k memory > "$SB/plan15b.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan15b.txt"; fail "imported antigravity config should already be converged"; }

echo "16. gui plans a buffer live and applies only when confirmed"
mkdir -p "$SB/gproj" "$SB/gsecret"
cat > "$SB/gui.edn" <<EDN
{:executors {:pi {:model "gm" :provider "gprov"}}
 :mcps {:gdemo {:command "/bin/echo" :args ["hi"] :tools [:pi]}}
 :extra-providers {:gprov {:url "http://127.0.0.1:9999" :models ["gm"] :tools [:pi]}}
 :projects {:gproj {:path "$SB/gproj" :executors {:pi {}}}}}
EDN
# the buffer the browser holds: one edit on top of what the file says
{ cat "$SB/gui.edn"; echo ";; edited in the gui"; } > "$SB/gui-buffer.edn"

"$ROOT/agentctl" gui -f "$SB/gui.edn" --port 0 --no-open > "$SB/gui.log" 2>&1 &
GUI_PID=$!
trap 'kill "$GUI_PID" 2>/dev/null || true; rm -rf "$SB"' EXIT
for _ in $(seq 1 100); do grep -q 'http://127.0.0.1' "$SB/gui.log" && break; sleep 0.1; done
URL=$(grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$SB/gui.log" | head -1)
[ -n "$URL" ] || { cat "$SB/gui.log"; fail "gui did not print a URL to open"; }
BASE=${URL%%/\?t=*}
TOK=${URL##*t=}

gapi() { # gapi <path> [json-body-file]
  local path=$1 body=${2:-}
  if [ -n "$body" ]; then
    curl -sS -X POST -H "X-Agentctl-Token: $TOK" -H 'content-type: application/json' \
      --data-binary "@$body" "$BASE$path"
  else
    curl -sS -H "X-Agentctl-Token: $TOK" "$BASE$path"
  fi
}
gstatus() { # gstatus <path> <json-body-file>
  curl -sS -o /dev/null -w '%{http_code}' -X POST -H "X-Agentctl-Token: $TOK" \
    -H 'content-type: application/json' --data-binary "@$2" "$BASE$1"
}
body() { # body <config-file> [extra-json] -> request body on stdout
  local cfg=$1 extra=${2:-}
  [ -n "$extra" ] || extra='{}'
  python3 -c 'import json,sys; d=json.loads(sys.argv[2]); d["text"]=open(sys.argv[1]).read(); print(json.dumps(d))' \
    "$cfg" "$extra"
}

code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/state")
[ "$code" = 403 ] || fail "gui served state without this run's token (got $code)"

gapi /api/config | grep -q 'gprov' || fail "gui did not serve the config file"

body "$SB/gui-buffer.edn" > "$SB/gui-plan.json"
gapi /api/plan "$SB/gui-plan.json" > "$SB/gui-plan.out"
python3 - "$SB/gui-plan.out" <<'PY' || fail "gui plan wrong"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["ok"], d
assert d["changes"] > 0, d
assert "mcps/gdemo" in d["plan"], d["plan"]
PY
grep -q gdemo "$SB/.pi/agent/mcp.json" 2>/dev/null && fail "a gui plan must not write anything"
grep -q 'edited in the gui' "$SB/gui.edn" && fail "a gui plan must not save the buffer"

echo '{"text": "{:mcps {"}' > "$SB/gui-broken.json"
gapi /api/plan "$SB/gui-broken.json" | grep -q 'cannot parse' \
  || fail "half-typed edn should come back as a message, not a failure"

# a credential the plan reads out of an existing tool config is masked, the way
# the cli masks it — the gui ships rendered text for exactly this reason
printf '{"mcpServers": {"gshared": {"command": "/bin/echo", "env": {"TOKEN": "sk-live-gui"}}}}\n' \
  > "$SB/gsecret/.mcp.json"
cat > "$SB/gui-secret.edn" <<EDN
{:mcps {:gshared {:cmd "/bin/echo hi" :tools [:claude]}}
 :projects {:gsecret {:path "$SB/gsecret" :executors {:claude {}} :mcp [:gshared]}}}
EDN
body "$SB/gui-secret.edn" > "$SB/gui-secret.json"
gapi /api/plan "$SB/gui-secret.json" > "$SB/gui-secret.out"
grep -q 'also declared in' "$SB/gui-secret.out" \
  || { cat "$SB/gui-secret.out"; fail "orphaned .mcp.json not reported through the gui"; }
grep -q 'sk-live-gui' "$SB/gui-secret.out" && fail "gui plan leaked a live credential"

body "$SB/gui-buffer.edn" > "$SB/gui-noconfirm.json"
code=$(gstatus /api/apply "$SB/gui-noconfirm.json")
[ "$code" = 400 ] || fail "gui applied without an explicit confirmation (got $code)"
grep -q gdemo "$SB/.pi/agent/mcp.json" 2>/dev/null && fail "unconfirmed apply wrote files"

body "$SB/gui-buffer.edn" '{"confirm": true, "expect": "0 to add, 0 to change, 0 to remove, 0 unchanged"}' \
  > "$SB/gui-stale.json"
code=$(gstatus /api/apply "$SB/gui-stale.json")
[ "$code" = 409 ] || fail "gui applied a plan that no longer matched what was shown (got $code)"
grep -q gdemo "$SB/.pi/agent/mcp.json" 2>/dev/null && fail "stale apply wrote files"

body "$SB/gui-buffer.edn" '{"confirm": true}' > "$SB/gui-apply.json"
gapi /api/apply "$SB/gui-apply.json" > "$SB/gui-apply.out"
python3 - "$SB/gui-apply.out" <<'PY' || fail "gui apply wrong"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["ok"] and d["applied"] > 0 and not d["failed"], d
assert d["backups"], d
PY
grep -q 'gdemo' "$SB/.pi/agent/mcp.json" || fail "gui apply did not converge pi"
grep -q 'edited in the gui' "$SB/gui.edn" || fail "gui apply did not save the buffer it applied"

# a second apply in the same process must not land in the first one's backup
# directory, where first-copy-wins would drop the state a backup exists to keep
{ cat "$SB/gui-buffer.edn"; echo ";; twice"; } > "$SB/gui-buffer2.edn"
body "$SB/gui-buffer2.edn" '{"confirm": true}' > "$SB/gui-apply2.json"
gapi /api/apply "$SB/gui-apply2.json" > /dev/null
[ "$(ls -d "$SB"/.config/agentctl/backups/gui-* | wc -l)" -ge 2 ] \
  || fail "two gui applies shared one backup directory"

set +e
run apply -f "$SB/gui.edn" -t pi > "$SB/plan16.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan16.txt"; fail "the cli sees drift after a gui apply"; }

kill "$GUI_PID" 2>/dev/null || true
trap 'rm -rf "$SB"' EXIT

echo "all agentctl e2e checks passed"
