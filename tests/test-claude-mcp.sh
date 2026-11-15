#!/usr/bin/env bash
# End-to-end: Claude Code MCP servers and the secrets they carry. A `claude`
# stub does what the real CLI does with `mcp add`/`mcp remove --scope user`
# (writes ~/.claude.json, which agentctl then reads back), and a `bw` stub
# serves a vault that is locked or holds the fields in $SB/bw-items.json.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-claude-mcp.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB" HOME="$SB"
unset DSH_HOME DSH_AGENTS_HOME AGENTCTL_TEST_UNSET
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
plan() { set +e; run apply -f "$SB/agents.edn" --all -t claude "$@" --verbose > "$SB/plan.txt" 2>&1; code=$?; set -e; }
apply() { set +e; run apply! -f "$SB/agents.edn" --all -t claude "$@" -y > "$SB/apply.txt" 2>&1; code=$?; set -e; }
json() { # json <file> <python expr over d>
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$1"
}
local_entry() { # local_entry <project dir> <server> -> the entry in ~/.claude.json, or None
  python3 - "$SB/.claude.json" "$1" "$2" <<'PY'
import json, os, sys
f, proj, name = sys.argv[1:]
d = json.load(open(f)) if os.path.exists(f) else {}
print(json.dumps(d.get("projects", {}).get(proj, {}).get("mcpServers", {}).get(name), sort_keys=True))
PY
}
vault() { # vault <json of item -> {field: value}>
  printf '%s\n' "$1" > "$SB/bw-items.json"
}

mkdir -p "$SB/bin"
cat > "$SB/bin/claude" <<'STUB'
#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
open(os.path.join(os.environ["HOME"], "claude-calls.log"), "a").write(" ".join(a) + "\n")
f = os.path.join(os.environ["HOME"], ".claude.json")
d = json.load(open(f)) if os.path.exists(f) else {}
if a[:2] == ["mcp", "remove"]:
    d.get("mcpServers", {}).pop(a[-1], None)
elif a[:2] == ["mcp", "add"]:
    rest = a[2:]; i = 0; transport = None; headers = {}; env = {}; pos = []
    while i < len(rest):
        x = rest[i]
        if x == "--scope": i += 2; continue
        if x == "--transport": transport = rest[i + 1]; i += 2; continue
        if x == "--header": k, v = rest[i + 1].split(": ", 1); headers[k] = v; i += 2; continue
        if x == "-e": k, v = rest[i + 1].split("=", 1); env[k] = v; i += 2; continue
        if x == "--": pos += rest[i + 1:]; break
        pos.append(x); i += 1
    if transport == "http":
        e = {"type": "http", "url": pos[1]}
        if headers: e["headers"] = headers
    else:
        # the real CLI writes an empty env object for a server without one
        e = {"type": "stdio", "command": pos[1], "args": pos[2:], "env": env}
    d.setdefault("mcpServers", {})[pos[0]] = e
else:
    sys.exit(0)
json.dump(d, open(f, "w"), indent=2)
STUB
cat > "$SB/bin/bw" <<'STUB'
#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
if a[:1] == ["status"]:
    print(json.dumps({"status": os.environ.get("BW_STATE", "unlocked")})); sys.exit(0)
if a[:2] == ["get", "item"]:
    items = json.load(open(os.path.join(os.environ["HOME"], "bw-items.json")))
    if a[2] not in items:
        print("Not found.", file=sys.stderr); sys.exit(1)
    print(json.dumps({"fields": [{"name": k, "value": v} for k, v in items[a[2]].items()]})); sys.exit(0)
sys.exit(1)
STUB
for cli in codex pi omp llm agy kimi; do printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"; done
chmod +x "$SB/bin/"*
export PATH="$SB/bin:$PATH"

git init -q "$SB/proj"
SECRET=sk-test-0123456789abcdef

# ---------------------------------------------------------------- unresolved secrets

cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :docs :type :http :url "https://mcp.example.com/docs" :headers {"api-key" "!bw://vendor/KEY"} :in :all :acli [:claude]}
        {:id :search :cmd "/bin/echo search" :env {"SEARCH_TOKEN" "!env://AGENTCTL_TEST_UNSET"} :in :all :acli [:claude]}
        {:id :plain :cmd "/bin/echo plain" :in :all :acli [:claude]}
        {:id :pdocs :type :http :url "https://mcp.example.com/docs" :headers {"api-key" "!bw://vendor/KEY"} :in "proj" :acli [:claude]}
        {:id :rdocs :type :http :url "https://mcp.example.com/docs" :headers {"api-key" "!bw://vendor/KEY"} :in "proj" :at :repo :acli [:claude]}]}
EDN
vault '{"vendor": {"KEY": "'$SECRET'"}}'
# a server the user added by hand, under a name the config now declares
python3 -c "import json,sys; json.dump({'mcpServers': {'docs': {'type': 'http', 'url': 'https://hand.example.com'}}}, open(sys.argv[1], 'w'))" "$SB/.claude.json"

echo "1. a locked vault holds back every server whose secret it keeps, and says why"
export BW_STATE=locked
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "an unresolved secret must not read as converged: exit $code"; }
for id in docs pdocs rdocs; do
  grep -A1 "mcps/$id" "$SB/plan.txt" | grep -q "⚠ secret unresolved: bitwarden vault locked (headers.api-key)" \
    || { cat "$SB/plan.txt"; fail "$id: unresolved header not reported"; }
