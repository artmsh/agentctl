#!/usr/bin/env bash
# End-to-end: a resolved secret never prints, for any tool that writes one.
# Each adapter gets an MCP env var, an MCP header and a provider key from a
# `bw` stub, under names that say nothing about a credential, so rendering
# alone would print them. Every output mode is grepped for them: the plan,
# -v, --json, apply!, apply! --json and the GUI's plan — before the first
# write, and again after every secret is rotated, when the old values sit
# on the before side of each diff.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-secrets.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB" HOME="$SB" AUTH_TOK=authtokvalue42
unset DSH_HOME DSH_AGENTS_HOME KIMI_CODE_HOME
fail() { echo "FAIL: $1" >&2; exit 1; }
TOOLS=(-t pi -t omp -t kimi -t antigravity -t codex)

mkdir -p "$SB/bin"
cat > "$SB/bin/bw" <<'STUB'
#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
if a[:1] == ["status"]:
    print(json.dumps({"status": "unlocked"})); sys.exit(0)
if a[:2] == ["get", "item"]:
    items = json.load(open(os.path.join(os.environ["HOME"], "bw-items.json")))
    if a[2] not in items:
        print("Not found.", file=sys.stderr); sys.exit(1)
    print(json.dumps({"fields": [{"name": k, "value": v} for k, v in items[a[2]].items()]})); sys.exit(0)
sys.exit(1)
STUB
for cli in claude pi omp llm agy kimi; do printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"; done
cat > "$SB/bin/codex" <<'STUB'
#!/bin/sh
# refuses `mcp add` on demand, echoing its whole argv — secrets included
if [ -e "$HOME/codex-refuse" ] && [ "$1 $2" = "mcp add" ]; then echo "refused: $*" >&2; exit 1; fi
exit 0
STUB
chmod +x "$SB/bin/"*
export PATH="$SB/bin:$PATH"

vault() { # vault <env> <header> <key> <arg>; the rest are fixed
  printf '{"plant": {"ENV": "%s", "HDR": "%s", "KEY": "%s", "ARG": "%s", "SHORT": "Zq9x", "WORD": "config", "URL": "https://mcp.example.com/urlsecretpath99"}}\n' \
    "$1" "$2" "$3" "$4" > "$SB/bw-items.json"
}

cat > "$SB/agents.edn" <<'EDN'
{:mcps [{:id :sdocs :cmd "/bin/echo s --token !bw://plant/ARG" :env {"DOCS_ENV" "!bw://plant/ENV" "MODE" "fast" "WORKDIR" "$HOME"} :in :all
         :acli [:pi :omp :kimi :antigravity :codex]}
        {:id :hdocs :type :http :url "https://mcp.example.com/h" :headers {"x-docs" "!bw://plant/HDR"} :in :all
         :acli [:omp :kimi :antigravity :codex]}]
 :providers [{:id :gw :url "https://api.example.com/v1" :api "openai-completions" :key "!bw://plant/KEY"
              :models [{:id "m1" :context-window 100000}] :acli [:pi :omp :kimi] :in :all}]}
EDN

"$ROOT/agentctl" gui -f "$SB/agents.edn" --port 0 --no-open "${TOOLS[@]}" > "$SB/gui.log" 2>&1 &
GUI_PID=$!
trap 'kill "$GUI_PID" 2>/dev/null || true; rm -rf "$SB"' EXIT
for _ in $(seq 1 100); do grep -q 'http://127.0.0.1' "$SB/gui.log" && break; sleep 0.1; done
URL=$(grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$SB/gui.log" | head -1)
[ -n "$URL" ] || { cat "$SB/gui.log"; fail "gui did not print a URL to open"; }
BASE=${URL%%/\?t=*}
TOK=${URL##*t=}

outputs() { # every output mode for $CFG, each into its own file; apply! runs last
  local cfg=${CFG:-$SB/agents.edn}
  local run=(--all "${TOOLS[@]}" -f "$cfg")
  set +e
  "$ROOT/agentctl" apply "${run[@]}" > "$SB/plain.txt" 2>&1
  "$ROOT/agentctl" apply "${run[@]}" -v > "$SB/verbose.txt" 2>&1
  "$ROOT/agentctl" apply "${run[@]}" --json > "$SB/plan.json" 2>&1
  python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "tools": ["pi", "omp", "kimi", "antigravity", "codex"]}))' \
    "$cfg" > "$SB/gui-body.json"
  curl -sS -X POST -H "X-Agentctl-Token: $TOK" -H 'content-type: application/json' \
    --data-binary "@$SB/gui-body.json" "$BASE/api/plan" > "$SB/gui.out"
  "$ROOT/agentctl" apply! "${run[@]}" -y "$@" > "$SB/apply.txt" 2>&1
  set -e
}
leaks() { # leaks <value>...: fail when any output carries one
  for v in "$@"; do
    for f in plain.txt verbose.txt plan.json gui.out apply.txt; do
      grep -n "$v" "$SB/$f" && fail "$f printed the resolved secret $v"
    done
  done
  return 0
}

