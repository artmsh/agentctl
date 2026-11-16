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

export AGENTCTL_HOME="$SB" HOME="$SB"
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
for cli in claude codex pi omp llm agy kimi; do printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"; done
chmod +x "$SB/bin/"*
export PATH="$SB/bin:$PATH"

vault() { # vault <env> <header> <key>
  printf '{"plant": {"ENV": "%s", "HDR": "%s", "KEY": "%s"}}\n' "$1" "$2" "$3" > "$SB/bw-items.json"
}

cat > "$SB/agents.edn" <<'EDN'
{:mcps [{:id :sdocs :cmd "/bin/echo s" :env {"DOCS_ENV" "!bw://plant/ENV" "MODE" "fast"} :in :all
         :acli [:pi :omp :kimi :antigravity :codex]}
        {:id :hdocs :type :http :url "https://mcp.example.com/h" :headers {"x-docs" "!bw://plant/HDR"} :in :all
         :acli [:kimi :antigravity :codex]}]
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

outputs() { # every output mode, each into its own file; apply! runs last
  local run=(--all "${TOOLS[@]}" -f "$SB/agents.edn")
  set +e
  "$ROOT/agentctl" apply "${run[@]}" > "$SB/plain.txt" 2>&1
  "$ROOT/agentctl" apply "${run[@]}" -v > "$SB/verbose.txt" 2>&1
  "$ROOT/agentctl" apply "${run[@]}" --json > "$SB/plan.json" 2>&1
  python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "tools": ["pi", "omp", "kimi", "antigravity", "codex"]}))' \
    "$SB/agents.edn" > "$SB/gui-body.json"
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
vault envsecretvalue111 hdrsecretvalue222 keysecretvalue333
outputs
for f in plain.txt plan.json gui.out; do
  grep -q "sdocs" "$SB/$f" || { cat "$SB/$f"; fail "$f does not plan the servers"; }
done
leaks envsecretvalue111 hdrsecretvalue222 keysecretvalue333
# masked, not missing: every tool's plan carries each of its secrets
python3 - "$SB/plan.json" <<'PY' || fail "a tool's plan does not carry its masked secrets"
import json, sys
ops = json.load(open(sys.argv[1]))["ops"]
want = {"pi": ["envs…11", "keys…33"], "omp": ["envs…11", "keys…33"],
        "kimi": ["envs…11", "hdrs…22", "keys…33"],
        "antigravity": ["envs…11", "hdrs…22"], "codex": ["hdrs…22"]}
for tool, masks in want.items():
    text = json.dumps([o for o in ops if o["tool"] == tool], ensure_ascii=False)
    for m in masks:
        assert m in text, (tool, m)
PY
grep -q 'hdrs…22' "$SB/verbose.txt" || fail "-v does not show the masked header"
grep -q envsecretvalue111 "$SB/.pi/agent/mcp.json" || fail "the secret was not written"

echo "2. rotated, neither the old values nor the new ones print"
vault envsecretvalue444 hdrsecretvalue555 keysecretvalue666
outputs --json
grep -q '"ops"' "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "apply! --json printed no plan"; }
leaks envsecretvalue111 hdrsecretvalue222 keysecretvalue333 envsecretvalue444 hdrsecretvalue555 keysecretvalue666
grep -q envsecretvalue444 "$SB/.pi/agent/mcp.json" || fail "the rotated secret was not written"

echo "all secrets e2e checks passed"
