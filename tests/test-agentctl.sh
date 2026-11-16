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

# The harness exports DSH_HOME/DSH_AGENTS_HOME into every child process, which
# would point the DeepSeek Harness adapter at the developer's real home instead
# of the scratch one. Unset them; the dsh tests set what they need themselves.
unset DSH_HOME DSH_AGENTS_HOME
run() { "$ROOT/agentctl" "$@"; }

# agentctl skips a tool whose CLI is not installed, so without these the suite
# would quietly plan nothing on a machine that has no agent installed — which
# is every CI runner. The adapters exercised here write files directly; the
# stubs only have to exist and succeed.
mkdir -p "$SB/bin"
for cli in claude codex pi omp llm agy kimi; do
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
grep -qE '^.{0,12}\+ skill \[agents: omp, pi\] .*demo-skill' "$SB/plan1.txt" || fail "skill op missing from plan"
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
grep -qE -- '^.{0,12}- skill .*demo-skill' "$SB/plan3.txt" || { cat "$SB/plan3.txt"; fail "prune not planned"; }
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
# the pack's clone line stands in for "not fetched yet", under the project it lands in
grep -A1 'PROJECT(.*pproj)' "$SB/plan11.txt" | grep -q '+ skill-pack \[agents: claude-code\]' || { cat "$SB/plan11.txt"; fail "unfetched pack not reported"; }
run apply! -f "$SB/pskills.edn" -y > "$SB/apply11.txt"
[ -d "$SB/.agents/skill-packs/fresh2/.git" ] || { cat "$SB/apply11.txt"; fail "project pack not cloned"; }
# a skill already on disk is linked from the clone, not copied by the skills CLI
link=$(readlink "$SB/pproj/.claude/skills/fresh-skill")
[ "$link" = "$SB/.agents/skill-packs/fresh2/skills/fresh-skill" ] || { cat "$SB/apply11.txt"; fail "stale project skill link not repointed at the clone (-> $link)"; }
[ ! -e "$SB/pproj/.agents/skills/fresh-skill" ] || fail "claude-only install wrote the shared .agents/skills copy"
[ ! -e "$SB/pproj/skills-lock.json" ] || fail "a linked skill was handed to the skills CLI"
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

echo "14b. deleting whole projects prunes owned MCPs in both placements"
mkdir -p "$SB/gone-local" "$SB/gone-repo"
cat > "$SB/gone-with.edn" <<EDN
{:mcps {:owned-local {:cmd "/bin/echo local" :tools [:claude] :scope :local}
        :owned-repo {:cmd "/bin/echo repo" :tools [:claude] :scope :project}}
 :projects {:gone-local {:path "$SB/gone-local" :executors {:claude {}} :mcp [:owned-local]}
            :gone-repo {:path "$SB/gone-repo" :executors {:claude {}} :mcp [:owned-repo]}}}
EDN
printf '{}\n' > "$SB/gone-without.edn"
run apply! -f "$SB/gone-with.edn" -y > /dev/null
python3 - "$SB/.claude.json" "$SB/gone-local" "$SB/gone-repo/.mcp.json" <<'PY' || fail "project MCP fixture setup failed"
import json, sys
runtime, local, repo = sys.argv[1:]
d = json.load(open(runtime))
servers = d["projects"][local]["mcpServers"]
assert "owned-local" in servers
servers["handmade"] = {"command": "/bin/echo"}
json.dump(d, open(runtime, "w"))
d = json.load(open(repo))
assert "owned-repo" in d["mcpServers"]
d["mcpServers"]["handmade"] = {"command": "/bin/echo"}
json.dump(d, open(repo, "w"))
PY
set +e
run apply -f "$SB/gone-without.edn" -t claude > "$SB/plan14b.txt"; code=$?
set -e
[ "$code" = 2 ] || { cat "$SB/plan14b.txt"; fail "project deletion reported no drift"; }
grep -q 'mcps/owned-local' "$SB/plan14b.txt" || fail "local MCP prune missing"
grep -q 'mcps/owned-repo' "$SB/plan14b.txt" || fail "repo MCP prune missing"
run apply! -f "$SB/gone-without.edn" -t claude -y > /dev/null
python3 - "$SB/.claude.json" "$SB/gone-local" "$SB/gone-repo/.mcp.json" <<'PY' || fail "project deletion did not preserve hand-made siblings"
import json, sys
runtime, local, repo = sys.argv[1:]
local_servers = json.load(open(runtime))["projects"][local]["mcpServers"]
repo_servers = json.load(open(repo))["mcpServers"]
assert "owned-local" not in local_servers and "handmade" in local_servers
assert "owned-repo" not in repo_servers and "handmade" in repo_servers
PY

echo "15. antigravity converges, and trust is unioned rather than overwritten"
mkdir -p "$SB/.gemini/antigravity-cli" "$SB/aproj" "$SB/packs/demo/skills/local-skill"
# the skills CLI installs under SKILL.md's name, so this one needs its own
printf -- '---\nname: local-skill\ndescription: Local.\n---\n' > "$SB/packs/demo/skills/local-skill/SKILL.md"
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
[ ! -e "$SB/aproj/.agents/skills/demo-skill" ] || fail "global skill duplicated in project"
[ -f "$SB/aproj/.agents/skills/local-skill/SKILL.md" ] || { cat "$SB/apply15.txt"; fail "project skill not installed into .agents"; }
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

echo "15b. kimi converges, and its own spellings are written the way kimi reads them"
mkdir -p "$SB/kproj" "$SB/packs/demo/skills/local-skill"
printf -- '---\nname: local-skill\ndescription: Local.\n---\n' > "$SB/packs/demo/skills/local-skill/SKILL.md"
cat > "$SB/kimi.edn" <<EDN
{:executors {:kimi {:model "kimi-k3"}}
 :mcps {:kdocs {:command "/bin/echo" :args ["hi"] :tools [:kimi]}
        :khttp {:url "https://example.com/mcp" :headers {"Authorization" "Bearer y"}
                 :tools [:kimi]}}
 :extra-providers {:sw {:url "https://switch.yard/v1" :key "\$SW_KEY"
                        :api "openai-completions" :tools [:kimi]
                        :models [{:id "kimi-k3" :reasoning true :context-window 1000000
                                  :max-tokens 131072 :input [:text :image]}]}}
 :skill-packs {:demo15b {:uri "file://$SB/packs/demo" :dir "skills"}}
 :skills {:demo-skill {:from :demo15b :tools [:kimi] :scope :global}}
 :memory {:shared {:from "$SB/brain/AGENTS.md" :tools [:kimi]}}
 :projects {:kproj {:path "$SB/kproj" :executors {:kimi {}} :skills [:demo15b]}}}
EDN
run apply! -f "$SB/kimi.edn" -t kimi -y > "$SB/apply15c.txt"
[ -L "$SB/.kimi-code/skills/demo-skill" ] || { cat "$SB/apply15c.txt"; fail "kimi skill not linked"; }
[ -L "$SB/.kimi-code/AGENTS.md" ] || { cat "$SB/apply15c.txt"; fail "kimi memory not linked"; }
[ -f "$SB/kproj/.agents/skills/local-skill/SKILL.md" ] \
  || { cat "$SB/apply15c.txt"; fail "project skill not installed into .agents"; }
python3 - "$SB/.kimi-code/config.toml" <<'PY' || fail "kimi config.toml wrong"
import sys, tomllib
d = tomllib.load(open(sys.argv[1], "rb"))
assert d["default_model"] == "kimi-k3", d
p = d["providers"]["sw"]
assert p["type"] == "openai", p
assert p["baseUrl"] == "https://switch.yard/v1", p
# the key is an environment variable: kimi gets its name, never its value
assert p["apiKeyEnv"] == "SW_KEY", p
assert "apiKey" not in p, p
m = d["models"]["kimi-k3"]
assert m["provider"] == "sw" and m["model"] == "kimi-k3", m
assert m["maxContextSize"] == 1000000 and m["maxOutputSize"] == 131072, m
assert m["capabilities"] == ["thinking", "image_in"], m
PY
python3 - "$SB/.kimi-code/mcp.json" <<'PY' || fail "kimi mcp.json wrong"
import json, sys
d = json.load(open(sys.argv[1]))["mcpServers"]
assert d["kdocs"]["transport"] == "stdio", d
assert d["kdocs"]["command"] == "/bin/echo" and d["kdocs"]["args"] == ["hi"], d
assert d["khttp"]["transport"] == "http", d
assert d["khttp"]["url"] == "https://example.com/mcp", d
assert d["khttp"]["headers"] == {"Authorization": "Bearer y"}, d
PY
set +e
run apply -f "$SB/kimi.edn" -t kimi > "$SB/plan15c.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan15c.txt"; fail "kimi left drift behind"; }

echo "15c. deepseek harness converges: user-wide skills and memory, project skills in the shared directory"
mkdir -p "$SB/dshproj/.git" "$SB/.dsh" "$SB/dshpacks/skills/dsh-skill" "$SB/dshpacks/skills/proj-skill"
printf -- '---\nname: dsh-skill\ndescription: D.\n---\n' > "$SB/dshpacks/skills/dsh-skill/SKILL.md"
printf -- '---\nname: proj-skill\ndescription: P.\n---\n' > "$SB/dshpacks/skills/proj-skill/SKILL.md"
cat > "$SB/dsh.edn" <<EDN
{:skill-packs {:dshpack {:uri "file://$SB/dshpacks" :dir "skills"}}
 :skills {:dsh-skill {:from :dshpack :tools [:dsh] :scope :global}
          :proj-skill {:from :dshpack :tools [:dsh]}}
 :memory {:shared {:from "$SB/brain/AGENTS.md" :tools [:dsh]}}
 :projects {:dshproj {:path "$SB/dshproj" :executors {:dsh {}} :skills [:proj-skill]}}}
EDN
run apply! -f "$SB/dsh.edn" -t dsh -y > "$SB/apply15d.txt"
[ -L "$SB/.dsh/skills/dsh-skill" ] || { cat "$SB/apply15d.txt"; fail "dsh skill not linked"; }
[ -L "$SB/.dsh/AGENTS.md" ] || { cat "$SB/apply15d.txt"; fail "dsh memory not linked"; }
[ -L "$SB/dshproj/.agents/skills/proj-skill" ] \
  || { cat "$SB/apply15d.txt"; fail "dsh project skill not linked into the shared .agents/skills"; }
[ -e "$SB/dshproj/.dsh/skills" ] && fail "dsh's own project skills directory must be left alone"
set +e
run apply -f "$SB/dsh.edn" -t dsh > "$SB/plan15d.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/plan15d.txt"; fail "dsh left drift behind"; }
run validate -f "$SB/dsh.edn" > "$SB/validate15d.txt" || { cat "$SB/validate15d.txt"; fail "validate rejected the dsh config"; }

echo "15d. DSH_HOME moves the Harness home; a dropped skill is pruned"
set +e
DSH_HOME="$SB/alt-dsh" run apply -f "$SB/dsh.edn" -t dsh -k skills --json > "$SB/plan15e.json"; code=$?
set -e
[ "$code" = 2 ] || { cat "$SB/plan15e.json"; fail "expected drift for a fresh DSH_HOME"; }
grep -qF "$SB/alt-dsh/skills/dsh-skill" "$SB/plan15e.json" \
  || { cat "$SB/plan15e.json"; fail "DSH_HOME was not honored"; }
[ -e "$SB/alt-dsh" ] && fail "a dry apply must not create the Harness home"
cat > "$SB/dsh-prune.edn" <<EDN
{:skill-packs {:dshpack {:uri "file://$SB/dshpacks" :dir "skills"}}
 :skills {:proj-skill {:from :dshpack :tools [:dsh]}}
 :memory {:shared {:from "$SB/brain/AGENTS.md" :tools [:dsh]}}
 :projects {:dshproj {:path "$SB/dshproj" :executors {:dsh {}} :skills [:proj-skill]}}}