echo "1. a resolved MCP env var, header and provider key print in no output, for any tool"
vault envsecretvalue111 hdrsecretvalue222 keysecretvalue333 argsecretvalue777
outputs
for f in plain.txt plan.json gui.out; do
  grep -q "sdocs" "$SB/$f" || { cat "$SB/$f"; fail "$f does not plan the servers"; }
done
leaks envsecretvalue111 hdrsecretvalue222 keysecretvalue333 argsecretvalue777
# masked, not missing: every tool's plan carries each of its secrets
python3 - "$SB/plan.json" <<'PY' || fail "a tool's plan does not carry its masked secrets"
import json, sys
ops = json.load(open(sys.argv[1]))["ops"]
want = {"pi": ["envs…11", "keys…33", "args…77"], "omp": ["envs…11", "hdrs…22", "keys…33", "args…77"],
        "kimi": ["envs…11", "hdrs…22", "keys…33", "args…77"],
        "antigravity": ["envs…11", "hdrs…22", "args…77"], "codex": ["hdrs…22", "args…77"]}
for tool, masks in want.items():
    text = json.dumps([o for o in ops if o["tool"] == tool], ensure_ascii=False)
    for m in masks:
        assert m in text, (tool, m)
PY
grep -q 'hdrs…22' "$SB/verbose.txt" || fail "-v does not show the masked header"
# a reference to a directory is no secret: paths still print whole
grep -qF "\"target\" : \"$SB/.pi/agent/mcp.json\"" "$SB/plan.json" || { grep -n target "$SB/plan.json"; fail "--json mangled a target path"; }
grep -qF "WORKDIR: \"$SB\"" "$SB/verbose.txt" || { cat "$SB/verbose.txt"; fail "a directory reference was masked"; }
grep -q envsecretvalue111 "$SB/.pi/agent/mcp.json" || fail "the secret was not written"
grep -q argsecretvalue777 "$SB/.pi/agent/mcp.json" || fail "the argument reference was not resolved"
python3 - "$SB/.omp/agent/mcp.json" <<'PY' || fail "omp's http server was written without its header"
import json, sys
assert json.load(open(sys.argv[1]))["mcpServers"]["hdocs"]["headers"] == {"x-docs": "hdrsecretvalue222"}
PY

echo "2. rotated, neither the old values nor the new ones print"
vault envsecretvalue444 hdrsecretvalue555 keysecretvalue666 argsecretvalue888
outputs --json
grep -q '"ops"' "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "apply! --json printed no plan"; }
leaks envsecretvalue111 hdrsecretvalue222 keysecretvalue333 argsecretvalue777 \
  envsecretvalue444 hdrsecretvalue555 keysecretvalue666 argsecretvalue888
grep -q envsecretvalue444 "$SB/.pi/agent/mcp.json" || fail "the rotated secret was not written"

echo "3. an llm provider whose key does not resolve is held back whole, not written keyless"
LLM_DIR="$SB/Library/Application Support/io.datasette.llm"
cat > "$SB/llm.edn" <<'EDN'
{:providers [{:id :gw :url "https://api.example.com/v1" :api "openai-completions" :key "!bw://plant/NOPE"
              :models [{:id "m1"}] :acli [:llm] :in :all}]}
EDN
set +e
"$ROOT/agentctl" apply --all -t llm -f "$SB/llm.edn" > "$SB/llm-plan.txt" 2>&1; code=$?
"$ROOT/agentctl" apply! --all -t llm -f "$SB/llm.edn" -y > "$SB/llm-apply.txt" 2>&1; bang=$?
set -e
[ "$code" = 2 ] || { cat "$SB/llm-plan.txt"; fail "apply exited $code on an unresolved llm key, not 2"; }
[ "$bang" = 1 ] || { cat "$SB/llm-apply.txt"; fail "apply! exited $bang on an unresolved llm key, not 1"; }
grep -q 'providers/gw' "$SB/llm-plan.txt" && grep -q 'secret unresolved: field not found on bw item: plant/NOPE' "$SB/llm-plan.txt" \
  || { cat "$SB/llm-plan.txt"; fail "the plan does not say the llm key is unresolved"; }
