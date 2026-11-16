#!/usr/bin/env bash
# End-to-end: Claude Code MCP servers and settings, and the secrets they
# carry. A `claude` stub does what the real CLI does with `mcp add`/`mcp
# remove --scope user` (writes ~/.claude.json, which agentctl then reads
# back) and, on every run, what Claude Code 2.1.325 does with a project's
# legacy MCP approvals: it moves `projects.<cwd>.enabledMcpjsonServers` out
# of ~/.claude.json into <cwd>/.claude/settings.local.json and deletes the
# key. A `bw` stub serves a vault that is locked or holds the fields in
# $SB/bw-items.json.
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
here = os.path.realpath(os.getcwd())
legacy = d.get("projects", {}).get(here, {}).pop("enabledMcpjsonServers", None)
if legacy is not None:
    local = os.path.join(here, ".claude", "settings.local.json")
    s = json.load(open(local)) if os.path.exists(local) else {}
    cur = s.get("enabledMcpjsonServers", [])
    s["enabledMcpjsonServers"] = cur + [x for x in legacy if x not in cur]
    os.makedirs(os.path.dirname(local), exist_ok=True)
    json.dump(s, open(local, "w"), indent=2)
refuse = os.path.join(os.environ["HOME"], "stub-refuse")
refused = os.environ.get("STUB_REFUSE") or (open(refuse).read().strip() if os.path.exists(refuse) else None)
if a[:2] == ["mcp", refused]:
    # a CLI may echo what it was given, values and all
    print("Error: refused by the stub: " + " ".join(a), file=sys.stderr); sys.exit(1)
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

# one GUI for the whole suite, started before any step locks the vault. It
# caches what a ref resolves to for its lifetime, so each GUI check reads a
# vault item no other step changes
printf '{}\n' > "$SB/agents.edn"
"$ROOT/agentctl" gui -f "$SB/agents.edn" --port 0 --no-open > "$SB/gui.log" 2>&1 &
GUI_PID=$!
trap 'kill "$GUI_PID" 2>/dev/null || true; rm -rf "$SB"' EXIT
for _ in $(seq 1 100); do grep -q 'http://127.0.0.1' "$SB/gui.log" && break; sleep 0.1; done
URL=$(grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$SB/gui.log" | head -1)
[ -n "$URL" ] || { cat "$SB/gui.log"; fail "gui did not print a URL to open"; }
BASE=${URL%%/\?t=*}
TOK=${URL##*t=}
gui() { # gui <plan|apply> <kind> -> the response, for agents.edn and claude alone
  python3 -c 'import json,sys; print(json.dumps({"text": open(sys.argv[1]).read(), "tools": ["claude"], "kinds": [sys.argv[2]], "confirm": True}))' \
    "$SB/agents.edn" "$2" > "$SB/gui-body.json"
  curl -sS -X POST -H "X-Agentctl-Token: $TOK" -H 'content-type: application/json' \
    --data-binary "@$SB/gui-body.json" "$BASE/api/$1"
}

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
grep -qs rdocs "$SB/proj/.claude/settings.local.json" && fail "a .mcp.json server held back on its secret was approved"
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

# ---------------------------------------------------------------- settings env

ROTATED=sk-test-9999999999abcdef
cat > "$SB/agents.edn" <<EDN
{:settings [{:env {"DOCS_KEY" "!bw://vendor/KEY" "MODE" "fast"} :acli [:claude] :in :all}
            {:env {"DOCS_KEY" "!bw://vendor/KEY"} :model "sonnet" :acli [:claude] :in "proj"}]}
EDN
unset BW_STATE

echo "7. a settings env ref is resolved, flagged as a secret and masked in the plan"
vault '{"vendor": {"KEY": "'$SECRET'"}}'
plan -k settings
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "settings env: expected drift, got exit $code"; }
grep -q 'env: ⚠ secret value written into ~/.claude/settings.json' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the user settings env write is not flagged as a secret"; }
grep -q 'env: ⚠ secret value written into the project' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the project settings env write is not flagged as a secret"; }
grep -q '+ env.DOCS_KEY: "sk-t…ef"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the secret is not shown masked"; }
grep -q '+ env.MODE: "fast"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "a plain env value should print as it is"; }
set +e; run apply -f "$SB/agents.edn" --all -t claude -k settings --json > "$SB/plan.json" 2>&1; set -e
for f in plan.txt plan.json; do
  grep -q "$SECRET" "$SB/$f" && fail "$f printed a resolved secret"
  grep -q '!bw://' "$SB/$f" && fail "$f printed the raw reference as the value"
done
apply -k settings
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "settings env apply! exited $code"; }
grep -q "write resolved secrets" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "the confirmation does not count the secret writes"; }
[ "$(json "$SB/.claude/settings.json" "d['env']['DOCS_KEY']")" = "$SECRET" ] || fail "user settings env not resolved"
[ "$(json "$SB/proj/.claude/settings.json" "d['env']['DOCS_KEY']")" = "$SECRET" ] || fail "project settings env not resolved"
plan -k settings
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "settings env not converged: exit $code"; }