EDN
run apply! -f "$SB/dsh-prune.edn" -t dsh -y > "$SB/apply15e.txt"
[ -e "$SB/.dsh/skills/dsh-skill" ] && { cat "$SB/apply15e.txt"; fail "pruned dsh skill still linked"; }
[ -L "$SB/dshproj/.agents/skills/proj-skill" ] || { cat "$SB/apply15e.txt"; fail "dropping a global skill removed the project's own"; }

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
run apply -f "$SB/hooks.edn" -t claude -v --show-noop > "$SB/hooks-noop.txt"
grep -q '= hooks/probe' "$SB/hooks-noop.txt" || { cat "$SB/hooks-noop.txt"; fail "fully converged hook not shown"; }
grep -q '1 unchanged' "$SB/hooks-noop.txt" || { cat "$SB/hooks-noop.txt"; fail "converged hook not counted"; }

echo "19. promoting wrap-up to global previews and removes every project copy"
mkdir -p "$SB/wrap-pack/skills/wrap-up"
printf -- '---\nname: wrap-up\ndescription: Wrap up the session.\n---\n' > "$SB/wrap-pack/skills/wrap-up/SKILL.md"
cat > "$SB/wrap.edn" <<EDN
{:skills {:wrap-up {:path "$SB/wrap-pack/skills/wrap-up" :tools [:claude]}}
 :projects {:wrap-a {:path "$SB/wrap-a" :executors #{:claude} :skills [:wrap-up]}
            :wrap-b {:path "$SB/wrap-b" :executors #{:claude} :skills [:wrap-up]}}}
EDN
run apply! -f "$SB/wrap.edn" -t claude -k skills -y > /dev/null
[ -d "$SB/wrap-a/.claude/skills/wrap-up" ] || fail "project wrap-up was not installed"
sed 's/:tools \[:claude\]/:scope :global :tools [:claude]/' "$SB/wrap.edn" > "$SB/wrap-global.edn"
set +e
run apply -f "$SB/wrap-global.edn" -t claude -k skills > "$SB/wrap-plan.txt"; code=$?
set -e
[ "$code" = 2 ] || fail "global promotion should report drift"
for id in wrap-a wrap-b; do
  grep -q "PROJECT(.*$id)" "$SB/wrap-plan.txt" || { cat "$SB/wrap-plan.txt"; fail "missing project deletion section"; }
  grep -A2 "PROJECT(.*$id)" "$SB/wrap-plan.txt" | grep -q "^exec: 'unlink .*/$id/.claude/skills/wrap-up'" || { cat "$SB/wrap-plan.txt"; fail "missing unlink preview"; }
  [ -d "$SB/$id/.claude/skills/wrap-up" ] || fail "dry run removed a project copy"
done
run apply! -f "$SB/wrap-global.edn" -t claude -k skills -y > /dev/null
[ -L "$SB/.claude/skills/wrap-up" ] || fail "global wrap-up was not installed"
for id in wrap-a wrap-b; do
  [ ! -e "$SB/$id/.claude/skills/wrap-up" ] || fail "project wrap-up remains"
  [ ! -e "$SB/$id/.agents/skills/wrap-up" ] || fail "project wrap-up copy remains"
  grep -qs '"wrap-up"' "$SB/$id/skills-lock.json" && fail "wrap-up still in the project's skills-lock.json"
done
[ -f "$SB/wrap-pack/skills/wrap-up/SKILL.md" ] || fail "removal took the source"
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
[ -d "$SB/ws/a/.claude/skills/only-a" ] || { cat "$SB/cwd-apply.txt"; fail "project-scoped apply! did not install its skill"; }
[ ! -e "$SB/.agents/skill-packs/theirs" ] || fail "project-scoped apply! cloned a pack nothing in scope needed"
[ ! -e "$SB/ws/b/.claude/skills/only-b" ] || fail "project-scoped apply! touched the other project"
[ ! -e "$SB/.claude/skills/user-wide" ] || fail "project-scoped apply! installed a user-wide skill"

set +e
plan_in "$SB/ws/a" > "$SB/cwd-again.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/cwd-again.txt"; fail "a project-scoped apply! left drift in its own scope"; }

plan_in "$SB/ws/b" > "$SB/cwd-b.txt"
grep -q 'scope: project b' "$SB/cwd-b.txt" || { cat "$SB/cwd-b.txt"; fail "standing in a project did not scope to it"; }
grep -q 'PROJECT(.*ws/b)' "$SB/cwd-b.txt" && grep -q 'skill .*only-b' "$SB/cwd-b.txt" || { cat "$SB/cwd-b.txt"; fail "the project's own skill was not planned"; }
grep -q 'only-a' "$SB/cwd-b.txt" && fail "the other project leaked into a project-scoped run"
grep -q 'user-wide' "$SB/cwd-b.txt" && fail "a user-wide skill leaked into a project-scoped run"
grep -q 'skill-pack .*/theirs' "$SB/cwd-b.txt" && fail "a pack no selected project needs was planned"

plan_in "$SB/ws" > "$SB/cwd-ws.txt"
grep -q 'scope: a, b' "$SB/cwd-ws.txt" || { cat "$SB/cwd-ws.txt"; fail "the workspace root did not scope to its projects"; }
grep -q 'PROJECT(.*ws/b)' "$SB/cwd-ws.txt" || fail "workspace run missed project b"
grep -q 'user-wide' "$SB/cwd-ws.txt" && fail "a user-wide skill leaked into a workspace run"

plan_in "$SB/ws/a" --all > "$SB/cwd-all.txt"
grep -q '^scope:' "$SB/cwd-all.txt" && fail "--all must not narrow anything"
grep -q '+ skill-pack .*/theirs' "$SB/cwd-all.txt" || { cat "$SB/cwd-all.txt"; fail "--all did not plan the pack behind the user-wide skill"; }

plan_in "$SB" > "$SB/cwd-out.txt"
grep -q '^scope:' "$SB/cwd-out.txt" && fail "a directory above the workspace is not a scope"

# -p names a HOME-relative path. Every spelling of it has to select the project:
# a path that silently matches nothing plans nothing, and reads as "converged".
mkdir -p "$SB/ws2/p/.git" "$SB/ws2/q/.git"
cat > "$SB/dash-p.edn" <<'EDN'
{:mcps [{:id :only-p :cmd "/bin/echo p" :in "ws2/p" :tools [:claude]}
        {:id :only-q :cmd "/bin/echo q" :in "ws2/q" :tools [:claude]}]}
EDN
for spelling in "ws2/p" "$SB/ws2/p" '~/ws2/p' "ws2/p/"; do
  (cd "$SB" && run apply -f "$SB/dash-p.edn" -p "$spelling" || true) > "$SB/dash-p.txt"
  grep -q 'mcps/only-p' "$SB/dash-p.txt" || { cat "$SB/dash-p.txt"; fail "-p $spelling did not select the project"; }
  grep -q 'only-q' "$SB/dash-p.txt" && fail "-p $spelling leaked another project"
done
(cd "$SB" && run apply -f "$SB/dash-p.edn" -p ws2 || true) > "$SB/dash-p.txt"
grep -q 'only-p' "$SB/dash-p.txt" && grep -q 'only-q' "$SB/dash-p.txt" || { cat "$SB/dash-p.txt"; fail "-p on a tree did not select the projects beneath it"; }
(cd "$SB" && run apply -f "$SB/dash-p.edn" -p ws2/nope || true) > "$SB/dash-p.txt"
grep -q -- '-p ws2/nope — no declared project there' "$SB/dash-p.txt" || { cat "$SB/dash-p.txt"; fail "a -p that selects nothing was not reported"; }

# one server, placed per tool: user-wide for pi, inside one repository for claude
mkdir -p "$SB/ws3/r/.git"
claude_has() { python3 - "$SB/.claude.json" "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
want = sys.argv[2]
local = any(p.endswith("/ws3/r") and want in v.get("mcpServers", {}) for p, v in d.get("projects", {}).items())
assert want not in d.get("mcpServers", {}), "claude got the server user-wide"
sys.exit(0 if local else 1)
PY
}
cat > "$SB/split.edn" <<'EDN'
{:mcps [{:id :split :cmd "/bin/echo split" :in :all :acli [:pi]}
        {:id :split :in "ws3/r" :acli [:claude]}]}
EDN
(cd "$SB" && run apply! -f "$SB/split.edn" --all -y) > "$SB/split.txt"
grep -q '"split"' "$SB/.pi/agent/mcp.json" || { cat "$SB/split.txt"; fail "per-tool placement: pi did not get the server user-wide"; }
claude_has split || { cat "$SB/split.txt"; fail "per-tool placement: claude did not get the server in the project"; }
set +e
(cd "$SB" && run apply -f "$SB/split.edn" --all) > "$SB/split-again.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/split-again.txt"; fail "per-tool placement did not converge"; }
# dropping the project placement prunes claude's entry and leaves pi's alone
cat > "$SB/split.edn" <<'EDN'
{:mcps [{:id :split :cmd "/bin/echo split" :in :all :acli [:pi]}]}
EDN
(cd "$SB" && run apply! -f "$SB/split.edn" --all -y) > "$SB/split.txt"
claude_has split && { cat "$SB/split.txt"; fail "per-tool placement: dropped project placement was not pruned"; }
grep -q '"split"' "$SB/.pi/agent/mcp.json" || { cat "$SB/split.txt"; fail "per-tool placement: pruning claude took pi's server"; }

# malformed refs must fail before apply! changes any owned files, and the
# diagnostic must not echo the ref's potentially secret raw spelling
cp "$SB/.config/agentctl/state.edn" "$SB/state-before-invalid-ref.edn"
cp "$SB/.pi/agent/mcp.json" "$SB/mcp-before-invalid-ref.json"
for ref_kind in env unsupported; do
  cat > "$SB/invalid-ref.edn" <<EDN
{:mcps {:broken {:command "/bin/echo" :tools [:pi]
                 :env {"TOKEN" {:ref/kind :$ref_kind :ref/raw "private-ref-fixture"}}}}}
EDN
  set +e
  run apply! -f "$SB/invalid-ref.edn" --all -y > "$SB/invalid-ref.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] || fail "malformed reference was not rejected"
  grep -q 'invalid reference' "$SB/invalid-ref.txt" || fail "malformed reference diagnostic missing"
  grep -q 'private-ref-fixture' "$SB/invalid-ref.txt" && fail "malformed reference diagnostic leaked its raw value"
  cmp -s "$SB/state-before-invalid-ref.edn" "$SB/.config/agentctl/state.edn" || fail "invalid reference changed ownership"
  cmp -s "$SB/mcp-before-invalid-ref.json" "$SB/.pi/agent/mcp.json" || fail "invalid reference changed MCPs"
done

echo "21. --only and --user narrow apply! without pruning what lies outside them"
# a home of its own: what this section owns, and nothing else, is the point
SEL="$SB/sel"
mkdir -p "$SEL/proj" "$SEL/gp/skills/g"
git -C "$SEL/proj" init -q
for s in a b c d e; do
  mkdir -p "$SEL/packs/p/skills/$s"
  printf -- '---\nname: %s\ndescription: Demo.\n---\n' "$s" > "$SEL/packs/p/skills/$s/SKILL.md"
done
printf -- '---\nname: g\ndescription: Demo.\n---\n' > "$SEL/gp/skills/g/SKILL.md"
git -C "$SEL/gp" init -q
git -C "$SEL/gp" -c user.email=t@e -c user.name=t add -A
git -C "$SEL/gp" -c user.email=t@e -c user.name=t commit -qm init
sel() { AGENTCTL_HOME="$SEL" run "$@"; }
sel_plan() { (cd "$SEL" && sel apply "$@" || true); }
owned() { sel state | grep -qE "$1"; }
pack="{:skill-packs {[:uri \"file://$SEL/packs/p\"] {:alias p :dir \"skills\"}}"
cat > "$SEL/full.edn" <<EDN
$pack
 :skills {[p "a"] {:in :all :acli [:claude]}
          [p "b"] {:in "proj" :acli [:claude]}}
 :mcps [{:id :keep :cmd "/bin/echo keep" :in :all :acli [:pi]}]}
EDN
(cd "$SEL" && sel apply! -f "$SEL/full.edn" --all -y) > "$SEL/own.txt"
[ -L "$SEL/.claude/skills/a" ] && [ -L "$SEL/proj/.claude/skills/b" ] || { cat "$SEL/own.txt"; fail "setup: skills not installed"; }
grep -q '"keep"' "$SEL/.pi/agent/mcp.json" || { cat "$SEL/own.txt"; fail "setup: pi MCP not written"; }

# the hazard: a manifest holding only the new entity owns nothing else, so an
# unscoped run removes everything else agentctl owns
cat > "$SEL/min.edn" <<EDN
$pack
 :skills {[p "c"] {:in :all :acli [:claude]}}}