[ ! -e "$LLM_DIR/extra-openai-models.yaml" ] || { cat "$LLM_DIR/extra-openai-models.yaml"; fail "the keyless llm models were written"; }

echo "4. an llm provider written once and then unresolved is held back, not pruned"
sed -i.bak 's|plant/NOPE|plant/KEY|' "$SB/llm.edn"
set +e; "$ROOT/agentctl" apply! --all -t llm -f "$SB/llm.edn" -y > "$SB/llm-apply.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] || { cat "$SB/llm-apply.txt"; fail "apply! of a resolved llm provider exited $code"; }
grep -q keysecretvalue666 "$LLM_DIR/keys.json" || fail "the llm key was not stored"
cp "$LLM_DIR/extra-openai-models.yaml" "$SB/models.before"; cp "$LLM_DIR/keys.json" "$SB/keys.before"
sed -i.bak 's|plant/KEY|plant/NOPE|' "$SB/llm.edn"
set +e
"$ROOT/agentctl" apply --all -t llm -f "$SB/llm.edn" > "$SB/llm-plan.txt" 2>&1; code=$?
"$ROOT/agentctl" apply! --all -t llm -f "$SB/llm.edn" -y > "$SB/llm-apply.txt" 2>&1; bang=$?
set -e
[ "$code" = 2 ] && [ "$bang" = 1 ] || { cat "$SB/llm-plan.txt" "$SB/llm-apply.txt"; fail "an unresolved llm key exited $code/$bang, not 2/1"; }
grep -q '^.*- ' "$SB/llm-plan.txt" && { cat "$SB/llm-plan.txt"; fail "an unresolved llm key plans a removal"; }
cmp -s "$SB/models.before" "$LLM_DIR/extra-openai-models.yaml" || fail "an unresolved llm key changed the models"
cmp -s "$SB/keys.before" "$LLM_DIR/keys.json" || fail "an unresolved llm key changed keys.json"

echo "5. a CLI that refuses, echoing its argv, prints no secret in the failure"
touch "$SB/codex-refuse"
for mode in text json; do
  set +e
  if [ $mode = json ]; then
    "$ROOT/agentctl" apply! --all -t codex -f "$SB/agents.edn" -y --json > "$SB/fail.txt" 2>&1; code=$?
  else
    "$ROOT/agentctl" apply! --all -t codex -f "$SB/agents.edn" -y > "$SB/fail.txt" 2>&1; code=$?
  fi
  set -e
  [ "$code" = 1 ] || { cat "$SB/fail.txt"; fail "$mode: a refused add exited $code, not 1"; }
  grep -q 'refused' "$SB/fail.txt" || { cat "$SB/fail.txt"; fail "$mode: the failure is not reported"; }
  grep -nE 'envsecretvalue444|hdrsecretvalue555|argsecretvalue888' "$SB/fail.txt" && fail "$mode: the failure printed a secret"
done
rm "$SB/codex-refuse"

echo "6. an argument reference that does not resolve holds its server back, for every tool"
cat > "$SB/args.edn" <<'EDN'
{:mcps [{:id :adocs :command "/bin/echo" :args ["--token=!bw://plant/NOPE"] :in :all
         :acli [:claude :pi :omp :kimi :antigravity :codex]}]}
EDN
for t in claude pi omp kimi antigravity codex; do
  set +e; "$ROOT/agentctl" apply --all -t $t -k mcps -f "$SB/args.edn" > "$SB/args.txt" 2>&1; code=$?; set -e
  [ "$code" = 2 ] || { cat "$SB/args.txt"; fail "$t: an unresolved argument exited $code, not 2"; }
  grep -qF 'secret unresolved: field not found on bw item: plant/NOPE (args.--token)' "$SB/args.txt" \
    || { cat "$SB/args.txt"; fail "$t: the unresolved argument is not named"; }
done
set +e; "$ROOT/agentctl" validate -f "$SB/args.edn" > "$SB/args.txt" 2>&1; set -e
grep -qF -- '--token' "$SB/args.txt" || { cat "$SB/args.txt"; fail "validate does not report the unresolved argument"; }

