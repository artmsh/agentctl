#!/usr/bin/env bash
# End-to-end: Claude Code plugins (#25). A `claude` stub reproduces what the
# real CLI does and refuses (checked against Claude Code's `plugin` commands):
#   - `marketplace add` rejects file:// sources
#   - `install` rejects plugin@marketplace together with --marketplace
#   - `install` records the install in ~/.claude/plugins/installed_plugins.json
#   - `uninstall` of a plugin that is not installed exits 1 ("is not installed")
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-plugins.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB" HOME="$SB"
unset DSH_HOME DSH_AGENTS_HOME
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
plan() { set +e; run apply -f "$SB/agents.edn" --all -t claude -k plugins --verbose > "$SB/plan.txt" 2>&1; code=$?; set -e; }
apply() { set +e; run apply! -f "$SB/agents.edn" --all -t claude -k plugins -y > "$SB/apply.txt" 2>&1; code=$?; set -e; }
installed() { # installed <key> <project-dir> -> exit 0 when the CLI's record has it
  python3 - "$SB/.claude/plugins/installed_plugins.json" "$1" "$2" <<'PY'
import json, os, sys
f, key, proj = sys.argv[1:]
rows = json.load(open(f)).get("plugins", {}).get(key, []) if os.path.exists(f) else []
sys.exit(0 if any(os.path.realpath(r.get("projectPath", "")) == os.path.realpath(proj) for r in rows) else 1)
PY
}
setting() { # setting <settings.json> <python expr over s>
  python3 -c "import json,sys; s=json.load(open(sys.argv[1])); print($2)" "$1"
}

mkdir -p "$SB/bin"
cat > "$SB/bin/claude" <<'STUB'
#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
open(os.path.join(os.environ["HOME"], "claude-calls.log"), "a").write(" ".join(a) + "\n")
if a[:1] != ["plugin"]:
    sys.exit(0)
rec = os.path.join(os.environ["HOME"], ".claude/plugins/installed_plugins.json")
def opt(name):
    return a[a.index(name) + 1] if name in a else None
def load():
    return json.load(open(rec)) if os.path.exists(rec) else {"version": 2, "plugins": {}}
def save(d):
    os.makedirs(os.path.dirname(rec), exist_ok=True)
    json.dump(d, open(rec, "w"), indent=2)
scope = opt("--scope") or "user"
key = a[-1]
here = os.path.realpath(os.getcwd())
mine = lambda r: r["scope"] == scope and (scope == "user" or r.get("projectPath") == here)
if a[1:3] == ["marketplace", "add"]:
    if key.startswith("file://"):
        print("✘ Invalid marketplace source format. Try: owner/repo, https://..., or ./path"); sys.exit(1)
    sys.exit(0)
if a[1] == "install":
    if os.environ.get("STUB_FAIL_INSTALL"):
        print("✘ network unreachable"); sys.exit(1)
    if "--marketplace" in a and "@" in key:
        print("✘ Name the marketplace once: <plugin>@<marketplace>, or claude plugin install <plugin> --marketplace <source>"); sys.exit(1)
    d = load(); rows = [r for r in d["plugins"].get(key, []) if not mine(r)]
    rows.append({"scope": scope, **({} if scope == "user" else {"projectPath": here})})
    d["plugins"][key] = rows; save(d); sys.exit(0)
if a[1] == "uninstall":
    d = load(); rows = d["plugins"].get(key, [])
    if not any(mine(r) for r in rows):
        print(f'✘ Failed to uninstall plugin "{key}": Plugin "{key}" is not installed in {scope} scope.'); sys.exit(1)
    d["plugins"][key] = [r for r in rows if not mine(r)]; save(d); sys.exit(0)
sys.exit(0)
STUB
chmod +x "$SB/bin/claude"
export PATH="$SB/bin:$PATH"

for p in proj other local; do git init -q "$SB/$p"; done
mkdir -p "$SB/mkt/.claude-plugin"
echo '{"name":"localmkt","owner":{"name":"t"},"plugins":[{"name":"lp","source":"./lp"}]}' > "$SB/mkt/.claude-plugin/marketplace.json"

KEY=understand-anything@understand-anything
cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "Egonex-AI/Understand-Anything" "understand-anything"] {:in "proj" :at :repo :acli [:claude]}}}
EDN

echo "1. the plan installs by plugin@marketplace, without --marketplace"
plan
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "expected drift, got exit $code"; }
grep -q "claude plugin install --scope project -y $KEY" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "install command missing"; }
grep -q -- "--marketplace" "$SB/plan.txt" && { cat "$SB/plan.txt"; fail "install still passes --marketplace with plugin@marketplace"; }

