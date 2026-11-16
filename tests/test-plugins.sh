#!/usr/bin/env bash
# End-to-end: Claude Code plugins. A `claude` stub reproduces what the
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

echo "8. unit: marketplace refs, source switches and import (claude_ext_test.clj)"
(
  SB="$SB/unit"; mkdir -p "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB"
  bb -cp "$ROOT/src" "$ROOT/tests/claude_ext_test.clj"
)

# Sections from here on run tests/claude-plugin-stub, which serves a `[:gh …]`
# marketplace from a local bare repository through git's insteadOf, so each
# one gets a home of its own: HOME, AGENTCTL_HOME, gitconfig and remotes.
stub_home() { # stub_home <dir>: a scratch home with the richer stub on PATH
  mkdir -p "$1/bin" "$1/remotes"
  ln -sf "$ROOT/tests/claude-plugin-stub" "$1/bin/claude"
  cat > "$1/.gitconfig" <<EOF
[url "file://$1/remotes/"]
	insteadOf = https://github.com/
[user]
	email = test@example.com
	name = test
[init]
	defaultBranch = main
EOF
}
market() { # market <home> <plugin version> [branch]: commit marketplace "acme" carrying plugin lp
  local w="$1/work" b="${3:-main}"
  mkdir -p "$w/.claude-plugin" "$w/lp/.claude-plugin"
  [ -d "$w/.git" ] || git -C "$w" init -q
  git -C "$w" checkout -q -B "$b"
  echo '{"name":"acme","owner":{"name":"t"},"plugins":[{"name":"lp","source":"./lp"}]}' > "$w/.claude-plugin/marketplace.json"
  echo "{\"name\":\"lp\",\"version\":\"$2\"}" > "$w/lp/.claude-plugin/plugin.json"
  echo "$2 $b $RANDOM" > "$w/lp/README.md"
  git -C "$w" add -A
  git -C "$w" commit -qm "lp $2"
  [ -d "$1/remotes/acme/market.git" ] || git init -q --bare "$1/remotes/acme/market.git"
  git -C "$w" push -q --force "$1/remotes/acme/market.git" "HEAD:refs/heads/$b"
}
owned() { # owned <id>: exit 0 when the manifest owns Claude plugin <id>
  bb -e "(System/exit (if (get-in (clojure.edn/read-string (slurp \"$SB/.config/agentctl/state.edn\")) [:managed [:claude :plugins \"$1\"]]) 0 1))" 2>/dev/null
}
record() { # record <python expr over the user-scope rows of lp@acme>
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); rows=[r for r in d['plugins'].get('lp@acme', []) if r['scope']=='user']; print($1)" "$SB/.claude/plugins/installed_plugins.json"
}

echo "9. a marketplace ref is part of its source: argv, settings, drift, and a re-point at user scope"
(
  SB="$SB/ref"; stub_home "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB" GIT_CONFIG_GLOBAL="$SB/.gitconfig" PATH="$SB/bin:$PATH"
  market "$SB" 1.0.0
  market "$SB" 1.0.0 dev
  cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme"}}}
EDN
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt" "$SB/claude-calls.log"; fail "install from a github marketplace failed: exit $code"; }
  [ "$(record 'rows[0]["version"]')" = 1.0.0 ] || fail "plugin not installed at user scope"
  sed -i.bak 's/:marketplace "acme"}/:marketplace "acme" :ref "dev"}/' "$SB/agents.edn"
  plan
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a ref change is not drift: exit $code"; }
  grep -q "claude plugin marketplace add --scope user acme/market#dev" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "add argv lacks the ref"; }
  grep -q '+ ref: "dev"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the diff does not show the ref"; }
  grep -q "marketplace source changed" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "a switch with the plugin installed is not called out"; }
  # Claude refuses to re-point a marketplace its user settings name from
  # another source, so apply! has to write the entry before adding
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "re-pointing the marketplace failed: exit $code"; }
  [ "$(setting "$SB/.claude/settings.json" "s['extraKnownMarketplaces']['acme']['source'].get('ref')")" = dev ] || fail "settings do not carry the ref"
  [ "$(setting "$SB/.claude/plugins/known_marketplaces.json" "s['acme']['source'].get('ref')")" = dev ] || fail "Claude's registration does not carry the ref"
  [ "$(git -C "$SB/.claude/plugins/marketplaces/acme" rev-parse HEAD)" = "$(git -C "$SB/remotes/acme/market.git" rev-parse dev)" ] || fail "the clone is not at the ref"
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after the ref change: exit $code"; }

  echo "10. a failed re-point leaves the settings file as it was"
  sed -i.bak 's/:ref "dev"/:ref "main"/' "$SB/agents.edn"
  cp "$SB/.claude/settings.json" "$SB/settings-before.json"
  STUB_FAIL_ADD=1 apply
  [ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a failed add exited $code"; }
  cmp -s "$SB/.claude/settings.json" "$SB/settings-before.json" || fail "a failed add left the settings changed"
)