echo "8. a rotated secret that masks the same still shows as a change"
vault '{"vendor": {"KEY": "'$ROTATED'"}}'
plan -k settings
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a rotated secret is not drift: exit $code"; }
grep -q '~ DOCS_KEY: "sk-t…ef" -> "sk-t…ef (changed)"' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "a rotated secret shows no change"; }
grep -qE "$SECRET|$ROTATED" "$SB/plan.txt" && fail "the plan printed a resolved secret"
vault '{"vendor": {"KEY": "'$SECRET'"}}'

echo "9. an unresolved settings env ref writes nothing to env, the rest still applies"
cp "$SB/.claude/settings.json" "$SB/settings-before.json"
sed -i.bak 's/:model "sonnet"/:model "opus"/; s/"MODE" "fast"/"MODE" "slow"/' "$SB/agents.edn"
export BW_STATE=locked
plan -k settings
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "an unresolved settings env read as converged: exit $code"; }
grep -q "⚠ secret unresolved: bitwarden vault locked (env.DOCS_KEY)" "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the unresolved settings env is not reported"; }
apply -k settings
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "apply! with an unresolved settings env exited $code"; }
cmp -s "$SB/settings-before.json" "$SB/.claude/settings.json" || fail "user settings env was partially written"
[ "$(json "$SB/proj/.claude/settings.json" "d['model']")" = opus ] || fail "a setting with no secret was held back too"
[ "$(json "$SB/proj/.claude/settings.json" "d['env']['DOCS_KEY']")" = "$SECRET" ] || fail "the existing secret was blanked"

echo "10. a malformed settings env ref is reported, not a crash"
unset BW_STATE
cat > "$SB/agents.edn" <<EDN
{:settings [{:env {"BROKEN" "!bw://"} :acli [:claude] :in :all}]}
EDN
plan -k settings
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a malformed settings env ref exited $code"; }
grep -q "⚠ secret unresolved: invalid reference" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the malformed ref is not reported"; }

# ---------------------------------------------------------------- transport change

entry() { # entry <local|project> -> the docs server's entry, sorted JSON
  if [ "$1" = local ]; then local_entry "$SB/proj" docs
  else python3 -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1]))['mcpServers'].get('docs'), sort_keys=True))" "$SB/proj/.mcp.json"; fi
}
target() { if [ "$1" = local ]; then echo "$SB/.claude.json"; else echo "$SB/proj/.mcp.json"; fi; }
nodups() { # fail when a JSON object in the file names one key twice
  python3 - "$1" <<'PY'
import json, sys
def pairs(ps):
    ks = [k for k, _ in ps]
    assert len(ks) == len(set(ks)), "duplicate key in %s" % ks
    return dict(ps)
json.load(open(sys.argv[1]), object_pairs_hook=pairs)
PY
}
edit_entry() { # edit_entry <local|project> <python statement over e>
  python3 - "$(target "$1")" "$SB/proj" "$2" <<'PY'
import json, sys
f, proj, stmt = sys.argv[1:]
d = json.load(open(f))
e = (d["projects"][proj] if f.endswith(".claude.json") else d)["mcpServers"]["docs"]
exec(stmt)
json.dump(d, open(f, "w"), indent=2)
PY
}
stdio() { # stdio <scope> <args>
  cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :docs :command "/bin/echo" :args [$2] :env {"A" "1"} :in "proj" :scope :$1 :acli [:claude]}]}