echo "2. apply! installs through the CLI and writes project settings"
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "apply! failed: exit $code"; }
installed "$KEY" "$SB/proj" || { cat "$SB/apply.txt" "$SB/claude-calls.log"; fail "plugin was not installed"; }
[ "$(setting "$SB/proj/.claude/settings.json" "s['enabledPlugins']['$KEY']")" = True ] || fail "plugin not enabled in settings"
[ "$(setting "$SB/proj/.claude/settings.json" "s['extraKnownMarketplaces']['understand-anything']['source']['repo']")" = Egonex-AI/Understand-Anything ] || fail "marketplace not declared"
[ ! -e "$SB/other/.claude" ] || fail "another project was touched"
[ ! -e "$SB/.claude/settings.json" ] || fail "a project plugin was written user-wide"

echo "3. converged"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged: exit $code"; }

echo "4. enabled in settings but not installed is drift, and apply! repairs it"
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['plugins']['$KEY']=[]; json.dump(d,open(f,'w'))" "$SB/.claude/plugins/installed_plugins.json"
plan
[ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a missing install was not reported"; }
grep -q "installed: false -> true" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the plan does not say why"; }
apply
[ "$code" = 0 ] && installed "$KEY" "$SB/proj" || { cat "$SB/apply.txt"; fail "missing install not repaired"; }

echo "5. a failing install fails apply!, writes no settings and claims no ownership"
cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "Egonex-AI/Understand-Anything" "understand-anything"] {:in ["proj" "other"] :at :repo :acli [:claude]}}}
EDN
cp "$SB/.config/agentctl/state.edn" "$SB/state-before.edn"
STUB_FAIL_INSTALL=1 apply
[ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a failed install exited $code"; }
grep -q "network unreachable" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "the CLI's error was not reported"; }
[ ! -e "$SB/other/.claude/settings.json" ] || fail "settings were written for a plugin that did not install"
grep -q 'other/understand-anything' "$SB/.config/agentctl/state.edn" && fail "ownership claimed for a failed install"
apply
[ "$code" = 0 ] && installed "$KEY" "$SB/other" || { cat "$SB/apply.txt"; fail "retry after failure did not install"; }

echo "6. a local file:// marketplace is added by path and declared as a directory"
python3 - "$SB/agents.edn" "$SB" <<'PY'
import sys
p, sb = sys.argv[1:]
s = open(p).read().rstrip()
assert s.endswith("}}")
s = s[:-2] + '\n  [:uri "file://%s/mkt" "lp"] {:in "local" :at :repo :acli [:claude] :marketplace "localmkt"}}}\n' % sb
open(p, "w").write(s)
PY
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt" "$SB/claude-calls.log"; fail "local marketplace failed: exit $code"; }
grep -q "plugin marketplace add --scope project $SB/mkt$" "$SB/claude-calls.log" || { cat "$SB/claude-calls.log"; fail "local marketplace not added by path"; }
[ "$(setting "$SB/local/.claude/settings.json" "s['extraKnownMarketplaces']['localmkt']['source']")" = "{'source': 'directory', 'path': '$SB/mkt'}" ] \
  || { cat "$SB/local/.claude/settings.json"; fail "local marketplace not declared as a directory"; }
installed "lp@localmkt" "$SB/local" || fail "local plugin not installed"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "local marketplace did not converge"; }

echo "7. dropping a placement uninstalls it, also when the CLI no longer has it"
python3 -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['plugins']['$KEY']=[r for r in d['plugins']['$KEY'] if not r.get('projectPath','').endswith('/other')]; json.dump(d,open(f,'w'))" "$SB/.claude/plugins/installed_plugins.json"
cat > "$SB/agents.edn" <<EDN
{:plugins {[:uri "file://$SB/mkt" "lp"] {:in "local" :at :repo :acli [:claude] :marketplace "localmkt"}}}
EDN
apply
[ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "removal failed: exit $code"; }
installed "$KEY" "$SB/proj" && fail "dropped plugin still installed in proj"
for p in proj other; do
  [ "$(setting "$SB/$p/.claude/settings.json" "'$KEY' in s.get('enabledPlugins', {})")" = False ] || fail "dropped plugin still enabled in $p"
done
installed "lp@localmkt" "$SB/local" || fail "removal took a plugin that is still declared"
plan
[ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after removal"; }

echo "all plugin e2e checks passed"