echo "11. :version :latest follows the marketplace; a row without :version keeps presence only"
(
  SB="$SB/latest"; stub_home "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB" GIT_CONFIG_GLOBAL="$SB/.gitconfig" PATH="$SB/bin:$PATH"
  market "$SB" 1.0.0
  first=$(git -C "$SB/remotes/acme/market.git" rev-parse main)
  cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme" :version :latest}}}
EDN
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt" "$SB/claude-calls.log"; fail "install with :version :latest failed: exit $code"; }
  [ "$(record 'rows[0]["version"]')" = 1.0.0 ] || fail "not installed at 1.0.0"
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after install: exit $code"; }
  market "$SB" 1.1.0
  new=$(git -C "$SB/remotes/acme/market.git" rev-parse main)
  plan
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a newer marketplace is not drift: exit $code"; }
  grep -q "version: \"1.0.0\" -> \"latest @ ${new:0:7}\"" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "no version diff"; }
  grep -q "claude plugin marketplace update acme" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "no marketplace refresh planned"; }
  grep -q "claude plugin update --scope user -y lp@acme" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "no plugin update planned"; }
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "update failed: exit $code"; }
  [ "$(record 'rows[0]["version"]')" = 1.1.0 ] || { cat "$SB/.claude/plugins/installed_plugins.json"; fail "not updated to 1.1.0"; }
  [ "$(record 'rows[0]["gitCommitSha"]')" = "$new" ] || fail "install record is not at the marketplace's new commit"
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after the update: exit $code"; }
  # a catalog already refreshed on disk names the version itself
  market "$SB" 1.2.0
  (cd "$SB" && claude plugin marketplace update acme >/dev/null)
  plan
  grep -q 'version: "1.1.0" -> "1.2.0"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the refreshed catalog's version is not shown"; }

  echo "12. a pinned :version installs exactly that version; one the marketplace lacks fails"
  sed -i.bak 's/:version :latest/:version "1.1.0"/' "$SB/agents.edn"
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a pin on the installed version is drift: exit $code"; }
  sed -i.bak 's/:version "1.1.0"/:version "1.0.0"/' "$SB/agents.edn"
  plan
  [ "$code" = 2 ] || { cat "$SB/plan.txt"; fail "a pin off the installed version is not drift"; }
  grep -q 'version: "1.1.0" -> "1.0.0"' "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "no version diff for the pin"; }
  grep -q "offers 1.2.0" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the plan does not say the marketplace lacks the pin"; }
  apply
  [ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "installing an unoffered pin exited $code"; }
  grep -q "pin it with :ref" "$SB/apply.txt" || { cat "$SB/apply.txt"; fail "the failure does not say why"; }
  [ "$(record 'rows[0]["version"]')" = 1.1.0 ] || fail "a refused pin changed the install"
  git -C "$SB/remotes/acme/market.git" tag v1.0.0 "$first"
  sed -i.bak 's/:version "1.0.0"}/:version "1.0.0" :ref "v1.0.0"}/' "$SB/agents.edn"
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "pinning through :ref failed: exit $code"; }
  [ "$(record 'rows[0]["version"]')" = 1.0.0 ] || { cat "$SB/.claude/plugins/installed_plugins.json"; fail "not at the pinned version"; }
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "pinned install not converged: exit $code"; }

  echo "13. without :version, a newer marketplace is not drift"
  sed -i.bak 's/ :version "1.0.0" :ref "v1.0.0"}/}/' "$SB/agents.edn"
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "dropping :version and :ref failed: exit $code"; }
  market "$SB" 1.3.0
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a row without :version reported drift: exit $code"; }
)