refused() { # refused <cfg> <value>...: a refused codex add prints none of them
  local cfg=$1; shift
  touch "$SB/codex-refuse"
  for mode in text json; do
    set +e
    if [ $mode = json ]; then
      "$ROOT/agentctl" apply! --all -t codex -f "$cfg" -y --json > "$SB/fail.txt" 2>&1; code=$?
    else
      "$ROOT/agentctl" apply! --all -t codex -f "$cfg" -y > "$SB/fail.txt" 2>&1; code=$?
    fi
    set -e
    [ "$code" = 1 ] && grep -q 'refused' "$SB/fail.txt" || { cat "$SB/fail.txt"; fail "$mode: the refused add is not reported"; }
    for v in "$@"; do grep -n -- "$v" "$SB/fail.txt" && fail "$mode: the failure printed $v"; done
  done
  rm "$SB/codex-refuse"
}

echo "7. a short secret in argv prints nowhere: summary, diff, GUI, apply! and a failure"
cat > "$SB/short.edn" <<'EDN'
{:mcps [{:id :tdocs :command "/bin/echo" :args ["--token" "!bw://plant/SHORT"] :in :all
         :acli [:pi :omp :kimi :antigravity :codex]}
        {:id :kdocs :command "/bin/echo" :args ["--api-key=!bw://plant/SHORT"] :in :all
         :acli [:pi :omp :kimi :antigravity :codex]}]}
EDN
CFG="$SB/short.edn" outputs
grep -q tdocs "$SB/plain.txt" || { cat "$SB/plain.txt"; fail "the short-secret servers are not planned"; }
leaks Zq9x
refused "$SB/short.edn" Zq9x

echo "8. a secret on disk that the declaration no longer resolves prints nowhere"
cat > "$SB/stale.edn" <<'EDN'
{:mcps [{:id :odocs :command "/bin/echo" :args ["--api-key=!bw://plant/ARG" "--token" "!bw://plant/ARG"]
         :env {"DOCS" "!bw://plant/ENV"} :in :all :acli [:pi :omp :kimi :antigravity :codex]}
        {:id :ohttp :type :http :url "https://mcp.example.com/o" :headers {"x-docs" "!bw://plant/HDR"} :in :all
         :acli [:omp :kimi :antigravity :codex]}]}
EDN
set +e; "$ROOT/agentctl" apply! --all "${TOOLS[@]}" -f "$SB/stale.edn" -y > "$SB/apply.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! of the stale-secret servers exited $code"; }
grep -q argsecretvalue888 "$SB/.pi/agent/mcp.json" || fail "the argument secret was not written"
sed -i.bak 's|plant/ARG|plant/SHORT|g; s|plant/ENV|plant/SHORT|; s|plant/HDR|plant/SHORT|' "$SB/stale.edn"
CFG="$SB/stale.edn" outputs
grep -q odocs "$SB/plain.txt" || { cat "$SB/plain.txt"; fail "the switched servers are not planned"; }
leaks argsecretvalue888 envsecretvalue444 hdrsecretvalue555 Zq9x

echo "9. an env var dropped from a server goes from disk, and no output prints it"
cat > "$SB/drop.edn" <<'EDN'
{:mcps [{:id :edocs :command "/bin/echo" :env {"DOCS" "!bw://plant/ENV" "MODE" "fast"} :in :all
         :acli [:pi :omp :kimi :antigravity]}
        {:id :ehttp :type :http :url "https://mcp.example.com/e" :headers {"x-docs" "!bw://plant/HDR" "x-mode" "fast"} :in :all
         :acli [:omp :kimi :antigravity]}]}
EDN
set +e; "$ROOT/agentctl" apply! --all "${TOOLS[@]}" -f "$SB/drop.edn" -y > "$SB/apply.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! of the env servers exited $code"; }
sed -i.bak 's|"DOCS" "!bw://plant/ENV" ||; s|"x-docs" "!bw://plant/HDR" ||' "$SB/drop.edn"
CFG="$SB/drop.edn" outputs
grep -q edocs "$SB/plan.json" || { cat "$SB/plan.json"; fail "the dropped env var is not planned"; }
leaks envsecretvalue444 hdrsecretvalue555
for f in .pi/agent/mcp.json .omp/agent/mcp.json .kimi-code/mcp.json .gemini/config/mcp_config.json; do
  python3 - "$SB/$f" <<'PY' || fail "$f still holds the dropped env var or header"