EDN
}
http() { # http <scope>
  cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :docs :type :http :url "https://mcp.example.com/docs" :headers {"api-key" "!bw://vendor/KEY"} :in "proj" :scope :$1 :acli [:claude]}]}
EDN
}
vault '{"vendor": {"KEY": "'$SECRET'"}}'
for scope in local project; do
  echo "11. $scope: a same-transport update keeps what the user added, and drops args no longer declared"
  stdio $scope '"docs-mcp" "--stdio"'
  apply -k mcps
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "$scope: stdio apply! exited $code"; }
  edit_entry $scope 'e["timeout"] = 30; e["env"]["USER_VAR"] = "kept"'
  stdio $scope ''
  sed -i.bak 's/{"A" "1"}/{"A" "2"}/' "$SB/agents.edn"
  plan -k mcps
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "$scope: dropped args are not drift"; }
  grep -q -- '- args: \["docs-mcp" "--stdio"\]' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "$scope: the plan does not show the dropped args"; }
  apply -k mcps
  nodups "$(target $scope)" || fail "$scope: an env name was written twice"
  [ "$(entry $scope)" = '{"command": "/bin/echo", "env": {"A": "2", "USER_VAR": "kept"}, "timeout": 30, "type": "stdio"}' ] \
    || { entry $scope; fail "$scope: same-transport update lost a user key or kept stale args"; }

  echo "12. $scope: stdio -> http replaces the transport keys, and the plan shows what goes"
  http $scope
  plan -k mcps
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "$scope: a transport change is not drift"; }
  for line in '- command: "/bin/echo"' '- env' '- USER_VAR: "********"' '~ type: "stdio" -> "http"' '+ url: "https://mcp.example.com/docs"'; do
    grep -qF -- "$line" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "$scope: the plan does not show '$line'"; }
  done
  grep -q "$SECRET" "$SB/plan.txt" && fail "$scope: the plan printed a resolved secret"
  apply -k mcps
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "$scope: http apply! exited $code"; }
  [ "$(entry $scope)" = '{"headers": {"api-key": "'$SECRET'"}, "timeout": 30, "type": "http", "url": "https://mcp.example.com/docs"}' ] \
    || { entry $scope; fail "$scope: stale stdio keys survived the transport change"; }
  plan -k mcps
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "$scope: not converged after the transport change"; }

  echo "13. $scope: http -> stdio drops the url and headers"
  stdio $scope '"docs-mcp"'
  plan -k mcps
  grep -qF -- '- url: "https://mcp.example.com/docs"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "$scope: the plan does not show the url going"; }
  apply -k mcps
  [ "$(entry $scope)" = '{"args": ["docs-mcp"], "command": "/bin/echo", "env": {"A": "1"}, "timeout": 30, "type": "stdio"}' ] \
    || { entry $scope; fail "$scope: stale http keys survived the transport change"; }
  plan -k mcps
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "$scope: not converged after http -> stdio"; }
  rm -f "$SB/proj/.mcp.json"
done

# ---------------------------------------------------------------- approvals

LOCAL="$SB/proj/.claude/settings.local.json"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :docs :cmd "/bin/echo docs" :in "proj" :at :repo :acli [:claude]}
        {:id :search :cmd "/bin/echo search" :in "proj" :at :repo :acli [:claude]}
        {:id :notes :cmd "/bin/echo notes" :in "proj" :acli [:claude]}]}
EDN
mkdir -p "$SB/proj/.claude"
printf '{"enabledMcpjsonServers": ["handmade"], "permissions": {"allow": ["Bash(ls)"]}}\n' > "$LOCAL"

echo "14. .mcp.json servers are approved in the project's settings.local.json, beside what is there"
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "approval: expected drift, got exit $code"; }
grep -q 'mcps/enabledMcpjsonServers' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "-k mcps does not plan the approval"; }
grep -q 'enabledMcpjsonServers: \["handmade"\] -> \["handmade" "docs" "search"\]' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the plan does not show the approvals being added"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "approval apply! exited $code"; }
[ "$(json "$LOCAL" "d['enabledMcpjsonServers']")" = "['handmade', 'docs', 'search']" ] \
  || { cat "$LOCAL"; fail "approvals not merged into settings.local.json"; }
