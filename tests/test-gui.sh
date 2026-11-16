#!/usr/bin/env bash
# End-to-end: the GUI's form on an entity-DSL config, rendered by a real
# headless browser (Lightpanda). Checks the DOM the page builds, and that an
# edit made through the form changes the one node it names and leaves every
# other byte of the buffer alone. Skipped where Lightpanda is not installed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
LP=${LIGHTPANDA:-$(command -v lightpanda || echo /opt/homebrew/bin/lightpanda)}
if [ ! -x "$LP" ]; then echo "gui e2e skipped: lightpanda not installed"; exit 0; fi

SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-gui.XXXXXX")
SB=$(cd "$SB" && pwd -P)
pid=
cleanup() { [ -n "$pid" ] && kill "$pid" 2>/dev/null; wait 2>/dev/null || true; rm -rf "$SB"; }
trap cleanup EXIT
fail() { echo "FAIL: $1" >&2; exit 1; }

export AGENTCTL_HOME="$SB" HOME="$SB"
unset DSH_HOME DSH_AGENTS_HOME
mkdir -p "$SB/bin" "$SB/projects/app/.git" "$SB/packs/team/agents" "$SB/.config"
for c in claude codex pi omp llm agy kimi; do printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$c"; chmod +x "$SB/bin/$c"; done
export PATH="$SB/bin:$PATH"
printf -- '---\nname: reviewer\ndescription: d\n---\n' > "$SB/packs/team/agents/reviewer.md"

cat > "$SB/.config/agents.edn" <<EDN
;; a hand-written DSL config: comments and layout must survive the form
{:#def {effort "high"}

 :settings
 [{:id :base :model "sonnet" :thinking \$effort :acli [:claude] :in :all} ; user-wide
  {:id :app :model "opus" :acli [:claude] :in "projects/app"}]

 :mcps [{:id :search :cmd "search-mcp --stdio" :acli [:claude :codex] :in :all}]

 :trust [{:in "projects" :trusted true :acli [:codex]}]

 :hooks [{:id :fmt :event :post-tool-use :matcher "Edit" :cmd "fmt" :in :all}]

 :skill-packs {[:uri "file://$SB/packs/team"] {:alias team}}

 :agents {[team "reviewer"] {:in :all}}

 :plugins {[:gh "acme/market" "lint"] {:in :all :acli [:claude] :marketplace "acme" :version :latest}}

 :statusline {:segments [:model]}}
EDN
cp "$SB/.config/agents.edn" "$SB/before.edn"

(cd "$SB" && exec "$ROOT/agentctl" gui --no-open > "$SB/gui.log" 2>&1) &
pid=$!
for _ in $(seq 60); do grep -q 'http://127' "$SB/gui.log" && break; sleep 1; done
url=$(grep -o 'http://127[^ ]*' "$SB/gui.log") || { cat "$SB/gui.log" >&2; fail "the GUI did not start"; }

dom() { # dom <out> [inject-script-file]: the page's DOM once the form has rendered
  local inject=()
  [ -n "${2:-}" ] && inject=(--inject-script-file "$2")
  "$LP" fetch --with-frames --dump html --wait-ms 15000 \
    --wait-script "${WAIT:-document.querySelector('#setup details.sec[data-key=\":agents\"] .entry')}" \
    "${inject[@]}" "$url" > "$1" 2>"$1.err" || { cat "$1.err" >&2; fail "lightpanda could not render the page"; }
}

echo "1. every DSL section renders as rows, each with its :in and :acli"
dom "$SB/dom.html"
python3 - "$SB/dom.html" <<'PY' || fail "the DSL form is not what was expected"
import html, json, re, sys
d = open(sys.argv[1]).read()
def section(key):
    m = re.search(r'<details class="sec" data-key="%s".*?</details>' % re.escape(key), d, re.S)
    assert m, "no section " + key
    return m.group(0)
paths = lambda s: [json.loads(html.unescape(x)) for x in re.findall(r'data-path="([^"]*)"', s)]
names = lambda s: [html.unescape(x) for x in re.findall(r'<span class="name">([^<]*)', s)]
for key in [":settings", ":permissions", ":mcps", ":skill-packs", ":skills", ":providers", ":memory",
            ":trust", ":hooks", ":plugins", ":agents", ":extensions", ":statusline"]:
    section(key)
s = section(":settings")
assert names(s) == ["base  · :all", 'app  · "projects/app"'], names(s)
ps = paths(s)
for row in ["0", "1"]:
    assert [":settings", row, ":in"] in ps and [":settings", row, ":acli"] in ps, ps[:6]