import json, sys
ms = json.load(open(sys.argv[1]))["mcpServers"]
assert "DOCS" not in ms["edocs"].get("env", {}), ms["edocs"]
assert ms["edocs"]["env"]["MODE"] == "fast", ms["edocs"]
assert "ehttp" not in ms or "x-docs" not in ms["ehttp"].get("headers", {}), ms.get("ehttp")
PY
done

echo "10. a secret that is also a word in a URL or a path leaves them readable"
cat > "$SB/word.edn" <<'EDN'
{:mcps [{:id :wdocs :type :http :url "https://config.example.com/w" :headers {"x-site" "!bw://plant/WORD"} :in :all
         :acli [:kimi :antigravity :codex]}]}
EDN
CFG="$SB/word.edn" outputs
grep -qF 'https://config.example.com/w' "$SB/plan.json" || { grep -n url "$SB/plan.json"; fail "--json masked a word inside a URL"; }
grep -qF "$SB/.gemini/config/mcp_config.json" "$SB/plan.json" || { grep -n target "$SB/plan.json"; fail "--json masked a word inside a path"; }
grep -qF '"x-site" : "********"' "$SB/plan.json" || { grep -n x-site "$SB/plan.json"; fail "the secret itself was not masked"; }

echo "11. a reference as an http server's URL is resolved, masked, and held back when it does not resolve"
cat > "$SB/url.edn" <<'EDN'
{:mcps [{:id :udocs :type :http :url "!bw://plant/URL" :in :all :acli [:omp :kimi :antigravity :codex]}]}
EDN
CFG="$SB/url.edn" outputs
leaks urlsecretpath99
grep -q 'urlsecretpath99' "$SB/.kimi-code/mcp.json" || fail "the resolved URL was not written"
grep -q '!bw://' "$SB/.kimi-code/mcp.json" && fail "the URL reference was written as text"
sed -i.bak 's|plant/URL|plant/NOPE|' "$SB/url.edn"
for t in omp kimi antigravity codex; do
  set +e; "$ROOT/agentctl" apply --all -t $t -k mcps -f "$SB/url.edn" > "$SB/url.txt" 2>&1; code=$?; set -e
  [ "$code" = 2 ] && grep -qF '(url.url)' "$SB/url.txt" || { cat "$SB/url.txt"; fail "$t: an unresolved URL is not held back"; }
done
set +e; "$ROOT/agentctl" validate -f "$SB/url.edn" > "$SB/url.txt" 2>&1; set -e
grep -qF 'udocs.url' "$SB/url.txt" || { cat "$SB/url.txt"; fail "validate does not report the unresolved URL"; }

held() { # held <cfg> <place> <tool>...: each tool holds the server back, naming the place
  local cfg=$1 place=$2; shift 2
  for t in "$@"; do
    set +e; "$ROOT/agentctl" apply --all -t $t -k mcps -f "$cfg" > "$SB/held.txt" 2>&1; code=$?; set -e
    [ "$code" = 2 ] && grep -qF "plant/NOPE ($place)" "$SB/held.txt" \
      || { cat "$SB/held.txt"; fail "$t: an unresolved reference at $place is not held back"; }
  done
}
written() { # written <server> <python expr over its pi entry `e`>
  python3 - "$SB/.pi/agent/mcp.json" "$1" "$2" <<'PY' || fail "pi's $1 was not written resolved"
import json, sys
e = json.load(open(sys.argv[1]))["mcpServers"][sys.argv[2]]
assert eval(sys.argv[3]), e
PY
}

echo "12. a reference after -t, -k, --auth or --key is resolved, masked and held back"
cat > "$SB/flags.edn" <<'EDN'
{:mcps [{:id :fdocs :command "/bin/echo" :args ["-t" "!bw://plant/ARG" "--auth" "$AUTH_TOK" "--key" "!bw://plant/ARG" "-k" "$AUTH_TOK"]
         :in :all :acli [:pi :omp :kimi :antigravity :codex]}]}