EDN
sel_plan -f "$SEL/min.edn" --all > "$SEL/min-all.txt"
grep -q -- '- skill .*/proj/.claude/skills/b' "$SEL/min-all.txt" || { cat "$SEL/min-all.txt"; fail "a minimal manifest no longer plans the prune this section guards against"; }

sel_plan -f "$SEL/min.edn" --only skills/c > "$SEL/only-plan.txt"
grep -q '+ skill .*skills/c' "$SEL/only-plan.txt" || { cat "$SEL/only-plan.txt"; fail "--only did not plan its entity"; }
grep -qE '^.{0,12}- ' "$SEL/only-plan.txt" && { cat "$SEL/only-plan.txt"; fail "--only planned a removal outside its selection"; }
(cd "$SEL" && sel apply! -f "$SEL/min.edn" --only skills/c -y) > "$SEL/only-apply.txt"
[ -L "$SEL/.claude/skills/c" ] || { cat "$SEL/only-apply.txt"; fail "--only did not install its entity"; }
[ -L "$SEL/.claude/skills/a" ] && [ -L "$SEL/proj/.claude/skills/b" ] || fail "--only removed a skill outside its selection"
grep -q '"keep"' "$SEL/.pi/agent/mcp.json" || fail "--only removed an MCP server outside its selection"
for id in 'skills +a$' 'skills +c$' 'skills +target-[0-9a-f]+/b$' 'mcps +keep$'; do
  owned "$id" || { sel state; fail "--only dropped ownership of $id"; }
done
# still owned, not orphaned: the unscoped plan still knows to remove them
sel_plan -f "$SEL/min.edn" --all > "$SEL/min-all2.txt"
grep -q -- '- skill .*/proj/.claude/skills/b' "$SEL/min-all2.txt" || { cat "$SEL/min-all2.txt"; fail "b is no longer owned after --only"; }

# --user: drop b and add d; the project's prune waits for a run that plans it
cat > "$SEL/user.edn" <<EDN
$pack
 :skills {[p "a"] {:in :all :acli [:claude]}
          [p "c"] {:in :all :acli [:claude]}
          [p "d"] {:in :all :acli [:claude]}}
 :mcps [{:id :keep :cmd "/bin/echo keep" :in :all :acli [:pi]}]}
EDN
(cd "$SEL" && sel apply -f "$SEL/user.edn" --user --json || true) > "$SEL/user.json"
python3 - "$SEL/user.json" <<'PY' || fail "--user planned an op inside a project"
import json, sys
ops = json.load(open(sys.argv[1]))["ops"]
assert any(o["kind"] == "skills" and o["id"] == "d" for o in ops), "d not planned"
assert not [o for o in ops if o.get("project") or o.get("project-path")], "project op under --user"
PY
(cd "$SEL/proj" && sel apply! -f "$SEL/user.edn" --user -y) > "$SEL/user-apply.txt"
grep -q '^scope:' "$SEL/user-apply.txt" && { cat "$SEL/user-apply.txt"; fail "the working directory narrowed a --user run"; }
[ -L "$SEL/.claude/skills/d" ] || { cat "$SEL/user-apply.txt"; fail "--user did not install a user-wide skill"; }
[ -L "$SEL/proj/.claude/skills/b" ] || fail "--user removed a project skill"
owned 'skills +target-[0-9a-f]+/b$' || { sel state; fail "--user dropped ownership of a project skill"; }
sel_plan -f "$SEL/user.edn" --all > "$SEL/user-all.txt"
grep -q -- '- skill .*/proj/.claude/skills/b' "$SEL/user-all.txt" || { cat "$SEL/user-all.txt"; fail "b is no longer owned after --user"; }

# --only outranks the working directory: a user-wide entity from inside a project
sed 's/\[p "d"\]/[p "e"]/' "$SEL/user.edn" > "$SEL/e.edn"
(cd "$SEL/proj" && sel apply -f "$SEL/e.edn" --only skills/e || true) > "$SEL/e.txt"
grep -q '^scope:' "$SEL/e.txt" && { cat "$SEL/e.txt"; fail "the working directory narrowed an --only run"; }
grep -q '+ skill .*skills/e' "$SEL/e.txt" || { cat "$SEL/e.txt"; fail "--only from inside a project missed a user-wide entity"; }

# a skill out of a git pack not fetched yet: --only brings the clone with it
cat > "$SEL/git.edn" <<EDN
{:skill-packs {[:uri "file://$SEL/packs/p"] {:alias p :dir "skills"}
               [:uri "file://$SEL/gp"] {:alias gp :dir "skills" :type :git}}
 :skills {[p "a"] {:in :all :acli [:claude]}
          [gp "g"] {:in :all :acli [:claude]}}}
EDN
(cd "$SEL" && sel apply! -f "$SEL/git.edn" --only skills/g -y) > "$SEL/git.txt"
[ -L "$SEL/.claude/skills/g" ] || { cat "$SEL/git.txt"; fail "--only did not fetch the pack its skill comes from"; }
[ -L "$SEL/.claude/skills/c" ] || fail "--only on a git pack removed a skill outside its selection"
set +e
(cd "$SEL" && sel apply -f "$SEL/git.edn" --only skills/g) > "$SEL/git2.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SEL/git2.txt"; fail "--only skills/g did not converge in one apply!"; }

# a selector that names nothing is an error or a warning, never a silent "converged"
set +e
sel apply -f "$SEL/git.edn" -k bogus-kind > "$SEL/k.txt" 2>&1; code=$?
set -e
[ "$code" = 1 ] && grep -q 'unknown kind' "$SEL/k.txt" && grep -q 'plugins' "$SEL/k.txt" || { cat "$SEL/k.txt"; fail "-k bogus-kind was not rejected with the valid kinds"; }
for bad in skills skill/x; do
  set +e
  sel apply -f "$SEL/git.edn" --only "$bad" > "$SEL/o.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] || { cat "$SEL/o.txt"; fail "--only $bad was not rejected"; }
done
sel_plan -f "$SEL/git.edn" --only skills/nope > "$SEL/nope.txt"
grep -q -- '--only skills/nope — nothing declared or owned' "$SEL/nope.txt" || { cat "$SEL/nope.txt"; fail "an --only naming nothing was not reported"; }

# ...and brings no pack: an unknown id must not fall back to cloning every
# pack not on disk yet
mkdir -p "$SEL/gp2/skills/h"
printf -- '---\nname: h\ndescription: Demo.\n---\n' > "$SEL/gp2/skills/h/SKILL.md"
git -C "$SEL/gp2" init -q
git -C "$SEL/gp2" -c user.email=t@e -c user.name=t add -A
git -C "$SEL/gp2" -c user.email=t@e -c user.name=t commit -qm init
cat > "$SEL/git2.edn" <<EDN
{:skill-packs {[:uri "file://$SEL/gp2"] {:alias gp2 :dir "skills" :type :git}}
 :skills {[gp2 "h"] {:in :all :acli [:claude]}}}
EDN
sel_plan -f "$SEL/git2.edn" --all --json > "$SEL/git2-all.json"
python3 -c 'import json,sys; ops=json.load(open(sys.argv[1]))["ops"]; sys.exit(0 if any(o["kind"]=="skill-packs" for o in ops) else 1)' "$SEL/git2-all.json" \
  || { cat "$SEL/git2-all.json"; fail "setup: gp2 is not planned as a clone"; }
sel_plan -f "$SEL/git2.edn" --only skills/nope --json > "$SEL/git2-nope.json"
python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1]))["ops"] == [] else 1)' "$SEL/git2-nope.json" \
  || { cat "$SEL/git2-nope.json"; fail "--only with an unknown id planned a clone or a link"; }
(cd "$SEL" && sel apply! -f "$SEL/git2.edn" --only skills/nope -y) > "$SEL/git2-nope.txt"
[ ! -e "$SEL/.agents/skill-packs/gp2" ] || { cat "$SEL/git2-nope.txt"; fail "--only with an unknown id cloned a pack"; }

# an id still owned but dropped from the config is only removed: its removal
# needs no clone, and must not fall back to fetching every pack not on disk
cat > "$SEL/git3.edn" <<EDN
{:skill-packs {[:uri "file://$SEL/packs/p"] {:alias p :dir "skills"}
               [:uri "file://$SEL/gp2"] {:alias gp2 :dir "skills" :type :git}}
 :skills {[gp2 "h"] {:in :all :acli [:claude]}}}
EDN
owned 'skills +a$' || { sel state; fail "setup: a is not owned"; }
sel_plan -f "$SEL/git3.edn" --only skills/a --json > "$SEL/drop-a.json"
python3 - "$SEL/drop-a.json" <<'PY' || { cat "$SEL/drop-a.json"; fail "--only on a dropped skill planned more than its removal"; }
import json, sys
ops = json.load(open(sys.argv[1]))["ops"]
assert ops, "nothing planned"
assert all(o["kind"] == "skills" and o["id"] == "a" and o["action"] == "delete" for o in ops), ops
PY
(cd "$SEL" && sel apply! -f "$SEL/git3.edn" --only skills/a -y) > "$SEL/drop-a.txt"
[ ! -e "$SEL/.claude/skills/a" ] || { cat "$SEL/drop-a.txt"; fail "--only did not remove a dropped skill"; }
[ ! -e "$SEL/.agents/skill-packs/gp2" ] || { cat "$SEL/drop-a.txt"; fail "removing a dropped skill cloned a pack"; }
[ -L "$SEL/.claude/skills/c" ] || fail "removing a dropped skill touched another"

# -k skills keeps the fetch a skill needs, alone and with --only / --user
sel_plan -f "$SEL/git3.edn" --user -k skills --json > "$SEL/k-user.json"
python3 -c 'import json,sys; ops=json.load(open(sys.argv[1]))["ops"]; sys.exit(0 if any(o["kind"]=="skill-packs" and o["id"]=="gp2" for o in ops) else 1)' "$SEL/k-user.json" \
  || { cat "$SEL/k-user.json"; fail "--user -k skills dropped the clone a user-wide skill needs"; }
(cd "$SEL" && sel apply! -f "$SEL/git3.edn" --only skills/h -k skills -y) > "$SEL/k-only.txt"
[ -L "$SEL/.claude/skills/h" ] || { cat "$SEL/k-only.txt"; fail "--only skills/h -k skills did not clone and link"; }

# --json carries the warnings the text prints
sel_plan -f "$SEL/git3.edn" --only skills/nope --json > "$SEL/warn.json"
python3 -c 'import json,sys; w=json.load(open(sys.argv[1]))["warnings"]; sys.exit(0 if any("--only skills/nope" in x for x in w) else 1)' "$SEL/warn.json" \
  || { cat "$SEL/warn.json"; fail "--json omitted the warning for an --only naming nothing"; }

# selectors that cannot both hold, and options nobody knows, are errors
for args in "-k skills --only mcps/keep" "--onyl skills/c"; do
  set +e
  sel apply -f "$SEL/git3.edn" $args > "$SEL/bad.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] || { cat "$SEL/bad.txt"; fail "apply $args was not rejected"; }
done
grep -q 'unknown option --onyl' "$SEL/bad.txt" || { cat "$SEL/bad.txt"; fail "an unknown option was not named"; }
[ -L "$SEL/.claude/skills/c" ] || fail "a rejected run touched the environment"

# a mistyped command is reported as one, whatever options came with it
for opt in "--only skills/x" "--host h"; do
  set +e
  sel bogus $opt > "$SEL/cmd.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] && grep -q 'unknown command: bogus' "$SEL/cmd.txt" || { cat "$SEL/cmd.txt"; fail "bogus $opt was not reported as an unknown command"; }
done

echo "22. a pack that fails to clone fails the run and owns nothing"
FAILSB="$SB/clonefail"; mkdir -p "$FAILSB"
cat > "$FAILSB/agents.edn" <<EDN
{:executors {:pi {:model "m1" :provider "prov"}}
 :skill-packs {:gone {:uri "file://$FAILSB/no-such-repo" :type :git :dir "skills"}}
 :skills {:gone-skill {:from :gone :tools [:pi]}}
 :extra-providers {:prov {:url "http://127.0.0.1:9999" :models ["m1"] :tools [:pi]}}}