done
grep -A1 "mcps/search" "$SB/plan.txt" | grep -q "⚠ secret unresolved: env var unset: AGENTCTL_TEST_UNSET (env.SEARCH_TOKEN)" \
  || { cat "$SB/plan.txt"; fail "search: unresolved env not reported"; }
grep -q "4 unresolved" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "summary does not count the unresolved servers"; }

echo "2. apply! writes the rest, nothing partial, and exits non-zero"
apply -k mcps
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "apply! with unresolved secrets exited $code"; }
grep -q "4 resource(s) not written" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "apply! does not name what it held back"; }
[ "$(json "$SB/.claude.json" "d['mcpServers']['plain']['command']")" = /bin/echo ] || fail "a server with no secret was not applied"
[ "$(json "$SB/.claude.json" "d['mcpServers']['docs']['url']")" = https://hand.example.com ] || fail "the hand-made server was replaced"
[ "$(json "$SB/.claude.json" "'search' in d['mcpServers']")" = False ] || fail "a stdio server was added without its env"
[ "$(local_entry "$SB/proj" pdocs)" = null ] || fail "a local-scope server was written without its header"
[ ! -e "$SB/proj/.mcp.json" ] || fail ".mcp.json was written without its header"
grep -q "mcp add.*docs" "$SB/claude-calls.log" && fail "claude mcp add ran for a server whose secret did not resolve"
grep -q '\[:claude :mcps "plain"\]' "$SB/.config/agentctl/state.edn" || fail "the written server is not owned"
grep -qE '\[:claude :mcps "([^"]*/)?(docs|search|pdocs|rdocs)"\]' "$SB/.config/agentctl/state.edn" \
  && fail "ownership claimed for a server that was never written"

echo "3. a server never written is not owned, so dropping it leaves the hand-made one alone"
cp "$SB/agents.edn" "$SB/agents-full.edn"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :plain :cmd "/bin/echo plain" :in :all :acli [:claude]}]}
EDN
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! failed: exit $code"; }
[ "$(json "$SB/.claude.json" "d['mcpServers']['docs']['url']")" = https://hand.example.com ] || fail "a hand-made server was pruned"
cp "$SB/agents-full.edn" "$SB/agents.edn"

echo "4. a vault field holding \"null\" or \"\" is missing, not a value"
export BW_STATE=unlocked
for empty in null ''; do
  vault '{"vendor": {"KEY": "'$empty'"}}'
  plan -k mcps
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "an empty vault field ('$empty') read as converged"; }
  grep -q "⚠ secret unresolved: field is empty on bw item: vendor/KEY (headers.api-key)" "$SB/plan.txt" \
    || { cat "$SB/plan.txt"; fail "an empty vault field ('$empty') was not reported"; }
  apply -k mcps
  [ "$(local_entry "$SB/proj" pdocs)" = null ] || fail "an empty vault field ('$empty') was written as a header"
done

echo "5. a resolved secret is written in every placement, and converges"
vault '{"vendor": {"KEY": "'$SECRET'"}}'
export AGENTCTL_TEST_UNSET=from-env
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['mcpServers'].pop('docs'); json.dump(d, open(f, 'w'))" "$SB/.claude.json"
plan -k mcps
grep -q "$SECRET" "$SB/plan.txt" && { cat "$SB/plan.txt"; fail "the plan printed a resolved secret"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! with a resolved secret exited $code"; }
[ "$(json "$SB/.claude.json" "d['mcpServers']['docs']['headers']['api-key']")" = "$SECRET" ] || fail "user-scope header not written"
[ "$(json "$SB/.claude.json" "d['mcpServers']['search']['env']['SEARCH_TOKEN']")" = from-env ] || fail "user-scope env not written"
[ "$(local_entry "$SB/proj" pdocs | python3 -c 'import json,sys; print(json.load(sys.stdin)["headers"]["api-key"])')" = "$SECRET" ] \
  || fail "local-scope header not written"
[ "$(json "$SB/proj/.mcp.json" "d['mcpServers']['rdocs']['headers']['api-key']")" = "$SECRET" ] || fail ".mcp.json header not written"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after a resolved apply!: exit $code"; }

echo "6. a header or env name missing from the user-scope entry is drift; its value is not compared"
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['mcpServers']['docs']['headers']['api-key']='rotated'; json.dump(d, open(f, 'w'))" "$SB/.claude.json"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a header value was compared: exit $code"; }
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['mcpServers']['docs'].pop('headers'); d['mcpServers']['search']['env']={}; json.dump(d, open(f, 'w'))" "$SB/.claude.json"
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a missing header name was not drift: exit $code"; }
grep -q '+ headers: \["api-key"\]' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the plan does not name the missing header"; }
grep -q '+ env: \["SEARCH_TOKEN"\]' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the plan does not name the missing env var"; }
grep -q "$SECRET" "$SB/plan.txt" && fail "the plan printed a resolved secret"
apply -k mcps
[ "$(json "$SB/.claude.json" "d['mcpServers']['docs']['headers']['api-key']")" = "$SECRET" ] || fail "a missing header was not restored"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after restoring a header: exit $code"; }

echo "all claude mcp e2e checks passed"