[ "$(json "$LOCAL" "d['permissions']['allow']")" = "['Bash(ls)']" ] || fail "settings.local.json lost an unrelated key"
[ "$(json "$SB/.claude.json" "d.get('projects', {}).get('$SB/proj', {}).get('enabledMcpjsonServers')")" = None ] \
  || fail "approvals were still written to ~/.claude.json"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "approvals not converged: exit $code"; }
# the earlier sections' settings are gone from agents.edn: converge the whole
# config, so the plans below speak for the approvals alone
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "full apply! exited $code"; }

echo "15. a Claude run that migrates a legacy approval leaves nothing to apply"
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d.setdefault('projects', {}).setdefault(sys.argv[2], {})['enabledMcpjsonServers']=['docs', 'legacy']; json.dump(d, open(f, 'w'))" "$SB/.claude.json" "$SB/proj"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a legacy approval in ~/.claude.json is planned: exit $code"; }
(cd "$SB/proj" && claude mcp list)
[ "$(json "$SB/.claude.json" "'enabledMcpjsonServers' in d['projects']['$SB/proj']")" = False ] || fail "the stub did not delete the legacy key"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "apply is not a no-op after a Claude run: exit $code"; }
[ "$(json "$LOCAL" "d['enabledMcpjsonServers']")" = "['handmade', 'docs', 'search', 'legacy']" ] \
  || { cat "$LOCAL"; fail "a migrated approval was lost"; }

echo "16. a server Claude Code dropped from the list is approved again, and nothing else changes"
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['enabledMcpjsonServers'].remove('search'); json.dump(d, open(f, 'w'))" "$LOCAL"
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "re-approval exited $code"; }
[ "$(json "$LOCAL" "d['enabledMcpjsonServers']")" = "['handmade', 'docs', 'legacy', 'search']" ] \
  || { cat "$LOCAL"; fail "re-approval reordered or dropped entries"; }

# ---------------------------------------------------------------- masking

# a value with no vendor prefix under names that say nothing about a
# credential: rendering alone would print it, so these steps see the masking
PLAIN=plainvalue1234
PLAIN2=plainother9934
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :cmd "/bin/echo m" :env {"DOCS_KEY" "!bw://vendor/PLAIN"} :in "proj" :acli [:claude]}
        {:id :mrepo :type :http :url "https://mcp.example.com/m" :headers {"x-docs" "!bw://vendor/PLAIN"} :in "proj" :at :repo :acli [:claude]}]}
EDN
vault '{"vendor": {"PLAIN": "'$PLAIN'"}}'

echo "17. a resolved project MCP secret is masked in the plan and in --json"
plan -k mcps
set +e; run apply -f "$SB/agents.edn" --all -t claude -k mcps --json > "$SB/plan.json" 2>&1; set -e
for f in plan.txt plan.json; do
  grep -q "$PLAIN" "$SB/$f" && { cat "$SB/$f"; fail "$f printed a resolved project MCP secret"; }
done
grep -q '+ DOCS_KEY: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the local-scope env secret is not shown masked"; }
grep -q '+ x-docs: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the .mcp.json header secret is not shown masked"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "masked apply! exited $code"; }
[ "$(json "$SB/proj/.mcp.json" "d['mcpServers']['mrepo']['headers']['x-docs']")" = "$PLAIN" ] || fail "masking changed what was written"

echo "18. a rotated project MCP secret that masks the same shows as changed"
vault '{"vendor": {"PLAIN": "'$PLAIN2'"}}'
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a rotated project MCP secret is not drift"; }
grep -q 'DOCS_KEY: "plai…34" -> "plai…34 (changed)"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the rotation shows no change"; }
grep -qE "$PLAIN|$PLAIN2" "$SB/plan.txt" && fail "the plan printed a rotated secret"
apply -k mcps

echo "19. a secret a transport change removes is masked too"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}
        {:id :mrepo :type :http :url "https://mcp.example.com/m" :headers {"x-docs" "!bw://vendor/PLAIN"} :in "proj" :at :repo :acli [:claude]}]}
EDN
plan -k mcps
grep -q -- '- DOCS_KEY: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the removed secret is not listed masked"; }
grep -qE "$PLAIN|$PLAIN2" "$SB/plan.txt" && { cat "$SB/plan.txt"; fail "the plan printed a removed secret"; }
apply -k mcps