EDN
set +e
run apply! -f "$FAILSB/agents.edn" -t pi -y > "$FAILSB/apply.txt" 2>&1; code=$?
set -e
[ "$code" = 1 ] || { cat "$FAILSB/apply.txt"; fail "a failed clone exited $code, want 1"; }
grep -q 'git clone exited' "$FAILSB/apply.txt" || { cat "$FAILSB/apply.txt"; fail "the failed clone was not reported with git's exit"; }
[ ! -e "$SB/.agents/skill-packs/gone" ] || fail "a failed clone left a checkout behind"
[ ! -e "$SB/.pi/agent/skills/gone-skill" ] || fail "a skill was linked against a pack that failed to clone"
if grep -q ":gone" "$SB/.config/agentctl/state.edn" 2>/dev/null; then fail "a failed clone was recorded as owned"; fi

echo "23. a converged plan still prints its warnings, and exits 0"
CW="$SB/converged-warn"
mkdir -p "$CW"
cw() { (cd "$CW" && AGENTCTL_HOME="$CW" run "$@"); }
# claude has no :bogus-setting: every plan reports it, converged or not
cat > "$CW/agents.edn" <<'EDN'
{:executors {:claude {:model "opus" :bogus-setting true}}}
EDN
cw apply! -f "$CW/agents.edn" -t claude -y > "$CW/first.txt"
grep -q '"model" *: *"opus"' "$CW/.claude/settings.json" || { cat "$CW/first.txt"; fail "setup: claude settings not written"; }
for cmd in apply apply!; do
  set +e
  cw "$cmd" -f "$CW/agents.edn" -t claude -y > "$CW/$cmd.txt"; code=$?
  set -e
  [ "$code" = 0 ] || { cat "$CW/$cmd.txt"; fail "a converged $cmd with a warning exited $code"; }
  grep -q 'unsupported setting :bogus-setting' "$CW/$cmd.txt" \
    || { cat "$CW/$cmd.txt"; fail "a converged $cmd hid its warning behind no changes"; }
  grep -q '^0 to add, 0 to change, 0 to remove' "$CW/$cmd.txt" \
    || { cat "$CW/$cmd.txt"; fail "a converged $cmd with a warning planned a change"; }
done
set +e
cw apply -f "$CW/agents.edn" -t claude --json > "$CW/plan.json"; code=$?
set -e
[ "$code" = 0 ] || { cat "$CW/plan.json"; fail "a converged apply --json with a warning exited $code"; }
python3 - "$CW/plan.json" <<'PY' || { cat "$CW/plan.json"; fail "--json dropped the warning of a converged plan"; }
import json, sys
ops = json.load(open(sys.argv[1]))["ops"]
assert not [o for o in ops if o["action"] in ("create", "update", "delete")], ops
assert any(o.get("warn") and "bogus-setting" in o["summary"] for o in ops), ops
PY

