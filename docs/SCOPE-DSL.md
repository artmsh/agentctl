# Entity-first config with a project selector

Status: implemented using shape B (kind-keyed vectors); `:skill-packs` and
`:skills` are maps keyed by source selector (see below). Legacy map sections and
the project registry remain readable for compatibility; new imports emit vectors.
The adapter model still uses internal resolved target records.

Discovery runs on every configuration load, including every `apply`. It stops
at repository roots, does not follow child directory symlinks, has a maximum
depth of 32, and skips `node_modules`, `.venv`, `venv`, VCS metadata, and
`skill-packs` cache directories. Selectors are HOME-relative; `:all` is user scope.
Narrower matching paths win, then tool-specific settings, then declaration order.

State version 2 records resolved absolute paths and retains destinations for
pruning after a selector loses a repository. Version 1 remains readable, but
cannot recover paths it never recorded. `:at :machine` is the default;
`:at :repo` opts MCPs into the repository file. `:scope` is a deprecated alias.
Tool capabilities still apply: unsupported project placements report warnings.
The GUI edits the section vectors and shows a read-only resolved-targets pane.

Placement-derived ids collide when two unnamed stanzas share a selector. Give
those stanzas distinct explicit `:id` values, including settings for different tools.

## Skills and skill packs: keyed maps

`:skill-packs` and `:skills` are maps, not vectors. The key is the source
selector and is unique by construction; the value holds placement and options.

```edn
{:skill-packs
 {[:uri "file://$HOME/projects/agents-setup"] {:alias agents-setup}
  [:gh "obra/superpowers"]                    {:alias superpowers :in ["projects/clz"]}}
 :skills
 {[agents-setup "wrap-up"]  {:in :all}
  [:gh "cyxzdev/Uncodixfy"] {:in ["projects/core-vector"] :acli [:codex]}}}
```

| key | in | means |
|---|---|---|
| `[:gh "owner/repo"]` | both | `https://github.com/owner/repo`, cloned under `~/.agents/skill-packs/<id>` |
| `[:uri "…"]` | both | a git URL, or a local directory (`file://`, `/`, `~`, `$VAR`) |
| `[pack "name"]` | `:skills` | skill directory `name` inside the pack whose id or `:alias` is `pack` |

- **Id.** `:alias sym` names the entity; without it the id is the last path
  segment of the key (`:superpowers`, `:Uncodixfy`, `:wrap-up`). Two keys with
  the same basename need an `:alias`. A skill's `:alias` renames it without
  changing which directory it reads.
- **Repository as a skill.** `[:gh …]` / git `[:uri …]` under `:skills` is
  fetched as an implicit pack (`:in :none`) and resolves to the repository's
  `SKILL.md`, or `skills/<id>`. A declared pack with the same URI is reused.
- **`:acli`** selects agent CLIs: `:claude`/`:cc`, `:codex`, `:pi`, `:omp`,
  `:llm`, `:antigravity`/`:agy`. It is `:tools` with short names; the other
  entity kinds accept it too.
- **`:in`** is unchanged. Packs default to `:none`, skills to `:all`. A placed
  pack installs every skill it contains.
- **Installs.** `:in :all` links into each tool's user directory. A
  repository placement runs `npx -y skills add` there (see README); a GitHub
  pack only projects use is fetched by that CLI, not cloned by agentctl.

## The change

Today `agents.edn` is **place-first**: `:projects` is a registry, and a project
lists the ids of the entities it wants (`:mcp [:tracker]`, `:skills [:review]`).
The entity is declared in one section, the placement in another, and three
functions (`scope-mcps`, `scope-skills`, `bare-project-skills`) exist only to
join the two halves back together.

The proposal is **entity-first**: an entity carries its own placement.

```edn
{:skill-packs {[:gh "obra/superpowers"] {:in :all}}}
{:skill-packs {[:gh "obra/superpowers"] {:in ["Brain" "projects"]}}}
{:skill-packs {[:gh "obra/superpowers"] {:in "projects/clz"}}}
```

One declaration, read top to bottom, no cross-section join.

## Naming: the selector is `:in`, not `:scope`

`:scope` is already load-bearing and means something else — *which file does
this land in* (`norm-mcp`, `norm-skill`, `norm-memory` all read it;
`scope-mcps`/`scope-skills` derive it):

| current `:scope` | current meaning |
|---|---|
| `:local` | `~/.claude.json`, under the project's entry |
| `:project` | `<project>/.mcp.json` — a tracked file |
| `:global` | `~/.claude.json`, user-wide |

Overloading that key with "which projects" gives one word two jobs. So:

