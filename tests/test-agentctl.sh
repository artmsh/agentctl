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
mkdir -p "$SB/.gemini/antigravity-cli" "$SB/aproj" "$SB/packs/demo/skills/local-skill"
cp "$SB/packs/demo/skills/demo-skill/SKILL.md" "$SB/packs/demo/skills/local-skill/SKILL.md"
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
[ ! -L "$SB/aproj/.agents/skills/demo-skill" ] || fail "global skill duplicated in project"
[ -L "$SB/aproj/.agents/skills/local-skill" ] || { cat "$SB/apply15.txt"; fail "project skill not linked into .agents"; }
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

echo "17. gui controls edit the buffer through the server, and only the buffer"
cat > "$SB/gui-form.edn" <<'EDN'
{;; hand written, and it stays that way
 :#def {effort "high"}

 :executors
 {:claude {:model "sonnet" :thinking $effort}
  :codex {:model "gpt" :thinking $effort}}

 :mcps {:gsearch "/bin/echo --stdio"}}
EDN
before=$(md5 -q "$SB/gui.edn" 2>/dev/null || md5sum "$SB/gui.edn" | cut -d' ' -f1)

edit() { # edit <config-file> <ops-json> -> response on stdout
  python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "ops": json.loads(sys.argv[2])}))' \
    "$1" "$2" > "$SB/gui-edit.json"
  gapi /api/edit "$SB/gui-edit.json"
}

# one field, changed the way a control changes it
edit "$SB/gui-form.edn" '[{"op":"set","path":[":executors",":claude",":model"],"type":"scalar","value":"opus"}]' \
  > "$SB/gui-edit1.out"
python3 - "$SB/gui-edit1.out" <<'PY' || fail "gui edit wrong"
import json, sys
d = json.load(open(sys.argv[1]))
t = d["text"]
assert '"opus"' in t, t
assert "hand written, and it stays that way" in t, "a control edit erased the comments"
assert t.count("$effort") == 2, "a control edit baked a :#def binding into the file"
assert d["form"]["ok"] and d["ok"], d
PY

# a bare command line becomes the map it is shorthand for, in one request; the
# controls resend the expansion until they re-render, so it repeats here too and
# must not undo the field set between the two
edit "$SB/gui-form.edn" '[{"op":"expand","path":[":mcps",":gsearch"],"edn":"{:cmd \"/bin/echo --stdio\"}"},{"op":"set","path":[":mcps",":gsearch",":cwd"],"type":"scalar","value":"/tmp"},{"op":"expand","path":[":mcps",":gsearch"],"edn":"{:cmd \"/bin/echo --stdio\"}"},{"op":"set","path":[":mcps",":gsearch",":tools"],"type":"kw-set","value":[":claude"]}]' \
  > "$SB/gui-edit2.out"
python3 - "$SB/gui-edit2.out" <<'PY' || fail "gui shorthand expansion wrong"
import json, sys
t = json.load(open(sys.argv[1]))["text"]
assert ':gsearch {:cmd "/bin/echo --stdio" :cwd "/tmp" :tools [:claude]}' in t, t
PY

# add an entry, then take it away again
edit "$SB/gui-form.edn" '[{"op":"set","path":[":skills",":review"],"edn":"{:from :pack}"}]' \
  > "$SB/gui-edit3.out"
python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["text"]; assert "\n\n :skills {:review {:from :pack}}" in t, t' \
  "$SB/gui-edit3.out" || fail "a new section should be written the way the file is written"
edit "$SB/gui-form.edn" '[{"op":"unset","path":[":executors",":codex"]}]' > "$SB/gui-edit4.out"
python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["text"]; assert ":codex" not in t and ":claude" in t, t' \
  "$SB/gui-edit4.out" || fail "removing an entry removed the wrong thing"

# the form describes what the buffer declares, including the tools on offer
python3 - "$SB/gui-edit1.out" <<'PY' || fail "gui form model wrong"
import json, sys
form = json.load(open(sys.argv[1]))["form"]
secs = {s["key"]: s for s in form["sections"]}
assert set(secs) >= {":#def", ":executors", ":mcps", ":skills", ":projects"}, list(secs)
claude = [e for e in secs[":executors"]["entries"] if e["id"] == ":claude"][0]
fields = {f["key"]: f for f in claude["fields"]}
assert fields["thinking"]["value"] == "$effort", fields["thinking"]
assert fields["model"]["value"] == "opus", fields["model"]
assert [e for e in secs[":executors"]["entries"] if not e["declared"]], "undeclared tools are offered too"
PY