echo "24. --json prints one JSON document: config findings and refusals included"
JO="$SB/json-out"
mkdir -p "$JO"
jo() { (cd "$JO" && AGENTCTL_HOME="$JO" run "$@"); }
# jo_json <want-exit> <out-file> <args…>: the run exits as wanted and its
# stdout parses as JSON on its own
jo_json() {
  local want=$1 out=$2; shift 2
  set +e
  jo "$@" --json > "$out"; local code=$?
  set -e
  [ "$code" = "$want" ] || { cat "$out"; fail "$* --json exited $code, wanted $want"; }
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out" \
    || { cat "$out"; fail "$* --json printed something besides JSON"; }
}
cat > "$JO/warn.edn" <<'EDN'
{:cli-code {:pi {:model "m"}}}
EDN
cat > "$JO/error.edn" <<'EDN'
{:mcps {:x {:cmd "a"}} :projects {:p {:mcp [:nope]}}}
EDN
# a structural warning travels in `findings`, beside the plan
jo_json 2 "$JO/warn.json" apply -f "$JO/warn.edn" -t pi
python3 - "$JO/warn.json" <<'PY' || { cat "$JO/warn.json"; fail "--json lost the config warning"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert d["ops"], d
assert any(f["level"] == "warn" and f["where"] == ["cli-code"] and ":cli-code" in f["message"]
           for f in d["findings"]), d["findings"]
PY
jo_json 0 "$JO/validate.json" validate -f "$JO/warn.edn" -t pi
# so does this machine's word on the status line, checked once the scope is known
cat > "$JO/statusline.edn" <<'EDN'
{:statusline {:defaults {:segments [:model :context]}}}
EDN
AGENT_STATUSLINE_CONFIG="$JO/elsewhere.json" jo_json 2 "$JO/statusline.json" apply -f "$JO/statusline.edn" --all
python3 - "$JO/statusline.json" <<'PY' || { cat "$JO/statusline.json"; fail "--json lost the status-line warning"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert any(f["level"] == "warn" and f["where"] == ["statusline", "env"] for f in d["findings"]), d["findings"]
PY
# a config with errors is refused as JSON, and plans nothing
for cmd in apply apply!; do
  jo_json 1 "$JO/error-$cmd.json" "$cmd" -f "$JO/error.edn" -y
  python3 - "$JO/error-$cmd.json" <<'PY' || { cat "$JO/error-$cmd.json"; fail "$cmd --json refused a config without saying why"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert "refusing to plan" in d["error"], d
assert "ops" not in d, d
assert any(f["level"] == "error" and f["where"] == ["projects", "p", "mcp"] for f in d["findings"]), d
PY
done
# so is every other error: a selector, a missing file
jo_json 1 "$JO/kind.json" apply -f "$JO/warn.edn" -k bogus-kind
python3 -c 'import json,sys; sys.exit(0 if "unknown kind" in json.load(open(sys.argv[1]))["error"] else 1)' "$JO/kind.json" \
  || { cat "$JO/kind.json"; fail "-k bogus-kind --json lost the error"; }
jo_json 1 "$JO/missing.json" apply -f "$JO/nowhere.edn"
python3 -c 'import json,sys; sys.exit(0 if "not found" in json.load(open(sys.argv[1]))["error"] else 1)' "$JO/missing.json" \
  || { cat "$JO/missing.json"; fail "a missing config under --json lost the error"; }
# text output is unchanged
set +e
jo apply -f "$JO/warn.edn" -t pi > "$JO/warn.txt"; code=$?
set -e
[ "$code" = 2 ] && grep -q '⚠ cli-code — :cli-code is the old name' "$JO/warn.txt" \
  || { cat "$JO/warn.txt"; fail "the text plan stopped printing config warnings"; }

echo "25. -k refuses a kind the config's format never plans"
FK="$SB/format-kinds"
mkdir -p "$FK/proj"
fk() { (cd "$FK" && AGENTCTL_HOME="$FK" run "$@"); }
# the same trusted project, once per format: the DSL plans its trust as
# `trust`, the old format as `projects`
cat > "$FK/dsl.edn" <<EDN
{:trust [{:in "$FK/proj" :acli [:codex]}]}
EDN
cat > "$FK/old.edn" <<EDN
{:projects {:p {:path "$FK/proj" :trusted true :executors #{:codex}}}}
EDN
# fk_code <want-exit> <out-file> <args…>
fk_code() {
  local want=$1 out=$2; shift 2
  set +e
  fk "$@" > "$out" 2>&1; local code=$?
  set -e
  [ "$code" = "$want" ] || { cat "$out"; fail "$* exited $code, wanted $want"; }
}
for cmd in apply apply!; do
  fk_code 1 "$FK/dsl-$cmd.txt" "$cmd" -f "$FK/dsl.edn" --all -t codex -k projects -y
  grep -q '^error: -k projects: an entity-DSL config plans nothing as projects; it files those ops under permissions, settings or trust (kinds for this config: extensions, ' "$FK/dsl-$cmd.txt" \
    || { cat "$FK/dsl-$cmd.txt"; fail "$cmd -k projects on a DSL config did not name the DSL's kinds"; }
  grep -q 'projects,\|, projects)' "$FK/dsl-$cmd.txt" && { cat "$FK/dsl-$cmd.txt"; fail "the DSL's kinds listed projects"; }
  fk_code 1 "$FK/old-$cmd.txt" "$cmd" -f "$FK/old.edn" --all -t codex -k trust -y
  grep -q '^error: -k trust: an old-format config plans nothing as trust; it files those ops under projects (kinds for this config: .*projects' "$FK/old-$cmd.txt" \
    || { cat "$FK/old-$cmd.txt"; fail "$cmd -k trust on an old-format config did not name its kinds"; }
done
[ -e "$FK/.codex/config.toml" ] && fail "a refused kind wrote the codex config"
# the counterpart in each format plans the trust
fk_code 2 "$FK/dsl-trust.txt" apply -f "$FK/dsl.edn" --all -t codex -k trust
grep -q 'trust' "$FK/dsl-trust.txt" || { cat "$FK/dsl-trust.txt"; fail "-k trust planned nothing on a DSL config"; }
fk_code 2 "$FK/old-projects.txt" apply -f "$FK/old.edn" --all -t codex -k projects
grep -q 'projects' "$FK/old-projects.txt" || { cat "$FK/old-projects.txt"; fail "-k projects planned nothing on an old-format config"; }
# --only's kind is held to the same rule, and --json carries the refusal
fk_code 1 "$FK/only.txt" apply -f "$FK/dsl.edn" --only projects/p
grep -q '^error: --only projects/p: an entity-DSL config plans nothing as projects' "$FK/only.txt" \
  || { cat "$FK/only.txt"; fail "--only projects/p on a DSL config was not refused"; }
fk_code 1 "$FK/dead.json" apply -f "$FK/old.edn" -k trust --json
python3 -c 'import json,sys; sys.exit(0 if "old-format" in json.load(open(sys.argv[1]))["error"] else 1)' "$FK/dead.json" \
  || { cat "$FK/dead.json"; fail "-k trust --json lost the refusal"; }
# --help marks the old format's own kind
run --help | tr -s ' \n' ' ' \
  | grep -q "legacy (old-format configs only, in place of trust, and of a project's permissions and settings): projects " \
  || { run --help; fail "--help does not mark projects as legacy"; }
# The steps below each get a home of their own: what the steps above own would
# otherwise turn into prunes and change the exit codes asserted here.
fresh_home() { local h="$SB/homes/$1"; rm -rf "$h"; mkdir -p "$h"; printf '%s' "$h"; }
# mkskill DIR [NAME [BODY]]: a skill directory whose SKILL.md says NAME
mkskill() { mkdir -p "$1"; printf -- "---\nname: ${2:-$(basename "$1")}\ndescription: ${3:-d}.\n---\n\nbody ${3:-d}\n" > "$1/SKILL.md"; }
# commit everything in repo DIR
commit_all() { git -C "$1" add -A && git -C "$1" -c user.email=t@e -c user.name=t commit -qm "${2:-c}"; }

echo "26. a stale lock entry beside a dangling project link is cleaned up, not a crash"
(
  H=$(fresh_home stale-lock); export AGENTCTL_HOME="$H"
  PK="$H/projects/shared"; P="$H/projects/game"
  mkdir -p "$PK/skills/wrap-up" "$PK/skills/other" "$P/.agents/skills" "$P/.claude/skills"
  printf -- '---\nname: wrap-up\ndescription: Demo.\n---\n' > "$PK/skills/wrap-up/SKILL.md"
  printf -- '---\nname: other\ndescription: Demo.\n---\n' > "$PK/skills/other/SKILL.md"
  # the CLI's copy is gone; claude's link into it and the lock entry are not
  ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
  printf '{"version":2,"skills":{"wrap-up":{"source":"../shared/skills/wrap-up","sourceType":"local"}}}\n' \
    > "$P/skills-lock.json"
  git -C "$P" init -q
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$PK/skills"] {:alias shared}}
 :skills {[shared "wrap-up"] {:in :all :acli [:claude :codex]}
          [shared "other"] {:in "projects/game" :acli [:claude]}}}
EDN
  set +e
  run apply -f "$H/agents.edn" --all > "$H/plan.txt" 2>&1; code=$?
  set -e
  [ "$code" = 2 ] || { cat "$H/plan.txt"; fail "a dangling project link: expected drift (exit 2), got $code"; }
  grep -q "^exec: '.*skills-stub remove wrap-up -y'" "$H/plan.txt" || { cat "$H/plan.txt"; fail "the stale lock entry is not dropped"; }
  grep -q "^exec: 'unlink ~/projects/game/.claude/skills/wrap-up'" "$H/plan.txt" || { cat "$H/plan.txt"; fail "the dangling link is not unlinked"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "apply! failed on a dangling project link"; }
  [ ! -L "$P/.claude/skills/wrap-up" ] || fail "the dangling link survived apply!"
  grep -q '"wrap-up"' "$P/skills-lock.json" && fail "the stale lock entry survived apply!"
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "a second apply did not converge"; }
)

echo "27. a project copy pi is pinned to survives its skill going user-wide, in one apply!"
(
  H=$(fresh_home pinned-copy); export AGENTCTL_HOME="$H"
  PK="$H/projects/shared"; P="$H/projects/game"
  mkdir -p "$PK/skills/wrap-up" "$PK/skills/other" "$P/.agents/skills" "$P/.claude/skills"
  printf -- '---\nname: wrap-up\ndescription: Demo.\n---\n\nbody\n' > "$PK/skills/wrap-up/SKILL.md"
  printf -- '---\nname: other\ndescription: Demo.\n---\n' > "$PK/skills/other/SKILL.md"
  # what the skills CLI left behind: a copy, claude's relative link into it, a lock entry
  cp -R "$PK/skills/wrap-up" "$P/.agents/skills/wrap-up"
  ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
  printf '{"version":2,"skills":{"wrap-up":{"source":"../shared/skills/wrap-up","sourceType":"local"}}}\n' \
    > "$P/skills-lock.json"
  git -C "$P" init -q
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$PK/skills"] {:alias shared}}
 :skills {[shared "wrap-up"] {:in :all :acli [:claude :codex :pi]
                              :per-tool {:pi {:path "~/projects/game/.agents/skills/wrap-up"}}}
          [shared "other"] {:in "projects/game" :acli [:claude]}}}
EDN
  set +e
  run apply -f "$H/agents.edn" --all > "$H/plan.txt" 2>&1; code=$?
  set -e
  [ "$code" = 2 ] || { cat "$H/plan.txt"; fail "the pinned copy: expected drift (exit 2), got $code"; }
  grep -q "copy ~/projects/game/.agents/skills/wrap-up kept — pi's wrap-up is linked from it" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the kept copy is not reported with its reason"; }
  grep -q "skills-stub remove wrap-up" "$H/plan.txt" && { cat "$H/plan.txt"; fail "the copy pi is pinned to is planned for removal"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "apply! failed"; }
  [ -f "$H/.pi/agent/skills/wrap-up/SKILL.md" ] || { ls -l "$H/.pi/agent/skills"; fail "pi's user-wide wrap-up dangles"; }
  [ -d "$P/.agents/skills/wrap-up" ] && [ ! -L "$P/.agents/skills/wrap-up" ] || fail "the copy pi is pinned to was removed"
  [ ! -e "$P/.claude/skills/wrap-up" ] && [ ! -L "$P/.claude/skills/wrap-up" ] || fail "claude's redundant project link survived"
  grep -q '"wrap-up"' "$P/skills-lock.json" || fail "the kept copy lost its lock entry"
  set +e
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1; code=$?
  set -e
  [ "$code" = 0 ] || { cat "$H/plan2.txt"; fail "one apply! did not converge (exit $code)"; }
)

echo "28. a skill committed in the project's own .agents/skills is linked, never moved"
(
  H=$(fresh_home committed-skill); export AGENTCTL_HOME="$H"
  P="$H/projects/sample"
  mkdir -p "$P/.agents/skills/shared"
  printf -- '---\nname: shared\ndescription: Demo.\n---\n' > "$P/.agents/skills/shared/SKILL.md"
  git -C "$P" init -q
  git -C "$P" add -A
  git -C "$P" -c user.email=t@e -c user.name=t commit -qm init
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$P/.agents/skills"] {:alias sample-skills}}
 :skills {[sample-skills "shared"] {:in "projects/sample" :acli [:claude]}}}
EDN
  set +e
  run apply -f "$H/agents.edn" --all -k skills -k skill-packs > "$H/plan.txt" 2>&1; code=$?
  set -e
  [ "$code" = 2 ] || { cat "$H/plan.txt"; fail "the committed skill: expected drift (exit 2), got $code"; }
  [ "$(grep -c "^exec:" "$H/plan.txt")" = 1 ] \
    && grep -q "^exec: 'ln -sfn ../../.agents/skills/shared ~/projects/sample/.claude/skills/shared'" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the plan is not one relative link"; }
  for i in 1 2; do
    run apply! -f "$H/agents.edn" --all -k skills -k skill-packs -y > "$H/apply$i.txt" 2>&1 || { cat "$H/apply$i.txt"; fail "apply! $i failed"; }
  done
  [ -f "$P/.agents/skills/shared/SKILL.md" ] && [ ! -L "$P/.agents/skills/shared" ] || fail "the committed source was moved"
  [ "$(readlink "$P/.claude/skills/shared")" = "../../.agents/skills/shared" ] || fail "claude's link is not relative"
  [ -f "$P/.claude/skills/shared/SKILL.md" ] || fail "claude's link does not resolve"
  [ ! -e "$P/skills-lock.json" ] || fail "a skills-lock.json was written"
  # the new .claude/ is untracked by design; nothing git tracks may change
  [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "apply! changed tracked files"; }
  # a relative link is committable: once committed, the tree stays clean
  git -C "$P" add -A
  git -C "$P" -c user.email=t@e -c user.name=t commit -qm link
  run apply -f "$H/agents.edn" --all -k skills -k skill-packs > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "not idempotent"; }
  [ -z "$(git -C "$P" status --porcelain)" ] || { git -C "$P" status; fail "git status is not clean"; }
)

echo "29. a per-tool path is the source the plan shows, and --verbose shows the link"
(
  H=$(fresh_home per-tool-path); export AGENTCTL_HOME="$H"
  for d in "$H/projects/shared/skills/wrap-up" "$H/projects/sample/.agents/skills/wrap-up"; do
    mkdir -p "$d"
    printf -- '---\nname: wrap-up\ndescription: Demo.\n---\n' > "$d/SKILL.md"
  done
  cat > "$H/base.edn" <<EDN
{:skill-packs {[:uri "file://$H/projects/shared"] {:alias shared :dir "skills"}}
 :skills {[shared "wrap-up"] {:in :all :acli [:pi]}}}
EDN
  run apply! -f "$H/base.edn" --all -t pi -k skills -y > /dev/null
  sed 's#:acli \[:pi\]}#:acli [:pi] :per-tool {:pi {:path "~/projects/sample/.agents/skills/wrap-up"}}}#' \
    "$H/base.edn" > "$H/agents.edn"
  (run apply -f "$H/agents.edn" --all -t pi -k skills || true) > "$H/plan.txt" 2>&1
  grep -q '~ skill \[agents: pi\] ~/projects/sample/.agents/skills/wrap-up' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the plan names the base source, not the per-tool path"; }
  (run apply -f "$H/agents.edn" --all -t pi -k skills --verbose || true) > "$H/plan-v.txt" 2>&1
  grep -q "^exec: 'ln -sfn ~/projects/sample/.agents/skills/wrap-up ~/.pi/agent/skills/wrap-up'" "$H/plan-v.txt" \
    && grep -q '^    link ~/.pi/agent/skills/wrap-up -> ~/projects/sample/.agents/skills/wrap-up (was ~/projects/shared/skills/wrap-up)' "$H/plan-v.txt" \
    || { cat "$H/plan-v.txt"; fail "--verbose does not show the user-wide link it changes"; }
  (run apply -f "$H/agents.edn" --all -t pi -k skills --json || true) 2>/dev/null | python3 -c '
import json, sys
o = [o for o in json.load(sys.stdin)["ops"] if o.get("kind") == "skills"][0]
assert o["display"]["source"] == o["from"] == "~/projects/sample/.agents/skills/wrap-up", o
' || fail "the JSON display source disagrees with from"
)

echo "30. pi reads a project's skills in the project, and is told where one shadows a user-wide skill"
(
  H=$(fresh_home pi-project-skills); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; P="$H/projects/proj"
  for s in wrap-up audit review; do
    mkdir -p "$K/$s"; printf -- "---\nname: $s\ndescription: Shared $s.\n---\n" > "$K/$s/SKILL.md"
  done
  # the project's own wrap-up, and an identical copy of audit in pi's own directory
  mkdir -p "$P/.agents/skills/wrap-up" "$P/.pi/skills"
  printf -- '---\nname: wrap-up\ndescription: Ours.\n---\n' > "$P/.agents/skills/wrap-up/SKILL.md"
  cp -R "$K/audit" "$P/.pi/skills/audit"
  git -C "$P" init -q
  cat > "$H/untrusted.edn" <<EDN
{:skill-packs {[:uri "file://$H/projects/shared"] {:alias shared :dir "skills"}}
 :skills {[shared "wrap-up"] {:in :all :acli [:pi]}
          [shared "audit"] {:in :all :acli [:pi]}
          [shared "review"] {:in "projects/proj" :acli [:pi]}}}
EDN
  { sed '$ s/}$//' "$H/untrusted.edn"; echo ' :trust [{:in "projects/proj" :acli [:pi]}]}'; } > "$H/agents.edn"
  (run apply -f "$H/untrusted.edn" --all -t pi || true) > "$H/plan0.txt" 2>&1
  grep -q 'pi asks before reading project skills here' "$H/plan0.txt" \
    && ! grep -q 'in place of the user-wide' "$H/plan0.txt" \
    || { cat "$H/plan0.txt"; fail "an untrusted project: no trust warning, or a collision pi never sees"; }
  set +e
  run apply -f "$H/agents.edn" --all -t pi > "$H/plan.txt" 2>&1; code=$?
  set -e
  [ "$code" = 2 ] || { cat "$H/plan.txt"; fail "pi project skills: expected drift (exit 2), got $code"; }
  grep -q "^exec: 'ln -sfn ~/projects/shared/skills/review ~/projects/proj/.agents/skills/review'" "$H/plan.txt" \
    && grep -q "^exec: 'rm -r ~/projects/proj/.pi/skills/audit'" "$H/plan.txt" \
    && grep -q 'pi loads ~/projects/proj/.agents/skills/wrap-up here in place of the user-wide wrap-up' "$H/plan.txt" \
    && ! grep -q '\.pi/agent/skills/review' "$H/plan.txt" \
    && ! grep -q 'pi asks before reading' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the plan does not place review in the project, drop the audit copy and report the wrap-up collision"; }
  run apply! -f "$H/agents.edn" --all -t pi -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "apply! failed"; }
  [ -f "$P/.agents/skills/review/SKILL.md" ] || fail "review is not in the project's .agents/skills"
  [ ! -e "$H/.pi/agent/skills/review" ] || fail "a project skill was installed user-wide for pi"
  [ ! -e "$P/.pi/skills/audit" ] || fail "the identical audit copy is still there"
  grep -q 'Ours' "$P/.agents/skills/wrap-up/SKILL.md" || fail "the project's own wrap-up was touched"
  run apply -f "$H/agents.edn" --all -t pi > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "not converged after one apply!"; }
  (run validate -f "$H/agents.edn" -t pi -k skills || true) > "$H/validate.txt" 2>&1
  grep -q '! ~/projects/proj/.agents/skills/wrap-up .*pi loads' "$H/validate.txt" \
    || { cat "$H/validate.txt"; fail "validate does not repeat the collision once converged"; }
)

echo "31. pi's collision with a kept copy the skills CLI installed is still reported"
(
  H=$(fresh_home pi-kept-collision); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; P="$H/projects/game"
  mkskill "$K/wrap-up" wrap-up shared; mkskill "$K/other" other other
  mkdir -p "$P/.agents/skills" "$P/.claude/skills"
  cp -R "$K/wrap-up" "$P/.agents/skills/"
  ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
  printf '{"version":2,"skills":{"wrap-up":{"source":"../shared/skills/wrap-up","sourceType":"local"}}}\n' > "$P/skills-lock.json"
  git -C "$P" init -q; commit_all "$P"
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias shared}}
 :skills {[shared "wrap-up"] {:in :all :acli [:claude :codex :pi]}
          [shared "other"] {:in "projects/game" :acli [:claude :codex :pi]}}
 :trust [{:in "projects/game" :acli [:pi]}]}