- **`:in`** — the new project selector. This document.
- **`:at`** — the old `:scope`, renamed, reduced to the one distinction that
  survives: `:machine` (default) vs `:repo` (write into `<project>/.mcp.json`,
  which is committed — the thing that earns a `⚠ secret value written into the
  project` warning). `:global` disappears, because `:in :all` already says it.

Read `:scope` as a deprecated alias for `:at` for one release; `import` writes
`:at`.

## The selector

```
selector := :all | :none | pattern | [pattern …]
pattern  := path | "!" path        ; "!" subtracts
path     := "Brain" | "projects/clz" | "~/work/x" | "/abs/path"
```

Three rules, and that is the whole language:

1. **A relative path is resolved against `$HOME`.** `"projects/clz"` is
   `~/projects/clz`. `~/…` and `/…` work as written. `$name` from `:#def`
   expands as it does everywhere else.
2. **A path that is a repository root means that repository. A path that is not
   means every repository under it.** `"Brain"` is one target (it is a repo);
   `"projects"` is every repo inside `~/projects`. That is exactly how the two
   read in the motivating example, with no extra syntax.
3. **`:all` is user scope, not fan-out.** It installs once, in the tool's own
   user-wide location, where every project picks it up. It does *not* enumerate
   repositories and write N copies. `:none` declares an entity without placing
   it (useful for a pack other entities reference).

Repository root = a directory containing `.git`, `.jj` or `.hg`. Discovery does
not descend into a repo it has already matched, so a nested checkout under a
matched root is not a separate target.

### Exclusion

`!` subtracts, and deny wins regardless of order:

```edn
:in ["projects" "!projects/vendor" "!projects/scratch"]
```

A selector containing only negatives is an error — there is nothing to subtract
from. `[:all "!x"]` is likewise an error: user scope has no per-project
granularity to carve out of. If you want "everywhere except", enumerate a
positive tree.

### Deliberately not in the language

| rejected | why |
|---|---|
| `{:under … :except … :where …}` map form | the vector already reads better and expresses the same two operations |
| predicates (`{:has "deps.edn"}`, `{:remote "github.com/art/*"}`) | a second matching engine, and content-based selection makes a plan depend on file contents that change under it |
| tags (`:in [:clojure :work]`) | tags need a registry keyed by project — which is the thing this proposal removes |
| glob depth (`projects/*` vs `projects/**`) | rule 2 already answers "how deep": as deep as it takes to find repo roots. `*` in a path segment is worth adding later if a real case shows up; it is not needed for any case in the motivating example |

`*` inside a segment (`"projects/clz-*"`) is the one extension with an obvious
shape if it is ever wanted: glob the segment, then apply rule 2 to each hit.

## Entity types

Each is a map in a flat top-level vector (or in per-kind vectors — see the two
file shapes below). Every one takes `:in` and `:tools`; `:tools` stays
orthogonal to `:in` and keeps its current meaning (which CLIs), defaulting to
every tool that supports the kind.

| key | entity | example |
|---|---|---|
| `:skill-packs` | a source of skills (keyed map) | `{[:gh "obra/superpowers"] {:in :all}}` |
| `:skills` | one skill from a pack, a repo, or a path (keyed map) | `{[superpowers "review"] {:in "projects"}}` |
| `:mcp` | an MCP server | `{:mcp :tracker :cmd "tracker-mcp -t stdio" :in "projects/clz"}` |
| `:provider` | a model provider | `{:provider :gateway :url "…" :key $KEY :in :all}` |
| `:permissions` | allow/ask/deny rules | `{:permissions {:allow {:Bash ["bb:*"]}} :in "projects/agentctl"}` |
| `:settings` | unified or per-CLI settings | `{:settings {:model "opus"} :tools [:claude] :in "projects"}` |
| `:memory` | a memory/AGENTS.md file | `{:memory "~/notes/AGENTS.md" :mode :symlink :in :all}` |
| `:trust` | mark a path trusted | `{:trust true :in ["Brain" "projects"]}` |
| `:hooks` | Claude Code hooks | `{:hooks {…} :tools [:claude] :in :all}` |

`:settings` covers the unified-vs-per-CLI split the current `:executors` map
handles by nesting. Unified keys (`:model`, `:thinking`, `:on`/`:off`) are
translated per tool; a key one CLI does not know is still reported as an
unsupported setting rather than dropped. A stanza that is genuinely CLI-shaped
narrows with `:tools`:

```edn
{:settings {:model "sonnet" :thinking $effort :on #{:auto-compact}} :in :all}
{:settings {:personality "pragmatic" :reasoning-effort $effort}
 :tools [:codex] :in :all}
```