echo "20. a pruned project MCP server and a pruned settings env print none of their secrets"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}
        {:id :mrepo :type :http :url "https://mcp.example.com/m" :headers {"x-docs" "!bw://vendor/PLAIN"} :in "proj" :at :repo :acli [:claude]}]
 :settings [{:env {"DOCS_KEY" "!bw://vendor/PLAIN"} :acli [:claude] :in :all}]}
EDN
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! before the prune exited $code"; }
[ "$(json "$SB/.claude/settings.json" "d['env']['DOCS_KEY']")" = "$PLAIN2" ] || fail "settings env not written before the prune"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}]}
EDN
plan
set +e; run apply -f "$SB/agents.edn" --all -t claude --json > "$SB/plan.json" 2>&1; set -e
grep -q -- '- mrepo' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the dropped .mcp.json server is not pruned"; }
grep -q -- '- DOCS_KEY: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the pruned settings env is not listed masked"; }
grep -q -- '- x-docs: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the pruned server's header is not listed masked"; }
for f in plan.txt plan.json; do
  grep -qE "$PLAIN|$PLAIN2" "$SB/$f" && { cat "$SB/$f"; fail "$f printed a pruned secret"; }
done
apply
[ "$(json "$SB/.claude/settings.json" "'env' in d")" = False ] || fail "the settings env was not pruned"
[ "$(json "$SB/proj/.mcp.json" "'mrepo' in d['mcpServers']")" = False ] || fail "the .mcp.json server was not pruned"

echo "21. dropping the last setting converges in one apply!, and settings.json keeps its \$schema"
cat > "$SB/agents.edn" <<EDN
{:settings [{:model "sonnet" :acli [:claude] :in :all}]}
EDN
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "settings apply! exited $code"; }
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}]}
EDN
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! after dropping the last setting exited $code"; }
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "dropping the last setting took more than one apply!: exit $code"; }
[ "$(json "$SB/.claude/settings.json" "'model' in d")" = False ] || fail "the dropped setting was not pruned"
[ "$(json "$SB/.claude/settings.json" "d['\$schema']")" = https://json.schemastore.org/claude-code-settings.json ] \
  || fail "settings.json lost its \$schema"

echo "22. a refused claude mcp add fails the op, the server is not owned, and its secret is not printed"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :refused :cmd "/bin/echo refused" :env {"DOCS_KEY" "!bw://refuse/KEY"} :in :all :acli [:claude]}
        {:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}]}
EDN
vault '{"refuse": {"KEY": "'$PLAIN'"}}'
STUB_REFUSE=add apply -k mcps
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a refused mcp add exited $code"; }
grep -q "1 failed" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "the refused add is not counted as failed"; }
grep -q "claude mcp add --scope user refused -e DOCS_KEY=plai…34 -- /bin/echo refused failed: Error: refused by the stub: mcp add --scope user refused -e DOCS_KEY=plai…34" "$SB/apply.txt" \
  || { cat "$SB/apply.txt"; fail "the CLI's error is not reported, masked"; }
grep -q "$PLAIN" "$SB/apply.txt" && { cat "$SB/apply.txt"; fail "the failure printed the secret the command carried"; }
[ "$(json "$SB/.claude.json" "'refused' in d.get('mcpServers', {})")" = False ] || fail "the stub wrote a refused server"
grep -q '\[:claude :mcps "refused"\]' "$SB/.config/agentctl/state.edn" && fail "ownership claimed for a server the CLI refused"
echo add > "$SB/stub-refuse"
gui apply mcps > "$SB/gui.out"
rm "$SB/stub-refuse"
python3 - "$SB/gui.out" <<'PY' || { cat "$SB/gui.out"; fail "the gui does not report the refused add, masked"; }
import json, sys
d = json.load(open(sys.argv[1]))
assert [f["id"] for f in d["failed"]] == ["refused"], d
assert "DOCS_KEY=plai…34" in d["failed"][0]["error"], d
PY
grep -q "$PLAIN" "$SB/gui.out" && { cat "$SB/gui.out"; fail "the gui served the secret the command carried"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "the retry after a refused add exited $code"; }
grep -q '\[:claude :mcps "refused"\]' "$SB/.config/agentctl/state.edn" || fail "the server added on retry is not owned"