EDN
  (run apply -f "$H/agents.edn" --all || true) > "$H/plan.txt" 2>&1
  grep -q 'project copy ~/projects/game/.agents/skills/wrap-up kept — it is tracked by git' "$H/plan.txt" \
    && grep -q 'pi loads ~/projects/game/.agents/skills/wrap-up here in place of the user-wide wrap-up' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the kept copy's collision report is missing"; }
)

echo "32. a shared project copy stays while an agent that reads it has no user-wide skill"
(
  # pi_copy NAME OTHER-ACLI TRUST: wrap-up user-wide for pi only, beside an
  # identical, untracked copy in the project's shared .agents/skills
  pi_copy() {
    H=$(fresh_home "$1"); export AGENTCTL_HOME="$H"
    K="$H/projects/shared/skills"; P="$H/projects/proj"
    mkskill "$K/wrap-up" wrap-up shared; mkskill "$K/other" other other
    mkdir -p "$P/.agents/skills"
    if [ "${4:-}" = link ]; then ln -s "$K/wrap-up" "$P/.agents/skills/wrap-up"
    else cp -R "$K/wrap-up" "$P/.agents/skills/wrap-up"; fi
    git -C "$P" init -q
    cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias shared}}
 :skills {[shared "wrap-up"] {:in :all :acli [:pi]}
          [shared "other"] {:in "projects/proj" :acli [$2]}}
 $3}
EDN
    (run apply -f "$H/agents.edn" --all || true) > "$H/plan.txt" 2>&1
    run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "$1: apply! failed"; }
  }
  trust=':trust [{:in "projects/proj" :acli [:pi]}]'
  rm_line="exec: 'rm -r ~/projects/proj/.agents/skills/wrap-up'"

  pi_copy codex-reads ":codex :pi" "$trust"
  ! grep -q "$rm_line" "$H/plan.txt" \
    && grep -q 'project copy ~/projects/proj/.agents/skills/wrap-up kept — codex reads it here and has no user-wide wrap-up' "$H/plan.txt" \
    && grep -q 'pi loads ~/projects/proj/.agents/skills/wrap-up here in place of the user-wide wrap-up' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the copy codex reads is planned for removal, or not reported"; }
  [ -f "$P/.agents/skills/wrap-up/SKILL.md" ] || fail "pi removed the copy codex reads"
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "codex-reads: not converged"; }

  # a link there is how codex gets the skill, as much as a copy is
  pi_copy codex-link ":codex :pi" "$trust" link
  ! grep -q "unlink ~/projects/proj/.agents/skills/wrap-up" "$H/plan.txt" \
    && grep -q 'project copy ~/projects/proj/.agents/skills/wrap-up kept — codex reads it here' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "the link codex reads is planned for removal"; }
  [ -L "$P/.agents/skills/wrap-up" ] || fail "pi removed the link codex reads"

  pi_copy pi-asks ":codex :pi" ""
  ! grep -q "$rm_line" "$H/plan.txt" && grep -q 'pi asks before reading project skills here' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "an untrusted project's copy is planned for removal"; }
  [ -f "$P/.agents/skills/wrap-up/SKILL.md" ] || fail "pi removed a copy in a project it does not trust"

  pi_copy pi-alone ":pi" "$trust"
  grep -q "$rm_line" "$H/plan.txt" || { cat "$H/plan.txt"; fail "the copy only pi reads is not removed"; }
  [ ! -e "$P/.agents/skills/wrap-up" ] || fail "the copy only pi reads is still there"
)

echo "33. a link or an install never lands on a project directory that stays"
(
  # committed NAME DIR ACLI [same]: shared's foo declared for ACLI in a project
  # that already commits its own foo (identical with "same") at DIR
  committed() {
    H=$(fresh_home "$1"); export AGENTCTL_HOME="$H"
    K="$H/projects/shared/skills"; P="$H/projects/proj"
    mkskill "$K/foo" foo theirs; mkskill "$P/$2/foo" foo ours
    [ -n "${4:-}" ] && cp "$K/foo/SKILL.md" "$P/$2/foo/SKILL.md"
    git -C "$P" init -q; commit_all "$P"
    cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$H/projects/shared"] {:alias shared :dir "skills"}}
 :skills {[shared "foo"] {:in "projects/proj" :acli [$3]}}}
EDN
    (run apply -f "$H/agents.edn" --all --show-noop || true) > "$H/plan.txt" 2>&1
    grep -q "project copy ~/projects/proj/$2/foo kept — it is tracked by git" "$H/plan.txt" \
      && ! grep -q "ln -sfn .* ~/projects/proj/$2/foo'" "$H/plan.txt" \
      || { cat "$H/plan.txt"; fail "$1: the committed $2/foo is not kept, or a link is planned onto it"; }
    # the entity alone first: --only narrows the plan, never the rules behind it
    run apply! -f "$H/agents.edn" --all --only skills/foo -y > "$H/apply-only.txt" 2>&1 \
      || { cat "$H/apply-only.txt"; fail "$1: apply! --only failed"; }
    [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "$1: --only changed tracked files"; }
    run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "$1: apply! failed"; }
    [ -d "$P/$2/foo" ] && [ ! -L "$P/$2/foo" ] || fail "$1: the committed copy was replaced"
    [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "$1: tracked files changed"; }
    run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "$1: not converged"; }
    (run validate -f "$H/agents.edn" || true) > "$H/validate.txt" 2>&1
    grep -q "! ~/projects/proj/$2/foo .*kept — it is tracked by git" "$H/validate.txt" \
      || { cat "$H/validate.txt"; fail "$1: validate does not report the kept copy"; }
  }
  committed codex-differs .agents/skills :codex
  committed codex-same .agents/skills :codex same
  committed claude-differs .claude/skills :claude

  # a copy pi is pinned to, and another pack's skill that goes by the same name
  H=$(fresh_home pinned-name); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; O="$H/projects/other/skills"; P="$H/projects/game"
  mkskill "$K/wrap-up" wrap-up shared; mkskill "$K/other" other other; mkskill "$O/wu2" wrap-up second
  mkdir -p "$P/.agents/skills" "$P/.claude/skills"
  cp -R "$K/wrap-up" "$P/.agents/skills/wrap-up"
  ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
  printf '{"version":2,"skills":{"wrap-up":{"source":"../shared/skills/wrap-up","sourceType":"local"}}}\n' > "$P/skills-lock.json"
  git -C "$P" init -q
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias shared}
               [:uri "file://$O"] {:alias other}}
 :skills {[shared "wrap-up"] {:in :all :acli [:claude :codex :pi]
                              :per-tool {:pi {:path "~/projects/game/.agents/skills/wrap-up"}}}
          [other "wu2"] {:in "projects/game" :acli [:codex]}
          [shared "other"] {:in "projects/game" :acli [:claude]}}}
EDN
  (run apply -f "$H/agents.edn" --all || true) > "$H/plan.txt" 2>&1
  ! grep -q "ln -sfn ~/projects/other/skills/wu2" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "another skill is linked onto the copy pi is pinned to"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "pinned-name: apply! failed"; }
  ! grep -q "✗" "$H/apply.txt" || { cat "$H/apply.txt"; fail "pinned-name: an op failed"; }
  grep -q "body shared" "$P/.agents/skills/wrap-up/SKILL.md" || fail "the copy pi is pinned to was replaced"
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "pinned-name: not converged"; }

  # skills add from a git pack only the project uses, beside a committed review
  H=$(fresh_home git-pack-add); export AGENTCTL_HOME="$H"
  G="$H/remote/kit"; P="$H/projects/proj"
  mkskill "$G/review" review theirs; mkskill "$G/audit" audit theirs
  git -C "$G" init -q; commit_all "$G"
  mkskill "$P/.agents/skills/review" review ours
  git -C "$P" init -q; commit_all "$P"
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$G"] {:alias kit :type :git}}
 :skills {[kit "review"] {:in "projects/proj" :acli [:codex]}
          [kit "audit"] {:in "projects/proj" :acli [:codex]}}}
EDN
  (run apply -f "$H/agents.edn" --all --show-noop || true) > "$H/plan.txt" 2>&1
  grep -q 'project copy ~/projects/proj/.agents/skills/review kept — it is tracked by git' "$H/plan.txt" \
    && ! grep -q -- "-s review" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "skills add is planned over the committed review"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "git-pack-add: apply! failed"; }
  [ -f "$P/.agents/skills/audit/SKILL.md" ] || fail "audit was not installed"
  [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "skills add changed the committed review"; }
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "git-pack-add: not converged"; }
)

echo "34. a hand-made copy that differs from the source is another skill: kept"
(
  H=$(fresh_home hand-made); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; P="$H/projects/proj"
  mkskill "$K/foo" foo theirs; mkskill "$K/bar" bar theirs
  # foo: left over in .agents/skills, which nothing placed there reads;
  # bar: where claude's link would go
  mkskill "$P/.agents/skills/foo" foo ours; mkskill "$P/.claude/skills/bar" bar ours
  git -C "$P" init -q
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$H/projects/shared"] {:alias shared :dir "skills"}}
 :skills {[shared "foo"] {:in "projects/proj" :acli [:claude]}
          [shared "bar"] {:in "projects/proj" :acli [:claude]}}}
EDN
  (run apply -f "$H/agents.edn" --all --show-noop || true) > "$H/plan.txt" 2>&1
  grep -q 'project copy ~/projects/proj/.agents/skills/foo kept — it differs from ~/projects/shared/skills/foo' "$H/plan.txt" \
    && grep -q 'project copy ~/projects/proj/.claude/skills/bar kept — it differs from ~/projects/shared/skills/bar' "$H/plan.txt" \
    && ! grep -q "rm -r ~/projects/proj/.agents/skills/foo" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "a differing hand-made copy is planned for removal"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "hand-made: apply! failed"; }
  grep -q "body ours" "$P/.agents/skills/foo/SKILL.md" && grep -q "body ours" "$P/.claude/skills/bar/SKILL.md" \
    || fail "a differing hand-made copy was removed"
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "hand-made: not converged"; }
)

echo "35. skills remove is never asked to take a directory that stays in any agent's directory"
(
  H=$(fresh_home other-agent-dir); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; P="$H/projects/game"
  mkskill "$K/wrap-up" wrap-up shared; mkskill "$K/other" other other
  # another agent's committed copy, which the skills CLI counts as its own
  mkdir -p "$P/.cursor/skills"; cp -R "$K/wrap-up" "$P/.cursor/skills/wrap-up"
  git -C "$P" init -q; commit_all "$P"
  # the CLI's own, untracked install of the same name
  mkdir -p "$P/.agents/skills" "$P/.claude/skills"
  cp -R "$K/wrap-up" "$P/.agents/skills/wrap-up"
  ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
  printf '{"version":2,"skills":{"wrap-up":{"source":"../shared/skills/wrap-up","sourceType":"local"}}}\n' > "$P/skills-lock.json"
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias shared}}
 :skills {[shared "wrap-up"] {:in :all :acli [:claude :codex]}
          [shared "other"] {:in "projects/game" :acli [:claude :codex]}}}
EDN
  (run apply -f "$H/agents.edn" --all || true) > "$H/plan.txt" 2>&1
  ! grep -q "remove wrap-up" "$H/plan.txt" \
    && grep -q 'project copy ~/projects/game/.cursor/skills/wrap-up kept — it is tracked by git' "$H/plan.txt" \
    && grep -q 'project copy ~/projects/game/.agents/skills/wrap-up kept — `skills remove` would take ~/projects/game/.cursor/skills/wrap-up with it' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "skills remove is planned while another agent's committed copy would go with it"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "other-agent-dir: apply! failed"; }
  [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "another agent's committed copy was removed"; }
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "other-agent-dir: not converged"; }

  # the same for a project's own skill: the CLI's older copy stays with its
  # lock entry, rather than being linked over while the lock still names it
  H=$(fresh_home blocked-project-skill); export AGENTCTL_HOME="$H"
  K="$H/projects/shared/skills"; P="$H/projects/proj"
  mkskill "$K/foo" foo theirs; mkskill "$P/.cursor/skills/foo" foo cursor
  git -C "$P" init -q; commit_all "$P"
  mkskill "$P/.agents/skills/foo" foo cli-old
  printf '{"version":2,"skills":{"foo":{"source":"../shared/skills/foo","sourceType":"local"}}}\n' > "$P/skills-lock.json"
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias shared}}
 :skills {[shared "foo"] {:in "projects/proj" :acli [:codex]}}}