That replaces `:executors` entirely: the top-level `:executors {:claude {…}}`
map becomes `{:settings {…} :tools [:claude] :in :all}`, and a project's
`:executors {:claude {:model "opus"}}` override becomes the same stanza with
`:in "projects/sample"`. Precedence: narrower `:in` wins over broader; among
equally narrow, later wins; `:tools`-specific wins over unified.

## Ids

Today the map key *is* the id — it is threaded through every `norm-*` as `:id`,
is what `state.edn` keys ownership on, and is what a plan line prints
(`mcps/{repl search}`). A flat vector of self-describing maps has no such key,
and ownership without stable ids is what makes prune wrong. So:

- **`:id` is always present in the normalized entity**, and always derivable.
- **Derivation per kind:** the entity's own value when it is a keyword
  (`{:mcp :tracker}` → `:tracker`); the last path segment of a ref for a pack
  (`[:gh "obra/superpowers"]` → `:superpowers`); for kinds with no natural name
  (`:permissions`, `:settings`, `:trust`), a digest of the normalized `:in`,
  since those entities *are* their placement.
- **An explicit `:id` always wins**, and is how two stanzas of the same kind and
  ref stay distinct.
- **A duplicate `(kind, id)` is an error**, not a merge — a map key gave that
  for free and a vector must buy it back.

This is also where the proposal *pays a price*. `docs/AGENTCTL.md` already
notes that deleting a project from `agents.edn` leaves nothing to point a prune
at, because the manifest stores ids and not paths. Under `:in`, targets are
discovered rather than declared, so a repo that disappears (or falls out of a
selector because a sibling directory was renamed) has the same problem for
*every* entity, not just for a deleted project. **The manifest must record the
resolved `(entity-id, tool, absolute path)` triple**, not the id alone. That is
a state-schema change, and it is the single biggest cost of this redesign — it
should be settled before any of the DSL is written.

## Two possible file shapes

**A — one flat vector.** Maximally entity-first; reads like Terraform resources.

```edn
{:#def {effort "high"}
 :agents
 [{:settings {:model "sonnet" :thinking $effort} :tools [:claude] :in :all}
  {:skill-pack [:gh "obra/superpowers"] :in :all}   ; not implemented: shape A
  {:mcp :tracker :cmd "tracker-mcp -t stdio" :in "projects"}
  {:trust true :in ["Brain" "projects"]}]}
```

**B — kind-keyed vectors.** Keeps the section skim of today's file; each section
is a vector of self-placing entities instead of a map joined to `:projects`.

```edn
{:#def {effort "high"}
 :settings [{:model "sonnet" :thinking $effort :tools [:claude] :in :all}]
 :mcps     [{:id :tracker :cmd "tracker-mcp -t stdio" :in "projects"}]
 :skills   {[superpowers "review"] {:in "projects"}}
 :trust    [{:in ["Brain" "projects"]}]}
```

**B is the better trade.** It keeps `-k, --kind` meaningful without a scan, keeps
the id-vs-kind disambiguation trivial (the section names the kind), and the GUI's
section layout survives. A is prettier in a snippet and worse in a 300-line file.

## Worked example — the shipped example file, restated

```edn
{:#def {effort "high" workspace "~/projects"}

 :settings
 [{:id :claude-defaults :model "sonnet" :output-style "Proactive" :thinking $effort
   :on #{:auto-compact} :tools [:claude] :in :all}
  {:id :codex-defaults :model "gpt-5.6-sol" :personality "pragmatic" :reasoning-effort $effort
   :tools [:codex] :in :all}
  {:model "opus" :on #{:ultracode} :tools [:claude] :in "projects/sample"}]

 :permissions
 [{:allow {:Bash ["bb:*" "clj:*"] :Mcp {:repl #{list_repls}}}
   :ask   {:Mcp {:repl #{eval}}}
   :in "projects/sample"}]

 :mcps
 [{:id :search  :cmd "/usr/local/bin/search-mcp --stdio" :in "projects/sample"}
  {:id :repl    :cmd "bun run $workspace/skills-repo/mcp-servers/repl-mcp/index.ts"
                :in "projects/sample"}
  {:id :tracker :command "~/.local/bin/tracker-mcp"
                :args ["-t" "stdio" "-url" "http://tracker.internal"]
                :env {"TRACKER_ACCESS_TOKEN" "!bw://dev-keys/tracker/token"}
                :tools [:claude :codex] :in "projects/sample"}
  {:id :notes   :url "https://notes.example.com/mcp" :type :http
                :bearer-token-env "MCP_BEARER_TOKEN" :tools [:codex] :in :all}]

 :skill-packs
 {[:gh "example/agent-skills"]              {:alias shared :ref "main"}
  [:uri "file://$HOME/projects/skills-repo"] {:alias skills-repo}}

 :skills
 {[skills-repo "review"]                   {:in "projects/sample"}
  [:uri "~/experiments/skills/adhoc"]      {:mode :copy :acli [:cc] :in :all}}

 :memory   [{:from "~/notes/AGENTS.md" :mode :symlink :in :all}]

 :providers
 [{:id :gateway :url "https://api.example.com/v1" :key $EXAMPLE_API_KEY
   :api "responses" :models :all :in :all}
  {:id :local :url "http://127.0.0.1:8080" :models ["vendor/model-a"]
   :overrides {"vendor/model-a" {:maxTokens 128000}}
   :tools [:pi :omp :llm] :in :all}]

 :trust [{:in "projects/sample"}]}
```