# a value that is not readable is a message, not a write and not a stack trace
python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "ops": [{"op":"set","path":[":mcps",":gsearch",":env"],"type":"edn","value":"{oops"}]}))' \
  "$SB/gui-form.edn" > "$SB/gui-edit-bad.json"
code=$(gstatus /api/edit "$SB/gui-edit-bad.json")
[ "$code" = 400 ] || fail "an unreadable control value should come back as 400 (got $code)"

# a binding is renamed in place: same line, same position, comment kept
edit "$SB/gui-form.edn" '[{"op":"rename","path":[":#def","effort"],"value":"level"}]' \
  > "$SB/gui-edit5.out"
python3 - "$SB/gui-edit5.out" <<'PY' || fail "gui rename wrong"
import json, sys
t = json.load(open(sys.argv[1]))["text"]
assert ' :#def {level "high"}' in t, t
assert "hand written, and it stays that way" in t, "a rename erased the comments"
PY
python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "ops": [{"op":"rename","path":[":#def","effort"],"value":""}]}))' \
  "$SB/gui-form.edn" > "$SB/gui-rename-bad.json"
code=$(gstatus /api/edit "$SB/gui-rename-bad.json")
[ "$code" = 400 ] || fail "an empty binding name should come back as 400 (got $code)"
python3 -c 'import json,sys; print(json.dumps({"text": "{:#def {a 1 b 2}}", "ops": [{"op":"rename","path":[":#def","a"],"value":"b"}]}))' \
  > "$SB/gui-rename-dup.json"
code=$(gstatus /api/edit "$SB/gui-rename-dup.json")
[ "$code" = 400 ] || fail "a rename onto an existing name should come back as 400 (got $code)"

# feature switches write :on and :off together
edit "$SB/gui-form.edn" '[{"op":"set","path":[":executors",":claude",":on"],"type":"flag-set","value":["ultracode"]},{"op":"set","path":[":executors",":claude",":off"],"type":"flag-set","value":["auto-compact"]}]' \
  > "$SB/gui-edit6.out"
python3 - "$SB/gui-edit6.out" <<'PY' || fail "gui feature switches wrong"
import json, sys
d = json.load(open(sys.argv[1]))
t = d["text"]
assert ":on #{:ultracode}" in t, t
assert ":off #{:auto-compact}" in t, t
claude = [e for e in [s for s in d["form"]["sections"] if s["key"] == ":executors"][0]["entries"]
          if e["id"] == ":claude"][0]
flags = [f for f in claude["fields"] if f["key"] == "features"][0]
state = {o["key"]: o["state"] for o in flags["options"]}
assert state["ultracode"] == "on" and state["auto-compact"] == "off", state
assert state["skip-auto"] == "", "unstated is not off"
assert not claude["extra"], claude["extra"]
PY

# a pack on disk lists its skills as switches
mkdir -p "$SB/pack/skills/review" "$SB/pack/skills/triage"
echo "# review" > "$SB/pack/skills/review/SKILL.md"
echo "# triage" > "$SB/pack/skills/triage/SKILL.md"
cat > "$SB/gui-pack.edn" <<EDN
{:skill-packs {:kit {:uri "$SB/pack" :type :file}}
 :skills {:review {:from :kit}}}
EDN
edit "$SB/gui-pack.edn" '[{"op":"set","path":[":skills",":triage"],"edn":"{:from :kit}"}]' \
  > "$SB/gui-edit7.out"
python3 - "$SB/gui-edit7.out" <<'PY' || fail "gui pack skill switches wrong"
import json, sys
d = json.load(open(sys.argv[1]))
assert ":triage {:from :kit}" in d["text"], d["text"]
group = [s for s in d["form"]["sections"] if s["key"] == ":skills"][0]["groups"][0]
assert group["id"] == ":kit", group
assert {o["label"]: o["on"] for o in group["options"]} == {"review": True, "triage": True}, group
PY

after=$(md5 -q "$SB/gui.edn" 2>/dev/null || md5sum "$SB/gui.edn" | cut -d' ' -f1)
[ "$before" = "$after" ] || fail "editing through the controls wrote to the config file"

kill "$GUI_PID" 2>/dev/null || true
trap 'rm -rf "$SB"' EXIT