echo "23. a refused claude mcp remove fails the prune, and ownership is kept"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :mlocal :type :http :url "https://mcp.example.com/m" :in "proj" :acli [:claude]}]}
EDN
STUB_REFUSE=remove apply -k mcps
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a refused mcp remove exited $code"; }
[ "$(json "$SB/.claude.json" "'refused' in d['mcpServers']")" = True ] || fail "the server is gone though the CLI refused"
grep -q '\[:claude :mcps "refused"\]' "$SB/.config/agentctl/state.edn" || fail "ownership dropped for a server still installed"
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "the retried prune exited $code"; }
[ "$(json "$SB/.claude.json" "'refused' in d['mcpServers']")" = False ] || fail "the retried prune left the server"

# ---------------------------------------------------------------- validate

validate() { set +e; run validate -f "$SB/agents.edn" > "$SB/validate.txt" 2>&1; code=$?; set -e; }
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :hdocs :type :http :url "https://mcp.example.com/h" :headers {"api-key" "!bw://vendor/KEY"} :in :all :acli [:claude]}]
 :settings [{:env {"DOCS_KEY" "!bw://vendor/KEY"} :acli [:claude] :in :all}
            {:env {"DOCS_KEY" "!bw://vendor/KEY" "BROKEN" "!bw://"} :acli [:claude] :in "proj"}]}
EDN

echo "24. validate reports header and settings env refs a locked vault keeps"
export BW_STATE=locked
validate
grep -q "? bitwarden *vault locked" "$SB/validate.txt" || { cat "$SB/validate.txt"; fail "a header-only bw ref does not report the locked vault"; }
grep -q "? hdocs.headers.api-key *bw:vendor/KEY — bitwarden vault locked" "$SB/validate.txt" \
  || { cat "$SB/validate.txt"; fail "the header ref is not reported"; }
grep -q "? claude.env.DOCS_KEY *bw:vendor/KEY — bitwarden vault locked" "$SB/validate.txt" \
  || { cat "$SB/validate.txt"; fail "the user settings env ref is not reported"; }
grep -q "? ~/proj.claude.env.DOCS_KEY *bw:vendor/KEY — bitwarden vault locked" "$SB/validate.txt" \
  || { cat "$SB/validate.txt"; fail "the project settings env ref is not reported"; }
grep -q "✗ ~/proj.claude.env.BROKEN *<invalid reference> — invalid reference" "$SB/validate.txt" \
  || { cat "$SB/validate.txt"; fail "a malformed settings env ref is not reported"; }
[ "$code" = 1 ] || { cat "$SB/validate.txt"; fail "the malformed ref is an error: exit $code"; }

echo "25. validate reports an emptied vault field under a header or settings env as an error"
unset BW_STATE
sed -i.bak 's/ "BROKEN" "!bw:\/\/"//' "$SB/agents.edn"
vault '{"vendor": {"KEY": "null"}}'
validate
[ "$code" = 1 ] || { cat "$SB/validate.txt"; fail "an emptied vault field is not an error: exit $code"; }
for where in hdocs.headers.api-key claude.env.DOCS_KEY "~/proj.claude.env.DOCS_KEY"; do
  grep -qF "✗ $where" "$SB/validate.txt" || { cat "$SB/validate.txt"; fail "$where: the emptied field is not reported"; }
done
grep -q "field is empty on bw item: vendor/KEY" "$SB/validate.txt" || { cat "$SB/validate.txt"; fail "the reason is not given"; }
vault '{"vendor": {"KEY": "'$SECRET'"}}'
validate
grep -qE "✗ (hdocs|claude|~/proj)" "$SB/validate.txt" && { cat "$SB/validate.txt"; fail "a resolved ref is still reported"; }
grep -q "$SECRET" "$SB/validate.txt" && fail "validate printed a resolved secret"

# ---------------------------------------------------------------- old values

outputs() { # outputs <kind>: the plan as apply prints it, verbose, --json and from the gui
  set +e
  run apply -f "$SB/agents.edn" --all -t claude -k "$1" > "$SB/plain.txt" 2>&1
  run apply -f "$SB/agents.edn" --all -t claude -k "$1" --json > "$SB/plan.json" 2>&1
  set -e
  plan -k "$1"
  gui plan "$1" > "$SB/gui.out"
}
leaks() { # leaks <value>: fail when any plan output, or apply!'s, carries it
  for f in plain.txt plan.txt plan.json gui.out apply.txt; do
    grep -q "$1" "$SB/$f" && { cat "$SB/$f"; fail "$f printed a value it replaced: $1"; }
  done
  return 0
}