EDN
  (run apply -f "$H/agents.edn" --all --show-noop || true) > "$H/plan.txt" 2>&1
  grep -q 'project copy ~/projects/proj/.agents/skills/foo kept — `skills remove` would take ~/projects/proj/.cursor/skills/foo with it' "$H/plan.txt" \
    && ! grep -q "ln -sfn" "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "a blocked CLI copy is linked over"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "blocked-project-skill: apply! failed"; }
  ! grep -q "✗" "$H/apply.txt" || { cat "$H/apply.txt"; fail "blocked-project-skill: an op failed"; }
  [ -z "$(git -C "$P" status --porcelain --untracked-files=no)" ] || { git -C "$P" status; fail "the committed .cursor copy changed"; }
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "blocked-project-skill: not converged"; }
)

echo "36. a user-wide link whose source is gone is reported, and the plan still converges"
(
  H=$(fresh_home missing-source); export AGENTCTL_HOME="$H"
  K="$H/projects/kit/skills"
  mkskill "$K/wrap-up" wrap-up shared; mkskill "$K/other" other other
  # what an earlier cleanup leaves: a stale lock entry beside dangling links,
  # and pi's user-wide link into the project copy that went with them
  for p in app lib; do
    P="$H/projects/$p"; mkdir -p "$P/.agents/skills" "$P/.claude/skills"
    ln -s ../../.agents/skills/wrap-up "$P/.claude/skills/wrap-up"
    printf '{"version":2,"skills":{"wrap-up":{"source":"../kit/skills/wrap-up","sourceType":"local"}}}\n' > "$P/skills-lock.json"
    git -C "$P" init -q
  done
  mkdir -p "$H/.pi/agent/skills"
  ln -s "$H/projects/lib/.agents/skills/wrap-up" "$H/.pi/agent/skills/wrap-up"
  cat > "$H/agents.edn" <<EDN
{:skill-packs {[:uri "file://$K"] {:alias kit}}
 :skills {[kit "wrap-up"] {:in :all :acli [:claude :codex :pi]
                           :per-tool {:pi {:path "~/projects/lib/.agents/skills/wrap-up"}}}
          [kit "other"] {:in ["projects/app" "projects/lib"] :acli [:claude]}}}
EDN
  (run apply -f "$H/agents.edn" --all || true) > "$H/plan.txt" 2>&1
  grep -q 'source missing: ~/projects/lib/.agents/skills/wrap-up — link not changed' "$H/plan.txt" \
    && ! grep -q '~ skill \[agents: pi\]' "$H/plan.txt" \
    || { cat "$H/plan.txt"; fail "a missing source is planned as a link update"; }
  run apply! -f "$H/agents.edn" --all -y > "$H/apply.txt" 2>&1 || { cat "$H/apply.txt"; fail "missing-source: apply! failed"; }
  run apply -f "$H/agents.edn" --all > "$H/plan2.txt" 2>&1 || { cat "$H/plan2.txt"; fail "a missing source keeps the plan from converging"; }
  (run validate -f "$H/agents.edn" || true) > "$H/validate.txt" 2>&1
  grep -q '! .*source missing: ~/projects/lib/.agents/skills/wrap-up' "$H/validate.txt" \
    || { cat "$H/validate.txt"; fail "validate does not report the missing source"; }
)

echo "37. apply! --json needs -y, and prints one document with what it applied"
AJ="$SB/apply-json"
mkdir -p "$AJ"
aj() { (cd "$AJ" && AGENTCTL_HOME="$AJ" run "$@"); }
# aj_json <want-exit> <out-file> <args…>: exits as wanted, stdout is one JSON document
aj_json() {
  local want=$1 out=$2; shift 2
  set +e
  aj "$@" --json > "$out"; local code=$?
  set -e
  [ "$code" = "$want" ] || { cat "$out"; fail "$* --json exited $code, wanted $want"; }
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out" \
    || { cat "$out"; fail "$* --json printed something besides JSON"; }
}
cat > "$AJ/ok.edn" <<'EDN'
{:executors {:pi {:model "m1"}}}
EDN
# a prompt cannot share stdout with JSON: without -y it is refused before
# anything is planned — on a host too, before it is looked up
aj_json 1 "$AJ/noyes.json" apply! -f "$AJ/ok.edn" -t pi
aj_json 1 "$AJ/noyes-host.json" apply! -f "$AJ/ok.edn" --host nowhere
for f in noyes noyes-host; do
  python3 -c 'import json,sys; sys.exit(0 if "-y" in json.load(open(sys.argv[1]))["error"] else 1)' "$AJ/$f.json" \
    || { cat "$AJ/$f.json"; fail "apply! --json without -y did not ask for -y"; }
done
[ -e "$AJ/.pi/agent/settings.json" ] && fail "apply! --json without -y wrote settings"
aj_json 0 "$AJ/apply.json" apply! -f "$AJ/ok.edn" -t pi -y
python3 - "$AJ/apply.json" <<'PY' || { cat "$AJ/apply.json"; fail "apply! --json did not report what it applied"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert any(o["action"] == "create" for o in d["ops"]), d
assert d["applied"]["failed"] == [], d
assert any(o["tool"] == "pi" and o["kind"] == "settings" for o in d["applied"]["done"]), d
assert d["backups"] and d["summary"], d
assert d["findings"] == [] and d["warnings"] == [], d
PY
grep -q 'm1' "$AJ/.pi/agent/settings.json" || fail "apply! --json -y did not converge"
aj_json 0 "$AJ/again.json" apply! -f "$AJ/ok.edn" -t pi -y
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d["applied"] == {"done": [], "failed": []} else 1)' "$AJ/again.json" \
  || { cat "$AJ/again.json"; fail "a converged apply! --json reported something applied"; }
# a failed op is in the document with its error, and the run exits 1
cat > "$AJ/fail.edn" <<EDN
{:executors {:pi {:model "m1"}}
 :skill-packs {:gone {:uri "file://$AJ/no-such-repo" :type :git :dir "skills"}}
 :skills {:gone-skill {:from :gone :tools [:pi]}}}
EDN
aj_json 1 "$AJ/fail.json" apply! -f "$AJ/fail.edn" -t pi -y
python3 - "$AJ/fail.json" <<'PY' || { cat "$AJ/fail.json"; fail "apply! --json lost a failed op"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert any(f["kind"] == "skill-packs" and "git clone exited" in f["error"] for f in d["applied"]["failed"]), d
PY
# the text output is unchanged
cat > "$AJ/ok2.edn" <<'EDN'
{:executors {:pi {:model "m2"}}}
EDN
aj apply! -f "$AJ/ok2.edn" -t pi -y > "$AJ/text.txt"
grep -q '^applied 1 change(s)$' "$AJ/text.txt" && grep -q '^backups: ' "$AJ/text.txt" \
  || { cat "$AJ/text.txt"; fail "apply! text output changed"; }

echo "38. every failure is one error line, or one JSON document under --json"
EF="$SB/errors"
mkdir -p "$EF/dir.edn"
printf '{}\n' > "$EF/locked.edn"
chmod 000 "$EF/locked.edn"
ef() { (cd "$EF" && AGENTCTL_HOME="$EF" run "$@"); }
# ef_fails <out-stem> <expect-in-message> <args…>: exit 1 in both modes, a
# one-line "error: …" in text, {"error": …} under --json, and no stack trace
ef_fails() {
  local stem=$1 want=$2; shift 2
  set +e
  ef "$@" > "$EF/$stem.txt" 2> "$EF/$stem.err"; local code=$?
  ef "$@" --json > "$EF/$stem.json" 2> "$EF/$stem.jerr"; local jcode=$?
  set -e
  [ "$code" = 1 ] && [ "$jcode" = 1 ] || { cat "$EF/$stem".*; fail "$* exited $code / $jcode under --json, wanted 1"; }
  [ "$(wc -l < "$EF/$stem.txt")" -eq 1 ] && grep -q "^error: .*$want" "$EF/$stem.txt" \
    || { cat "$EF/$stem.txt" "$EF/$stem.err"; fail "$* did not fail with one error line"; }
  python3 -c 'import json,sys; sys.exit(0 if sys.argv[2] in json.load(open(sys.argv[1]))["error"] else 1)' "$EF/$stem.json" "$want" \
    || { cat "$EF/$stem.json" "$EF/$stem.jerr"; fail "$* --json did not fail with a JSON error"; }
  if grep -q 'Error ---\|Location:' "$EF/$stem.err" "$EF/$stem.jerr"; then
    cat "$EF/$stem.err" "$EF/$stem.jerr"; fail "$* printed a stack trace"
  fi
}
ef_fails dir 'Is a directory' apply -f "$EF/dir.edn"
ef_fails dir-validate 'Is a directory' validate -f "$EF/dir.edn"
# root reads a file whatever its mode; the check means nothing there
if [ ! -r "$EF/locked.edn" ]; then
  ef_fails locked 'Permission denied' apply -f "$EF/locked.edn"
fi
chmod 600 "$EF/locked.edn"
# a mistyped command, under --json, is an error and not the usage text
set +e
ef bogus --json > "$EF/bogus.json"; code=$?
set -e
[ "$code" = 1 ] || { cat "$EF/bogus.json"; fail "bogus --json exited $code"; }
python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])) == {"error": "unknown command: bogus"} else 1)' "$EF/bogus.json" \
  || { cat "$EF/bogus.json"; fail "bogus --json did not print a JSON error"; }
set +e
ef bogus > "$EF/bogus.txt"; code=$?
set -e
[ "$code" = 1 ] && grep -q '^unknown command: bogus' "$EF/bogus.txt" && grep -q '^USAGE' "$EF/bogus.txt" \
  || { cat "$EF/bogus.txt"; fail "bogus without --json stopped printing the usage"; }

echo "39. gui -k and -p are the page's starting filter, as -t is"
GD="$SB/gui-filter"
mkdir -p "$GD/sub/web"
cat > "$GD/agents.edn" <<'EDN'
{:mcps [{:id :inproj :cmd "/bin/echo" :in "sub/web" :acli [:claude]}
        {:id :global :cmd "/bin/echo" :in :all :acli [:claude]}]
 :settings [{:model "opus" :acli [:claude] :in :all}]}
EDN
(cd "$GD" && AGENTCTL_HOME="$GD" exec "$ROOT/agentctl" gui -f "$GD/agents.edn" -k mcps -p sub/web \
   --port 0 --no-open) > "$GD/gui.log" 2>&1 &