assert 'data-opt=":claude" aria-pressed="true"' in s, "the row's :acli is shown as chosen CLIs"
a = section(":agents")
assert names(a) == ['[team "reviewer"]'], names(a)
assert [":agents", '[team "reviewer"]', ":in"] in paths(a)
p = section(":plugins")
assert [":plugins", '[:gh "acme/market" "lint"]', ":version"] in paths(p)
assert ":latest" in p
t = section(":trust")
assert [":trust", "0", ":trusted"] in paths(t) and [":trust", "0", ":acli"] in paths(t)
h = section(":hooks")
assert [":hooks", "0", ":matcher"] in paths(h)
# placement is a row even where the file leaves it out: the hook has no :acli,
# the pack no :in, the subagent no :acli
shown = lambda s: re.findall(r'<div class="row">.*?data-path="([^"]*)"', s)
shown_paths = lambda s: [json.loads(html.unescape(x)) for x in shown(s)]
assert [":hooks", "0", ":acli"] in shown_paths(h), shown_paths(h)
k = section(":skill-packs")
assert any(p[0] == ":skill-packs" and p[-1] == ":in" for p in shown_paths(k)), shown_paths(k)
assert [":agents", '[team "reviewer"]', ":acli"] in shown_paths(a)
assert '<button type="button" data-opt=":claude"' in a and 'data-opt=":codex"' not in a, "subagents are Claude's"
assert "Key, e.g." in a, "keyed sections add by key"
assert "addrow-dsl" in s, "row sections add rows"
assert "segments" in section(":statusline")
print("ok")
PY

act() { # act <name> <js: act on the form> <js: buffer predicate, src.value in `b`>: the buffer after it
  cat > "$SB/$1.js" <<JS
window.addEventListener("load", () => {
  const go = () => {
    if (!document.querySelector('#setup details.sec[data-key=":agents"] .entry')) { setTimeout(go, 100); return; }
    const before = document.querySelector("#src").value;
    $2
    // a textarea's value is not in the serialized DOM: copy the buffer out
    // once the server's answer has replaced it
    const out = () => {
      const b = document.querySelector("#src").value;
      if (b === before || !($3)) { setTimeout(out, 100); return; }
      const pre = document.createElement("pre");
      pre.id = "buffer-dump";
      pre.textContent = b;
      document.body.appendChild(pre);
    };
    out();
  };
  go();
});
JS
  WAIT="document.querySelector('#buffer-dump')" dom "$SB/$1.html" "$SB/$1.js"
  python3 -c '
import html, re, sys
m = re.search(r"<pre id=\"buffer-dump\">(.*?)</pre>", open(sys.argv[1]).read(), re.S)
sys.stdout.write(html.unescape(m.group(1)) if m else "")' "$SB/$1.html" > "$SB/$1.edn"
  [ -s "$SB/$1.edn" ] || fail "$1: no buffer came back"
}
same_but() { # same_but <name> <python: expected text, from `before`>
  python3 - "$SB/$1.edn" "$SB/before.edn" "$2" <<'PY' || fail "$1 touched more than its node"
import sys
after = open(sys.argv[1]).read(); before = open(sys.argv[2]).read()
want = eval(sys.argv[3])
assert after == want, "\n--- want\n" + want + "\n--- got\n" + after
PY
}
field='document.querySelector(`#setup [data-path=${JSON.stringify(JSON.stringify(P))}]`)'

echo "2. a field edit through the form changes its node; every other byte of the buffer stays"
act model "const el = ${field/P/[\":settings\",\"1\",\":model\"]}; el.value = \"haiku\"; el.dispatchEvent(new Event(\"input\", {bubbles: true}));" \
  "b.includes('\"haiku\"')"
same_but model 'before.replace("\"opus\"", "\"haiku\"")'

echo "3. :acli is a set of chips; switching one on writes that row's :acli alone"
act acli "${field/P/[\":settings\",\"0\",\":acli\"]}.querySelector('button[data-opt=\":codex\"]').click();" \
  "b.includes(':acli [:claude :codex]')"
same_but acli 'before.replace(":acli [:claude] :in :all} ; user-wide", ":acli [:claude :codex] :in :all} ; user-wide")'

echo "4. + Add row appends the starter row; the rows above are untouched"
act addrow "document.querySelector('#setup .addrow-dsl[data-section=\":trust\"]').click();" \
  "b.split(':trusted true').length === 3"
same_but addrow 'before.replace(":trust [{:in \"projects\" :trusted true :acli [:codex]}]", ":trust [{:in \"projects\" :trusted true :acli [:codex]} {:in \"projects\" :trusted true}]")'

echo "5. a keyed entry is removed by its selector key, and the section stays"
act delagent "document.querySelector('#setup details.sec[data-key=\":agents\"] [data-del]').click();" \
  "!b.includes('reviewer')"
same_but delagent 'before.replace(":agents {[team \"reviewer\"] {:in :all}}", ":agents {}")'

echo "6. a keyed entry is added under the selector typed"
act addagent "const sec = document.querySelector('#setup details.sec[data-key=\":agents\"]'); sec.querySelector('.newid').value = '[team \"planner\"]'; sec.querySelector('.addentry').click();" \
  "b.includes('planner')"
same_but addagent 'before.replace(":agents {[team \"reviewer\"] {:in :all}}", ":agents {[team \"reviewer\"] {:in :all} [team \"planner\"] {:in :all}}")'

cmp -s "$SB/.config/agents.edn" "$SB/before.edn" || fail "the form wrote the file; edits are a buffer until Save"

echo "all gui e2e checks passed"