echo "26. a settings env secret a change drops or replaces prints masked, at user and project level"
cat > "$SB/agents.edn" <<EDN
{:settings [{:env {"DOCS_KEY" "!bw://m2/KEY" "MODE" "fast"} :acli [:claude] :in :all}
            {:env {"DOCS_KEY" "!bw://m2/KEY" "MODE" "fast"} :acli [:claude] :in "proj"}]}
EDN
vault '{"m2": {"KEY": "'$PLAIN'"}}'
apply -k settings
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "settings env apply! exited $code"; }
# dropped at user level, a literal now at project level
cat > "$SB/agents.edn" <<EDN
{:settings [{:env {"MODE" "fast"} :acli [:claude] :in :all}
            {:env {"DOCS_KEY" "literal-new" "MODE" "fast"} :acli [:claude] :in "proj"}]}
EDN
outputs settings
grep -qF -- '- DOCS_KEY: "plai…34"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the dropped secret is not listed masked"; }
grep -qF -- '~ DOCS_KEY: "plai…34" -> "literal-new"' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the secret a literal replaces is not shown masked"; }
grep -qF 'plai…34' "$SB/gui.out" || { cat "$SB/gui.out"; fail "the gui plan does not show the change"; }
apply -k settings
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "settings env apply! exited $code"; }
leaks "$PLAIN"
[ "$(json "$SB/proj/.claude/settings.json" "d['env']['DOCS_KEY']")" = literal-new ] || fail "the literal was not written"

echo "27. a project MCP secret a literal replaces prints masked, in either placement"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :m3l :cmd "/bin/echo m" :env {"DOCS_KEY" "!bw://m3/KEY" "MODE" "fast"} :in "proj" :acli [:claude]}
        {:id :m3r :type :http :url "https://mcp.example.com/m3" :headers {"x-docs" "!bw://m3/KEY" "x-mode" "fast"} :in "proj" :at :repo :acli [:claude]}]}
EDN
vault '{"m3": {"KEY": "'$PLAIN'"}}'
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "project MCP apply! exited $code"; }
sed -i.bak 's|"!bw://m3/KEY"|"literal-new"|g' "$SB/agents.edn"
outputs mcps
grep -qF -- '~ DOCS_KEY: "plai…34" -> "literal-new"' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the local-scope secret a literal replaces is not shown masked"; }
grep -qF -- '~ x-docs: "plai…34" -> "literal-new"' "$SB/plan.txt" \
  || { cat "$SB/plan.txt"; fail "the .mcp.json secret a literal replaces is not shown masked"; }
grep -qF 'plai…34' "$SB/gui.out" || { cat "$SB/gui.out"; fail "the gui plan does not show the change"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "project MCP apply! exited $code"; }
leaks "$PLAIN"
[ "$(json "$SB/proj/.mcp.json" "d['mcpServers']['m3r']['headers']['x-docs']")" = literal-new ] || fail "the literal was not written"

# ---------------------------------------------------------------- hand-added values

user_entry() { # user_entry <server> -> its user-scope entry, sorted JSON
  python3 -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1]))['mcpServers'].get(sys.argv[2]), sort_keys=True))" "$SB/.claude.json" "$1"
}
HAND="Bearer handmade-token-1234"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :hdocs :type :http :url "https://mcp.example.com/h" :headers {"api-key" "!bw://m5/KEY" "x-extra" "plain-extra"} :in :all :acli [:claude]}
        {:id :sdocs :command "/bin/echo" :args ["s"] :env {"A" "1"} :in :all :acli [:claude]}]}
EDN
vault '{"m5": {"KEY": "'$PLAIN'"}}'
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "user-scope apply! exited $code"; }
python3 - "$SB/.claude.json" "$HAND" <<'PY'
import json, sys
f, hand = sys.argv[1:]
d = json.load(open(f))
d["mcpServers"]["hdocs"]["headers"]["Authorization"] = hand
d["mcpServers"]["sdocs"]["env"]["USER_VAR"] = "kept"
json.dump(d, open(f, "w"), indent=2)
PY

echo "28. a header or env var added by hand to a user-scope server is not drift"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a hand-added name read as drift: exit $code"; }