Every cross-reference that used to run through `:projects` is gone. What
survives is `:from` (skill → pack), which is a genuine dependency and not a
placement.

## What this buys

- **A new repo is covered the moment it exists.** `:in "projects"` is a
  standing rule, not a list to maintain. Today every new checkout means another
  `:projects` entry repeating the same four keys.
- **One reading order.** "What does this pack do" is answered by its own line.
- **Three join functions disappear** (`scope-mcps`, `scope-skills`,
  `bare-project-skills`) along with the `:used-by` / `:named-by` derivations and
  the "bare id resolved by scanning packs" special case.
- **`:scope` stops meaning two things.**

## What it costs — blast radius

| today | under `:in` |
|---|---|
| `config/normalize`'s `:projects` map | **dies.** Sections become vectors; `norm-project` goes |
| `scope-mcps`, `scope-skills`, `bare-project-skills` | **die.** Placement is declared, not derived |
| `config/active-tools` — derives the active tool set from `(keys (:tools proj))`, which is what makes "settings for a tool no project names are not applied" work | **loses its input.** Replacement: the union of `:tools` across all declared entities, defaulting to all tools when no entity narrows. Weaker signal — a stray provider stanza now activates a tool. Worth keeping only if `--tool` remains the real control |
| `-p, --project ID` | **changes meaning:** takes a path or a selector (`-p projects/clz`), and filters resolved targets rather than looking up a declared id. Arguably better; it stops being a lookup into a registry that may not name the repo you are standing in |
| `-k, --kind projects` | **dies** as a kind; `trust` takes its place for the one thing it uniquely did |
| project `:path` / `:parent` / `~/projects/<key>` default | **die.** A selector *is* the path; there is nothing left to default |
| `import` — emits `:projects` today | **rewritten.** It must now emit selectors, and the honest output is one literal path per discovered project (`:in "projects/clz"`). Import stays lossy w.r.t. sugar, as documented; it can never infer that two literal paths were meant as `"projects"` |
| GUI form — controls derived from `config/capabilities` and the adapters | **mostly unchanged** under shape B, since the sections survive. The Projects pane becomes a *resolved targets* pane: which repos each selector currently matches, which is strictly more useful than an editable registry |
| `state.edn` ownership manifest | **schema change, mandatory.** Must record resolved absolute paths per entity (see Ids above) or prune breaks for discovered targets |
| `plan` rendering — `<sigil> [projects/<id>] <kind>/<ids>` | **unchanged in shape**, with the bracket carrying a path instead of a declared id |
| `:tools` selector, `:per-tool`, `:#def`, `:on`/`:off`, permissions mini-DSL, secret refs, `:extra` | **unchanged.** All orthogonal to placement |

## Decisions (confirmed)

1. `:all` is user scope, never fan-out.
2. Discovery runs on every apply.
3. Narrower matching selectors win.
4. Relative paths resolve against `$HOME`.

### Original questions and rationale

1. **Is `:all` really user-scope, or should it fan out?** Stated above as user
   scope, which is cheap and matches today's `:global`. The argument for fan-out
   is uniformity — one code path for every selector. The argument against is N
   symlinks and N prune records for something one file already does.
2. **Does discovery run on every `apply`?** It must, or a new repo is not
   picked up — but that makes `apply` walk the filesystem, and `apply` is
   documented as genuinely read-only (it is: walking is a read). Depth limit and
   an ignore list (`node_modules`, `.venv`, pack caches under
   `~/.agents/skill-packs`) are needed before this is safe on a large `$HOME`.
3. **Precedence between overlapping selectors** is stated as narrower-wins,
   which requires a total order on selectors. "Number of path segments in the
   matching pattern" is the obvious one and is well-defined; confirm it handles
   `:all` (rank 0) and `!` (not a winner, a filter) the way the examples read.
4. **`$HOME`-relative by default, or `:#def workspace`-relative?** The example
   `["Brain" "projects"]` reads as `$HOME`-relative. A `:#def root` binding that
   changes the base would be more flexible and less predictable.