echo "18. an unfiltered apply! refreshes a hook's stored value even when it plans nothing"
# Regression for the sync-state!/scoped? wiring: a *scoped* run (-t/-k/-p)
# that plans nothing for a hook must not overwrite its recorded value (the
# element could still be unwritten), but an *unfiltered* run that plans
# nothing is proof the live element already matches the declaration, and
# must still refresh the manifest — otherwise a later declaration change
# computes `old` from a stale value nothing on disk holds, and the real
# element is orphaned instead of replaced. `main.clj`/`gui.clj` must pass
# `(when (core/scoped? opts) done)`, not a bare `#{}`, at both call sites.
cat > "$SB/hooks.edn" <<'EDN'
{:executors {:claude {:hooks {:probe {:event :PreToolUse :matcher "*" :command "v1.sh"}}}}}
EDN
run apply! -f "$SB/hooks.edn" -y > /dev/null
grep -q '"v1.sh"' "$SB/.claude/settings.json" || fail "hook not written"
grep -q 'v1.sh' "$SB/.config/agentctl/state.edn" || fail "hook value not recorded in state"

# hand-edit the live file to what a future declaration will say — as if a
# person, not agentctl, made this exact change
python3 - "$SB/.claude/settings.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p))
d["hooks"]["PreToolUse"][0]["hooks"][0]["command"] = "v2.sh"
json.dump(d, open(p, "w"))
PY
sed -i.bak 's/v1\.sh/v2.sh/' "$SB/hooks.edn" && rm -f "$SB/hooks.edn.bak"

run apply! -f "$SB/hooks.edn" -y > "$SB/hooks-apply.txt"
grep -q 'no changes' "$SB/hooks-apply.txt" || { cat "$SB/hooks-apply.txt"; fail "expected the hand-edit to already match — no op to plan"; }
grep -q 'v2.sh' "$SB/.config/agentctl/state.edn" || { cat "$SB/.config/agentctl/state.edn"; fail "unfiltered apply! did not refresh the hook's stored value"; }