EDN
CFG="$SB/flags.edn" outputs
grep -q fdocs "$SB/plain.txt" || { cat "$SB/plain.txt"; fail "the flag servers are not planned"; }
leaks argsecretvalue888 authtokvalue42
written fdocs 'e["args"] == ["-t", "argsecretvalue888", "--auth", "authtokvalue42", "--key", "argsecretvalue888", "-k", "authtokvalue42"]'
sed -i.bak 's|"-t" "!bw://plant/ARG"|"-t" "!bw://plant/NOPE"|' "$SB/flags.edn"
held "$SB/flags.edn" 'args.-t' pi omp kimi antigravity codex

echo "13. a reference inside one argument is resolved, masked and held back"
cat > "$SB/inner.edn" <<'EDN'
{:mcps [{:id :idocs :command "/bin/echo" :args ["--header" "Authorization: Bearer !bw://plant/ARG"]
         :in :all :acli [:pi :omp :kimi :antigravity :codex]}
        {:id :ihttp :type :http :url "https://mcp.example.com/i" :headers {"Authorization" "Bearer $AUTH_TOK"}
         :in :all :acli [:omp :kimi :antigravity :codex]}]}
EDN
CFG="$SB/inner.edn" outputs
leaks argsecretvalue888 authtokvalue42
written idocs 'e["args"] == ["--header", "Authorization: Bearer argsecretvalue888"]'
python3 - "$SB/.kimi-code/mcp.json" <<'PY' || fail "a variable inside a header was not resolved"
import json, sys
assert json.load(open(sys.argv[1]))["mcpServers"]["ihttp"]["headers"] == {"Authorization": "Bearer authtokvalue42"}
PY
sed -i.bak 's|Bearer !bw://plant/ARG|Bearer !bw://plant/NOPE|' "$SB/inner.edn"
held "$SB/inner.edn" 'args.args[1]' pi omp kimi antigravity codex

echo "14. a reference inside a URL is resolved, masked and held back"
cat > "$SB/query.edn" <<'EDN'
{:mcps [{:id :qdocs :type :http :url "https://mcp.example.com/q?k=!bw://plant/ARG&v=1" :in :all
         :acli [:omp :kimi :antigravity :codex]}
        {:id :vdocs :type :http :url "https://mcp.example.com/v?k=$AUTH_TOK" :in :all
         :acli [:omp :kimi :antigravity :codex]}]}
EDN
CFG="$SB/query.edn" outputs
leaks argsecretvalue888 authtokvalue42
python3 - "$SB/.kimi-code/mcp.json" <<'PY' || fail "a reference inside a URL was not resolved"
import json, sys
ms = json.load(open(sys.argv[1]))["mcpServers"]
assert ms["qdocs"]["url"] == "https://mcp.example.com/q?k=argsecretvalue888&v=1", ms["qdocs"]
assert ms["vdocs"]["url"] == "https://mcp.example.com/v?k=authtokvalue42", ms["vdocs"]
PY
sed -i.bak 's|k=!bw://plant/ARG|k=!bw://plant/NOPE|' "$SB/query.edn"
held "$SB/query.edn" 'url.url' omp kimi antigravity codex

echo "15. a spelling nothing resolves is a plan and validate finding, never silent"
cat > "$SB/text.edn" <<'EDN'
{:mcps [{:id :xdocs :command "/bin/echo" :args ["--opt" "$AGENTCTL_TEST_NEVER_SET"] :env {"MODE" "x !cmd://echo hi"}
         :in :all :acli [:pi :kimi]}]}
EDN
set +e; "$ROOT/agentctl" apply --all -t pi -t kimi -f "$SB/text.edn" > "$SB/text.txt" 2>&1; set -e
for t in PI KIMI; do
  sed -n "/^$t\$/,/^\$/p" "$SB/text.txt" | grep -qF 'args[1] holds `$AGENTCTL_TEST_NEVER_SET`, which nothing resolves there — written as text' \
    || { cat "$SB/text.txt"; fail "$t: the plan does not report the unset variable"; }
done
grep -qF 'env.MODE holds `!cmd://…`' "$SB/text.txt" || { cat "$SB/text.txt"; fail "the plan does not report the embedded command"; }
set +e; "$ROOT/agentctl" validate -f "$SB/text.edn" > "$SB/text.txt" 2>&1; set -e
grep -qF 'xdocs.args[1]' "$SB/text.txt" && grep -qF 'xdocs.env.MODE' "$SB/text.txt" \
  || { cat "$SB/text.txt"; fail "validate does not report the spellings written as text"; }

pi_args() { # pi_args <server>: its argv as JSON
  python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["mcpServers"][sys.argv[2]]["args"]))' "$SB/.pi/agent/mcp.json" "$1"
}