echo "29. a declared header the config drops is removed, and the hand-added one is kept"
sed -i.bak 's/ "x-extra" "plain-extra"//' "$SB/agents.edn"
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a dropped declared header is not drift: exit $code"; }
grep -q 'x-extra' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the plan does not name the dropped header"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! dropping a header exited $code"; }
[ "$(user_entry hdocs)" = '{"headers": {"Authorization": "'"$HAND"'", "api-key": "'$PLAIN'"}, "type": "http", "url": "https://mcp.example.com/h"}' ] \
  || { user_entry hdocs; fail "the dropped header stayed, or the hand-added one went"; }
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after dropping a header: exit $code"; }

echo "30. an update carries the hand-added header and env var over the remove and add"
sed -i.bak 's|mcp.example.com/h"|mcp.example.com/h2"|; s|:args \["s"\]|:args ["s2"]|' "$SB/agents.edn"
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "the url and args change is not drift: exit $code"; }
grep -qE -- '- .*(Authorization|USER_VAR)' "$SB/plan.txt" && { cat "$SB/plan.txt"; fail "the plan says a hand-added name goes"; }
apply -k mcps
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! of the update exited $code"; }
[ "$(user_entry hdocs)" = '{"headers": {"Authorization": "'"$HAND"'", "api-key": "'$PLAIN'"}, "type": "http", "url": "https://mcp.example.com/h2"}' ] \
  || { user_entry hdocs; fail "the update wiped the hand-added header"; }
[ "$(user_entry sdocs)" = '{"args": ["s2"], "command": "/bin/echo", "env": {"A": "1", "USER_VAR": "kept"}, "type": "stdio"}' ] \
  || { user_entry sdocs; fail "the update wiped the hand-added env var"; }
dir=$(sed -n 's|^backups: ~/||p' "$SB/apply.txt")
[ -f "$SB/$dir/__.claude.json" ] || { cat "$SB/apply.txt"; fail "~/.claude.json was not backed up before the remove"; }
grep -q "handmade-token" "$SB/$dir/__.claude.json" || fail "the backup does not hold the hand-added header"
plan -k mcps
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after the update: exit $code"; }

echo "31. a refused add does not print the hand-added header it carried"
sed -i.bak 's|mcp.example.com/h2"|mcp.example.com/h3"|' "$SB/agents.edn"
STUB_REFUSE=add apply -k mcps
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a refused add exited $code"; }
grep -qF "Authorization: Bear…34" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "the carried header is not shown masked"; }
grep -q "handmade-token" "$SB/apply.txt" && { cat "$SB/apply.txt"; fail "the failure printed the carried header"; }

echo "32. an env var, file or command that yields nothing is missing, not an empty secret"
export AGENTCTL_TEST_EMPTY=""
printf '  \n' > "$SB/empty-token"
cat > "$SB/agents.edn" <<EDN
{:mcps [{:id :fenv :type :http :url "https://mcp.example.com/e" :headers {"x-key" "!env://AGENTCTL_TEST_EMPTY"} :in :all :acli [:claude]}
        {:id :ffile :command "/bin/echo" :env {"KEY" "!file://$SB/empty-token"} :in :all :acli [:claude]}
        {:id :fcmd :command "/bin/echo" :env {"KEY" "!cmd://true"} :in :all :acli [:claude]}]}
EDN
plan -k mcps
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "an empty ref value read as converged: exit $code"; }
for why in "env var is empty: AGENTCTL_TEST_EMPTY (headers.x-key)" "file is empty: $SB/empty-token (env.KEY)" "command printed nothing (env.KEY)"; do
  grep -qF "⚠ secret unresolved: $why" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "not reported: $why"; }
done
apply -k mcps
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "apply! with empty ref values exited $code"; }
for id in fenv ffile fcmd; do
  [ "$(user_entry $id)" = null ] || { user_entry $id; fail "$id was written with an empty secret"; }
done
validate
[ "$code" = 1 ] || { cat "$SB/validate.txt"; fail "validate let an empty ref value through: exit $code"; }
grep -qF "✗ fenv.headers.x-key" "$SB/validate.txt" || { cat "$SB/validate.txt"; fail "validate does not report the empty env var"; }
unset AGENTCTL_TEST_EMPTY

echo "all claude mcp e2e checks passed"