GD_PID=$!
trap 'kill ${GUI_PID:-} ${GD_PID:-} 2>/dev/null || true; rm -rf "$SB"' EXIT
for _ in $(seq 1 100); do grep -q 'http://127.0.0.1' "$GD/gui.log" && break; sleep 0.1; done
GD_URL=$(grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$GD/gui.log" | head -1)
[ -n "$GD_URL" ] || { cat "$GD/gui.log"; fail "gui -k -p did not start"; }
gd_post() { # gd_post <extra-json> <out>: plan the file's text with extra request fields
  python3 -c 'import json,sys; d=json.loads(sys.argv[2]); d["text"]=open(sys.argv[1]).read(); print(json.dumps(d))' \
    "$GD/agents.edn" "$1" > "$GD/req.json"
  curl -sS -X POST -H "X-Agentctl-Token: ${GD_URL##*t=}" -H 'content-type: application/json' \
    --data-binary "@$GD/req.json" "${GD_URL%%/\?t=*}/api/plan" > "$2"
}
curl -sS -H "X-Agentctl-Token: ${GD_URL##*t=}" "${GD_URL%%/\?t=*}/api/config" > "$GD/config.json"
python3 - "$GD/config.json" <<'PY' || { cat "$GD/config.json"; fail "gui did not hand the page its -k and -p"; }
import json, sys
o = json.load(open(sys.argv[1]))["opts"]
assert o["kinds"] == ["mcps"] and o["projects"] == ["sub/web"], o
PY
# a request that says nothing about kinds or projects gets the command line's
gd_post '{"tools": ["claude"]}' "$GD/narrow.json"
python3 - "$GD/narrow.json" <<'PY' || { cat "$GD/narrow.json"; fail "gui ignored -k / -p"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert d["ok"], d
assert "inproj" in d["plan"], d["plan"]
assert "global" not in d["plan"] and "opus" not in d["plan"], d["plan"]
PY
# one that does say overrides them, an empty list meaning none
gd_post '{"tools": ["claude"], "kinds": [], "projects": []}' "$GD/whole.json"
python3 - "$GD/whole.json" <<'PY' || { cat "$GD/whole.json"; fail "the page could not widen the gui's filter"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert d["ok"] and "inproj" in d["plan"] and "global" in d["plan"] and "opus" in d["plan"], d["plan"]
PY
# a -p that selects nothing is said, as the cli says it
gd_post '{"tools": ["claude"], "projects": ["sub/nope"]}' "$GD/nope.json"
python3 - "$GD/nope.json" <<'PY' || { cat "$GD/nope.json"; fail "gui -p naming nothing was not reported"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert any(f["level"] == "warn" and "sub/nope" in f["where"] for f in d["findings"]), d["findings"]
PY
kill "$GD_PID" 2>/dev/null || true

echo "40. a config that decides no format claims none; --host holds -k to the format"
UF="$SB/undecided"
mkdir -p "$UF/proj"
uf() { (cd "$UF" && AGENTCTL_HOME="$UF" run "$@"); }
printf '{}\n' > "$UF/empty.edn"
cat > "$UF/statusline.edn" <<'EDN'
{:statusline {:defaults {:segments [:model :context]}}}
EDN
# -k trust selects nothing in either, and is told so — as a warning, since the
# next section added may decide the format either way
for f in empty statusline; do
  set +e
  uf apply -f "$UF/$f.edn" --all -k trust > "$UF/$f.txt" 2>&1; code=$?
  uf apply -f "$UF/$f.edn" --all -k trust --json > "$UF/$f.json"; jcode=$?
  set -e
  [ "$code" = 0 ] && [ "$jcode" = 0 ] || { cat "$UF/$f.txt" "$UF/$f.json"; fail "-k trust on $f.edn exited $code / $jcode"; }
  grep -q '⚠ -k trust — selects nothing in this config' "$UF/$f.txt" \
    || { cat "$UF/$f.txt"; fail "-k trust on $f.edn was not reported"; }
  python3 -c 'import json,sys; sys.exit(0 if any(w.startswith("-k trust — selects nothing in this config") for w in json.load(open(sys.argv[1]))["warnings"]) else 1)' "$UF/$f.json" \
    || { cat "$UF/$f.json"; fail "-k trust --json on $f.edn lost the warning"; }
  if grep -q 'old-format\|entity-DSL' "$UF/$f.txt" "$UF/$f.json"; then
    cat "$UF/$f.txt" "$UF/$f.json"; fail "$f.edn was said to be in a format it does not have"
  fi
done
# a host is planned under the same config: a kind its format never plans is
# refused there too, before the host is looked up
cat > "$UF/dsl.edn" <<EDN
{:trust [{:in "$UF/proj" :acli [:codex]}]}
EDN
cat > "$UF/old.edn" <<EDN
{:projects {:p {:path "$UF/proj" :trusted true :executors #{:codex}}}}
EDN
for pair in "dsl projects entity-DSL" "old trust old-format"; do
  set -- $pair
  set +e
  uf apply -f "$UF/$1.edn" --host nowhere -k "$2" > "$UF/host-$1.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] && grep -q "^error: -k $2: an $3 config plans nothing as $2" "$UF/host-$1.txt" \
    || { cat "$UF/host-$1.txt"; fail "--host -k $2 on the $1 config was not refused"; }
done
echo "41. codex nested tables converge through state.edn on disk and prune what they wrote"
mkdir -p "$SB/.codex"
printf 'model = "m-a"\n[tui]\nstatus_line = [\n    "model",\n]\n\n[tui.model_availability_nux]\n"m-a" = 4\n' \
  > "$SB/.codex/config.toml"
cat > "$SB/codex.edn" <<'EDN'
{:settings [{:id :user-codex :acli [:codex] :in :all :sandbox "dev"
             :keymap {:chat {:open_presets "alt-t"}}
             :presets {0 {:input "/model" :label "model picker"} 1 {:input "/status"}}
             :status-line [:model-with-reasoning :context-used]
             :permission-profiles {:dev {:extends ":workspace"
                                         :filesystem {"~/Library/Application Support/Example" "write"}}}}]}
EDN
run apply! -f "$SB/codex.edn" --all -t codex -y > "$SB/codex1.txt"
grep -q '^\[tui.presets.1\]' "$SB/.codex/config.toml" || fail "codex preset not written"
grep -q 'Application Support/Example" = "write"' "$SB/.codex/config.toml" || fail "codex profile not written"
set +e
run apply -f "$SB/codex.edn" --all -t codex > "$SB/codex2.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/codex2.txt"; fail "codex nested settings did not converge after a state.edn round trip"; }
python3 - "$SB/codex.edn" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
open(p, 'w').write(s.replace(' 1 {:input "/status"}', ''))
PY
run apply! -f "$SB/codex.edn" --all -t codex -y > "$SB/codex3.txt"
grep -q 'tui.presets.1' "$SB/.codex/config.toml" && fail "dropped codex preset slot not pruned"
set +e
run apply -f "$SB/codex.edn" --all -t codex > "$SB/codex4.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$SB/codex4.txt"; fail "codex settings did not converge after a prune"; }
python3 -c 'import sys,tomllib; t=tomllib.load(open(sys.argv[1],"rb")); assert t["tui"]["model_availability_nux"] == {"m-a": 4}, t; assert t["tui"]["status_line"] == ["model-with-reasoning", "context-used"], t' \
  "$SB/.codex/config.toml" || fail "codex [tui] state or footer wrong"

echo "30. what a config no longer declares is pruned, whatever is left of it"
PR="$SB/prune-any"
# pr <home> <args…>: each case gets a home of its own
pr() { local h="$PR/$1"; shift; (cd "$h" && AGENTCTL_HOME="$h" run "$@"); }
trusted() { grep -q 'trust_level = "trusted"' "$PR/$1/.codex/config.toml" 2>/dev/null; }
for h in vec empty old switch; do
  mkdir -p "$PR/$h/proj"
  printf '{:trust [{:in "%s/proj" :acli [:codex]}]}\n' "$PR/$h" > "$PR/$h/dsl.edn"
  printf '{:projects {:p {:path "%s/proj" :trusted true :executors #{:codex}}}}\n' "$PR/$h" > "$PR/$h/old.edn"
  printf '{:mcps []}\n' > "$PR/$h/vec.edn"
  printf '{}\n' > "$PR/$h/empty.edn"
done
# an emptied DSL config, as a section vector or as nothing at all
for h in vec empty; do
  pr "$h" apply! -f "$PR/$h/dsl.edn" --all -t codex -y > /dev/null
  trusted "$h" || fail "setup: DSL trust not written ($h)"
  pr "$h" apply! -f "$PR/$h/$h.edn" --all -t codex -y > "$PR/$h/prune.txt"
  trusted "$h" && { cat "$PR/$h/prune.txt"; fail "emptying the DSL config to $h.edn left its trust behind"; }
  set +e
  pr "$h" apply -f "$PR/$h/$h.edn" --all -t codex > "$PR/$h/after.txt"; code=$?
  set -e
  [ "$code" = 0 ] || { cat "$PR/$h/after.txt"; fail "$h.edn: the pruned trust is still owned"; }
done
# an old-format config prunes none of what it declares
pr old apply! -f "$PR/old/old.edn" --all -t codex -y > /dev/null
trusted old || fail "setup: old-format trust not written"
set +e
pr old apply -f "$PR/old/old.edn" --all -t codex > "$PR/old/again.txt"; code=$?
set -e
[ "$code" = 0 ] || { cat "$PR/old/again.txt"; fail "a converged old-format config planned a change"; }
# nor what a DSL config wrote that it declares the old way
pr switch apply! -f "$PR/switch/dsl.edn" --all -t codex -y > /dev/null
pr switch apply! -f "$PR/switch/old.edn" --all -t codex -y > "$PR/switch/old.txt"
trusted switch || { cat "$PR/switch/old.txt"; fail "rewriting the DSL trust the old way pruned it"; }

echo "31. --debug and AGENTCTL_DEBUG add the stack trace on stderr, nothing on stdout"
DB="$SB/debug"
mkdir -p "$DB/dir.edn"
db() { (cd "$DB" && AGENTCTL_HOME="$DB" run "$@"); }
for mode in flag env off; do
  set +e
  case $mode in
    flag) db apply -f "$DB/dir.edn" --debug > "$DB/$mode.out" 2> "$DB/$mode.err" ;;
    env) AGENTCTL_DEBUG=1 db apply -f "$DB/dir.edn" > "$DB/$mode.out" 2> "$DB/$mode.err" ;;
    off) AGENTCTL_DEBUG=0 db apply -f "$DB/dir.edn" > "$DB/$mode.out" 2> "$DB/$mode.err" ;;
  esac
  code=$?
  set -e
  [ "$code" = 1 ] && [ "$(wc -l < "$DB/$mode.out")" -eq 1 ] && grep -q '^error: .*Is a directory' "$DB/$mode.out" \
    || { cat "$DB/$mode.out" "$DB/$mode.err"; fail "$mode: the error line changed"; }
  if [ "$mode" = off ]; then
    [ -s "$DB/$mode.err" ] && { cat "$DB/$mode.err"; fail "AGENTCTL_DEBUG=0 printed a trace"; }
  else
    grep -q 'FileNotFoundException' "$DB/$mode.err" && grep -q '	at ' "$DB/$mode.err" \
      || { cat "$DB/$mode.err"; fail "$mode: no stack trace on stderr"; }
  fi
done
# under --json stdout stays the one document
set +e
db apply -f "$DB/dir.edn" --json --debug > "$DB/json.out" 2> "$DB/json.err"
set -e
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$DB/json.out" \
  || { cat "$DB/json.out"; fail "--debug broke the --json document"; }
grep -q 'FileNotFoundException' "$DB/json.err" || { cat "$DB/json.err"; fail "--json --debug printed no trace"; }

echo "42. a Claude settings.json that does not parse is refused, never rewritten"
(
  H=$(fresh_home broken-json); export AGENTCTL_HOME="$H"
  P="$H/projects/app"; mkdir -p "$P/.claude" "$H/.claude"; git -C "$P" init -q
  broken='{"env": {"KEEP": "1"}, "model": "m-a",}'
  printf '%s\n' "$broken" > "$H/.claude/settings.json"
  printf '%s\n' "$broken" > "$P/.claude/settings.json"
  cp "$H/.claude/settings.json" "$H/user.before"; cp "$P/.claude/settings.json" "$H/proj.before"
  cat > "$H/agents.edn" <<'EDN'
{:settings [{:id :user-claude :acli [:claude] :in :all :model "m-b"}
            {:id :app-claude :acli [:claude] :in "projects/app" :model "m-c"}]}
EDN
  (run apply -f "$H/agents.edn" --all -t claude || true) > "$H/plan.txt" 2>&1
  [ "$(grep -c 'settings.json is not valid JSON' "$H/plan.txt")" -ge 2 ] \
    || { cat "$H/plan.txt"; fail "the plan does not report both broken settings files"; }
  set +e
  run apply! -f "$H/agents.edn" --all -t claude -y > "$H/apply.txt" 2>&1; code=$?
  set -e
  [ "$code" = 1 ] || { cat "$H/apply.txt"; fail "apply! on broken settings.json exited $code, not 1"; }
  cmp -s "$H/.claude/settings.json" "$H/user.before" || fail "user settings.json was rewritten"
  cmp -s "$P/.claude/settings.json" "$H/proj.before" || fail "project settings.json was rewritten"
)

echo "all agentctl e2e checks passed"