echo "19. promoting wrap-up to global previews and unlinks every project copy"
mkdir -p "$SB/wrap-pack/skills/wrap-up"
printf -- '---\nname: wrap-up\ndescription: Wrap up the session.\n---\n' > "$SB/wrap-pack/skills/wrap-up/SKILL.md"
cat > "$SB/wrap.edn" <<EDN
{:skills {:wrap-up {:path "$SB/wrap-pack/skills/wrap-up" :tools [:claude]}}
 :projects {:wrap-a {:path "$SB/wrap-a" :executors #{:claude} :skills [:wrap-up]}
            :wrap-b {:path "$SB/wrap-b" :executors #{:claude} :skills [:wrap-up]}}}
EDN
run apply! -f "$SB/wrap.edn" -t claude -k skills -y > /dev/null
[ -L "$SB/wrap-a/.claude/skills/wrap-up" ] || fail "project wrap-up was not installed"
sed 's/:tools \[:claude\]/:scope :global :tools [:claude]/' "$SB/wrap.edn" > "$SB/wrap-global.edn"
set +e
run apply -f "$SB/wrap-global.edn" -t claude -k skills > "$SB/wrap-plan.txt"; code=$?
set -e
[ "$code" = 2 ] || fail "global promotion should report drift"
for id in wrap-a wrap-b; do
  grep -q -- "- projects/$id skills/wrap-up" "$SB/wrap-plan.txt" || { cat "$SB/wrap-plan.txt"; fail "missing project deletion section"; }
  grep -q "unlink.*$id/.claude/skills/wrap-up" "$SB/wrap-plan.txt" || fail "missing unlink preview"
  [ -L "$SB/$id/.claude/skills/wrap-up" ] || fail "dry run removed a project link"
done
run apply! -f "$SB/wrap-global.edn" -t claude -k skills -y > /dev/null
[ -L "$SB/.claude/skills/wrap-up" ] || fail "global wrap-up was not installed"
for id in wrap-a wrap-b; do
  [ ! -L "$SB/$id/.claude/skills/wrap-up" ] || fail "project wrap-up remains"
done
[ -f "$SB/wrap-pack/skills/wrap-up/SKILL.md" ] || fail "unlink removed the source"
run apply -f "$SB/wrap-global.edn" -t claude -k skills > /dev/null

echo "20. the working directory scopes apply, and --all opts back out"
# two git packs: one the projects draw their skills from, one only a user-wide
# skill uses. A project-scoped run must clone the first and leave the second.
for pack in ours theirs; do
  mkdir -p "$SB/$pack/skills"
  git -C "$SB/$pack" init -q
done
for sk in only-a only-b; do
  mkdir -p "$SB/ours/skills/$sk"
  printf -- '---\nname: %s\ndescription: Demo.\n---\n' "$sk" > "$SB/ours/skills/$sk/SKILL.md"
done
mkdir -p "$SB/theirs/skills/user-wide"
printf -- '---\nname: user-wide\ndescription: Demo.\n---\n' > "$SB/theirs/skills/user-wide/SKILL.md"
for pack in ours theirs; do
  git -C "$SB/$pack" -c user.email=t@e -c user.name=t add -A
  git -C "$SB/$pack" -c user.email=t@e -c user.name=t commit -qm init
done

mkdir -p "$SB/ws/a/src" "$SB/ws/b"
cat > "$SB/cwd.edn" <<EDN
{:skill-packs {:ours   {:uri "file://$SB/ours" :type :git :dir "skills"}
               :theirs {:uri "file://$SB/theirs" :type :git :dir "skills"}}
 :skills {:only-a {:from :ours :tools [:claude]}
          :only-b {:from :ours :tools [:claude]}
          :user-wide {:from :theirs :scope :global :tools [:claude]}}
 :projects {:a {:path "$SB/ws/a" :executors #{:claude} :skills [:only-a]}
            :b {:path "$SB/ws/b" :executors #{:claude} :skills [:only-b]}}}
EDN

# a dry apply exits 2 on drift, which set -e would take as the script failing
plan_in() { (cd "$1" && run apply -f "$SB/cwd.edn" "${@:2}" || true); }

# the whole point of letting pack ops through a project scope: clone, re-plan,
# link — in one run, without touching anything the project did not ask for
(cd "$SB/ws/a/src" && run apply! -f "$SB/cwd.edn" -y) > "$SB/cwd-apply.txt"
grep -q 'scope: project a' "$SB/cwd-apply.txt" || { cat "$SB/cwd-apply.txt"; fail "a subdirectory of a project did not scope to it"; }
[ -d "$SB/.agents/skill-packs/ours/.git" ] || { cat "$SB/cwd-apply.txt"; fail "project-scoped apply! did not clone the pack its skill needs"; }
[ -L "$SB/ws/a/.claude/skills/only-a" ] || { cat "$SB/cwd-apply.txt"; fail "project-scoped apply! did not link its skill"; }
[ ! -e "$SB/.agents/skill-packs/theirs" ] || fail "project-scoped apply! cloned a pack nothing in scope needed"
[ ! -e "$SB/ws/b/.claude/skills/only-b" ] || fail "project-scoped apply! touched the other project"
[ ! -e "$SB/.claude/skills/user-wide" ] || fail "project-scoped apply! installed a user-wide skill"

set +e
plan_in "$SB/ws/a" > "$SB/cwd-again.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/cwd-again.txt"; fail "a project-scoped apply! left drift in its own scope"; }

plan_in "$SB/ws/b" > "$SB/cwd-b.txt"
grep -q 'scope: project b' "$SB/cwd-b.txt" || { cat "$SB/cwd-b.txt"; fail "standing in a project did not scope to it"; }
grep -q 'projects/b skills/only-b' "$SB/cwd-b.txt" || { cat "$SB/cwd-b.txt"; fail "the project's own skill was not planned"; }
grep -q 'only-a' "$SB/cwd-b.txt" && fail "the other project leaked into a project-scoped run"
grep -q 'user-wide' "$SB/cwd-b.txt" && fail "a user-wide skill leaked into a project-scoped run"
grep -q 'skill-packs/theirs' "$SB/cwd-b.txt" && fail "a pack no selected project needs was planned"

plan_in "$SB/ws" > "$SB/cwd-ws.txt"
grep -q 'scope: a, b' "$SB/cwd-ws.txt" || { cat "$SB/cwd-ws.txt"; fail "the workspace root did not scope to its projects"; }
grep -q 'projects/b skills/only-b' "$SB/cwd-ws.txt" || fail "workspace run missed project b"
grep -q 'user-wide' "$SB/cwd-ws.txt" && fail "a user-wide skill leaked into a workspace run"

plan_in "$SB/ws/a" --all > "$SB/cwd-all.txt"
grep -q '^scope:' "$SB/cwd-all.txt" && fail "--all must not narrow anything"
grep -q 'skill-packs/theirs' "$SB/cwd-all.txt" || { cat "$SB/cwd-all.txt"; fail "--all did not plan the pack behind the user-wide skill"; }

plan_in "$SB" > "$SB/cwd-out.txt"
grep -q '^scope:' "$SB/cwd-out.txt" && fail "a directory above the workspace is not a scope"

echo "all agentctl e2e checks passed"