echo "14. a source switch at an unchanged version reinstalls from the new source"
(
  SB="$SB/switch"; stub_home "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB" GIT_CONFIG_GLOBAL="$SB/.gitconfig" PATH="$SB/bin:$PATH"
  market "$SB" 1.0.0
  market "$SB" 1.0.0 dev
  cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme"}}}
EDN
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "install failed: exit $code"; }
  sed -i.bak 's/:marketplace "acme"}/:marketplace "acme" :ref "dev"}/' "$SB/agents.edn"
  plan
  grep -q "claude plugin uninstall --scope user -y lp@acme" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "no reinstall planned"; }
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "the switch failed: exit $code"; }
  [ "$(record 'rows[0]["gitCommitSha"]')" = "$(git -C "$SB/remotes/acme/market.git" rev-parse dev)" ] \
    || { cat "$SB/.claude/plugins/installed_plugins.json"; fail "the plugin still comes from the old source"; }
  grep -q " dev " "$(record 'rows[0]["installPath"]')/README.md" || fail "the cached copy is the old one"
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "not converged after the switch: exit $code"; }
)

echo "15. :build runs once per new install path; a failing build fails apply!, and the install stays owned"
(
  SB="$SB/build"; stub_home "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB" GIT_CONFIG_GLOBAL="$SB/.gitconfig" PATH="$SB/bin:$PATH"
  market "$SB" 1.0.0
  cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme" :version :latest :build "exit 3"}}}
EDN
  apply
  [ "$code" = 1 ] || { cat "$SB/apply.txt"; fail "a failing build exited $code"; }
  # the install stands: owned, so a dropped row still uninstalls it
  owned lp || { cat "$SB/.config/agentctl/state.edn"; fail "an install whose build failed is not owned"; }
  plan
  [ "$code" = 2 ] && grep -q "sh -c exit 3" "$SB/plan.txt" || { cat "$SB/plan.txt"; fail "the failed build is not planned again"; }
  sed -i.bak 's/:build "exit 3"/:build "echo built >> BUILT"/' "$SB/agents.edn"
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "build failed: exit $code"; }
  dir=$(record 'rows[0]["installPath"]')
  [ "$(cat "$dir/BUILT")" = built ] || fail "the build did not run in the install path"
  owned lp || { cat "$SB/.config/agentctl/state.edn"; fail "a built plugin is not owned"; }
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "a built plugin is drift: exit $code"; }
  market "$SB" 1.1.0
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "update with a build failed: exit $code"; }
  new=$(record 'rows[0]["installPath"]')
  [ "$new" != "$dir" ] && [ "$(cat "$new/BUILT")" = built ] || fail "the new install path was not built once"
  [ "$(cat "$dir/BUILT")" = built ] || fail "the old install path was built again"
)