echo "16. two references in one string are both resolved, and the text between them kept"
cat > "$SB/two.edn" <<'EDN'
{:mcps [{:id :twodocs :command "/bin/echo" :args ["--creds=!bw://plant/ARG:!bw://plant/SHORT" "!bw://plant/ARG !bw://plant/SHORT"]
         :in :all :acli [:pi]}]}
EDN
set +e; "$ROOT/agentctl" apply! --all -t pi -f "$SB/two.edn" -y > "$SB/apply.txt" 2>&1; set -e
[ "$(pi_args twodocs)" = '["--creds=argsecretvalue888:Zq9x", "argsecretvalue888 Zq9x"]' ] \
  || { cat "$SB/apply.txt"; pi_args twodocs; fail "two references in one string were not both resolved"; }

echo "17. a !cmd:// inside an argument is text with a warning, never run"
cat > "$SB/cmd.edn" <<'EDN'
{:mcps [{:id :cmddocs :command "/bin/echo" :args ["--opt=!cmd://echo hi" "pre !cmd://echo t"] :in :all :acli [:pi]}]}
EDN
set +e
"$ROOT/agentctl" apply --all -t pi -f "$SB/cmd.edn" > "$SB/cmd.txt" 2>&1
"$ROOT/agentctl" apply! --all -t pi -f "$SB/cmd.edn" -y > "$SB/apply.txt" 2>&1
set -e
[ "$(pi_args cmddocs)" = '["--opt=!cmd://echo hi", "pre !cmd://echo t"]' ] || { pi_args cmddocs; fail "an embedded command ran"; }
grep -qF 'args[0] holds `!cmd://…`' "$SB/cmd.txt" && grep -qF 'args[1] holds `!cmd://…`' "$SB/cmd.txt" \
  || { cat "$SB/cmd.txt"; fail "an embedded command is not reported"; }

echo "18. a reference glued to text is bounded by !{…}"
cat > "$SB/dsn.edn" <<'EDN'
{:mcps [{:id :dsndocs :command "/bin/echo" :args ["--dsn=postgres://app:!{bw://plant/ARG}@db/x" "pre !{cmd://echo z}"] :in :all :acli [:pi]}]}
EDN
set +e
"$ROOT/agentctl" apply --all -t pi -f "$SB/dsn.edn" > "$SB/dsnplan.txt" 2>&1
"$ROOT/agentctl" apply! --all -t pi -f "$SB/dsn.edn" -y > "$SB/apply.txt" 2>&1
set -e
grep -qF 'args[1] holds `!{cmd://…`' "$SB/dsnplan.txt" || { cat "$SB/dsnplan.txt"; fail "a braced command is not reported"; }
[ "$(pi_args dsndocs)" = '["--dsn=postgres://app:argsecretvalue888@db/x", "pre !{cmd://echo z}"]' ] || { cat "$SB/apply.txt"; pi_args dsndocs; fail "a braced reference was not resolved"; }

echo "19. a reference dropped from inside an argument leaves its old secret unprinted"
cat > "$SB/gone.edn" <<'EDN'
{:mcps [{:id :gdocs :command "/bin/echo" :args ["--opt=x !bw://plant/ARG y"] :in :all :acli [:pi :kimi]}]}
EDN
set +e; "$ROOT/agentctl" apply! --all -t pi -t kimi -f "$SB/gone.edn" -y > "$SB/apply.txt" 2>&1; code=$?; set -e
[ "$code" = 0 ] && [ "$(pi_args gdocs)" = '["--opt=x argsecretvalue888 y"]' ] || { cat "$SB/apply.txt"; fail "the embedded reference was not written resolved"; }
st="$SB/.config/agentctl/state.edn"
grep -q argsecretvalue888 "$st" && fail "the state file keeps a resolved value"
[ "$(python3 -c 'import os,sys; print(oct(os.stat(sys.argv[1]).st_mode & 0o777))' "$st")" = 0o600 ] || fail "the state file holding digests is readable by others"
sed -i.bak 's|"--opt=x !bw://plant/ARG y"|"--opt=x y"|' "$SB/gone.edn"
CFG="$SB/gone.edn" outputs
grep -q gdocs "$SB/plain.txt" || { cat "$SB/plain.txt"; fail "the dropped reference is not planned"; }
leaks argsecretvalue888

echo "all secrets e2e checks passed"