echo "16. :agents links a pack's subagent definitions user-wide and into a project, and prunes only its own links"
(
  SB="$SB/agents"; mkdir -p "$SB/bin"; ln -s "$ROOT/tests/claude-plugin-stub" "$SB/bin/claude"
  export AGENTCTL_HOME="$SB" HOME="$SB" PATH="$SB/bin:$PATH"
  ag() { set +e; run "$@" -f "$SB/agents.edn" --all -t claude > "$SB/out.txt" 2>&1; code=$?; set -e; }
  agents_owned() { # the ids state.edn owns as Claude agents, one per line
    bb -e "(doseq [[[t k id] _] (:managed (clojure.edn/read-string (slurp \"$SB/.config/agentctl/state.edn\"))) :when (= [t k] [:claude :agents])] (println id))" | sort
  }
  pack="$SB/packs/team"; app="$SB/projects/app"
  mkdir -p "$pack/agents" "$app/.git" "$SB/.claude/agents"
  for a in reviewer planner mine; do printf -- '---\nname: %s\ndescription: d\n---\nbody\n' "$a" > "$pack/agents/$a.md"; done
  echo "hand-made" > "$SB/.claude/agents/notes.md"
  cat > "$SB/agents.edn" <<EDN
{:skill-packs {[:uri "file://$pack"] {:alias team}}
 :agents {[team "reviewer"] {:in :all}
          [team "planner"] {:in "projects/app"}}}
EDN
  ag validate
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail ":agents does not validate: exit $code"; }
  ag apply
  [ "$code" = 2 ] || { cat "$SB/out.txt"; fail "agents to link are not drift: exit $code"; }
  grep -q "+ agent \[agents: claude-code\] ~/packs/team/agents/reviewer.md" "$SB/out.txt" || { cat "$SB/out.txt"; fail "no user-wide agent line"; }
  grep -A3 "PROJECT(projects/app)" "$SB/out.txt" | grep -q "+ agent \[agents: claude-code\] ~/packs/team/agents/planner.md" \
    || { cat "$SB/out.txt"; fail "no project agent line"; }
  ag apply -k skills
  grep -q "+ agent" "$SB/out.txt" && { cat "$SB/out.txt"; fail "-k skills plans agents"; }
  ag apply -k agents
  [ "$(grep -c "+ agent" "$SB/out.txt")" = 2 ] || { cat "$SB/out.txt"; fail "-k agents does not plan both agents"; }
  ag apply! -y
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "apply! failed: exit $code"; }
  [ "$(readlink "$SB/.claude/agents/reviewer.md")" = "$pack/agents/reviewer.md" ] || fail "no user-wide link"
  [ "$(readlink "$app/.claude/agents/planner.md")" = "$pack/agents/planner.md" ] || fail "no project link"
  [ "$(agents_owned | wc -l | tr -d ' ')" = 2 ] && agents_owned | grep -qx reviewer && agents_owned | grep -q '/planner$' \
    || { agents_owned; fail "the links are not owned"; }
  ag apply
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "not converged: exit $code"; }

  echo "17. removing an agent removes only the link agentctl made"
  cat > "$SB/agents.edn" <<EDN
{:skill-packs {[:uri "file://$pack"] {:alias team}}
 :agents {[team "planner"] {:in "projects/app"}}}
EDN
  ag apply
  grep -q -- "- agent \[agents: claude-code\] ~/packs/team/agents/reviewer.md" "$SB/out.txt" || { cat "$SB/out.txt"; fail "no removal line"; }
  ag apply! -y
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "prune failed: exit $code"; }
  [ -e "$SB/.claude/agents/reviewer.md" ] || [ -L "$SB/.claude/agents/reviewer.md" ] && fail "the owned link survived its removal"
  [ "$(cat "$SB/.claude/agents/notes.md")" = hand-made ] || fail "a hand-made definition was touched"
  [ -f "$pack/agents/reviewer.md" ] || fail "the pack's file went with the link"
  agents_owned | grep -qx reviewer && fail "a pruned link is still owned"
  # a link the user turned into a file of their own stays when the row goes
  rm "$app/.claude/agents/planner.md"; echo "edited" > "$app/.claude/agents/planner.md"
  cat > "$SB/agents.edn" <<EDN
{:skill-packs {[:uri "file://$pack"] {:alias team}}}
EDN
  ag apply --show-noop
  grep -q "is not the link agentctl made — left in place" "$SB/out.txt" || { cat "$SB/out.txt"; fail "no warning for a replaced link"; }
  ag apply! -y
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "apply! with a replaced link failed: exit $code"; }
  [ "$(cat "$app/.claude/agents/planner.md")" = edited ] || fail "a file the user put in place of the link was removed"

  echo "18. linking over a hand-made definition backs it up first"
  echo "my own" > "$SB/.claude/agents/mine.md"
  cat > "$SB/agents.edn" <<EDN
{:skill-packs {[:uri "file://$pack"] {:alias team}}
 :agents {[team ["mine" "reviewer"]] {:in :all}}}
EDN
  ag apply
  grep -q "replaces a file agentctl did not write (backed up first)" "$SB/out.txt" || { cat "$SB/out.txt"; fail "the replacement is not flagged"; }
  ag apply! -y
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "apply! failed: exit $code"; }
  [ "$(readlink "$SB/.claude/agents/mine.md")" = "$pack/agents/mine.md" ] || fail "not linked over the hand-made file"
  grep -rqx "my own" "$SB/.config/agentctl/backups" || fail "the hand-made file was not backed up"

  echo "19. import reads links into a declared pack back as :agents"
  mkdir -p "$app/.claude/agents"; rm -f "$app/.claude/agents/planner.md"
  ln -s "$pack/agents/planner.md" "$app/.claude/agents/planner.md"
  ln -s "$SB/elsewhere.md" "$SB/.claude/agents/stray.md"
  set +e; run import -f "$SB/agents.edn" > "$SB/import.edn" 2> "$SB/import.err"; code=$?; set -e
  [ "$code" = 0 ] || { cat "$SB/import.err"; fail "import failed: exit $code"; }
  bb -e "(let [a (:agents (clojure.edn/read-string (slurp \"$SB/import.edn\")))]
           ;; the declared row stays as written; the link found on disk joins it
           (assert (= {:in :all} (get a '[team [\"mine\" \"reviewer\"]])) (pr-str a))
           (assert (= [\"~/projects/app\"] (:in (get a '[team \"planner\"]))) (pr-str a))
           (assert (= 2 (count a)) (pr-str a)))" || { cat "$SB/import.edn"; fail "import does not round-trip :agents"; }
  cp "$SB/import.edn" "$SB/agents.edn"
  ag apply
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "the imported config is not converged: exit $code"; }
)

echo "20. a marketplace entry keeps the keys the row does not declare"
(
  SB="$SB/keep"; stub_home "$SB"
  export AGENTCTL_HOME="$SB" HOME="$SB" GIT_CONFIG_GLOBAL="$SB/.gitconfig" PATH="$SB/bin:$PATH"
  market "$SB" 1.0.0
  cat > "$SB/agents.edn" <<EDN
{:plugins {[:gh "acme/market" "lp"] {:in :all :acli [:claude] :marketplace "acme"}}}
EDN
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "install failed: exit $code"; }
  python3 - "$SB/.claude/settings.json" <<'PY'
import json, sys
f = sys.argv[1]; d = json.load(open(f)); d["extraKnownMarketplaces"]["acme"]["autoUpdate"] = True
json.dump(d, open(f, "w"), indent=2)
PY
  plan
  [ "$code" = 0 ] || { cat "$SB/plan.txt"; fail "an undeclared key is drift: exit $code"; }
  sed -i.bak 's/:marketplace "acme"}/:marketplace "acme" :ref "main"}/' "$SB/agents.edn"
  apply
  [ "$code" = 0 ] || { cat "$SB/apply.txt"; fail "the re-point failed: exit $code"; }
  [ "$(setting "$SB/.claude/settings.json" 's["extraKnownMarketplaces"]["acme"].get("autoUpdate")')" = True ] \
    || { cat "$SB/.claude/settings.json"; fail "autoUpdate was dropped"; }
  [ "$(setting "$SB/.claude/settings.json" 's["extraKnownMarketplaces"]["acme"]["source"].get("ref")')" = main ] \
    || fail "the source was not re-pointed"
)

echo "21. an owned agent link whose source is gone is reported, not converged"
(
  SB="$SB/agent-gone"; mkdir -p "$SB/bin"; ln -s "$ROOT/tests/claude-plugin-stub" "$SB/bin/claude"
  export AGENTCTL_HOME="$SB" HOME="$SB" PATH="$SB/bin:$PATH"
  pack="$SB/packs/team"; mkdir -p "$pack/agents"
  printf -- '---\nname: reviewer\ndescription: d\n---\n' > "$pack/agents/reviewer.md"
  cat > "$SB/agents.edn" <<EDN
{:skill-packs {[:uri "file://$pack"] {:alias team}}
 :agents {[team "reviewer"] {:in :all}}}
EDN
  set +e; run apply! -y -f "$SB/agents.edn" --all -t claude > "$SB/out.txt" 2>&1; code=$?; set -e
  [ "$code" = 0 ] || { cat "$SB/out.txt"; fail "apply! failed: exit $code"; }
  rm "$pack/agents/reviewer.md"
  set +e; run apply -f "$SB/agents.edn" --all -t claude > "$SB/out.txt" 2>&1; code=$?; set -e
  grep -q "source missing: ~/packs/team/agents/reviewer.md" "$SB/out.txt" || { cat "$SB/out.txt"; fail "a dangling owned link reads as converged"; }
  grep -q "no changes" "$SB/out.txt" && { cat "$SB/out.txt"; fail "the plan says no changes"; }
  [ -L "$SB/.claude/agents/reviewer.md" ] || fail "the link was touched"
)

echo "all plugin e2e checks passed"
