# agentctl — design

The reference for the DSL and the decisions behind it. `README.md` is the short
version: install, safety model, MCP scope, project skills, reading a plan.

Declarative provisioning for coding agents — Terraform/Ansible shaped, but the
managed infrastructure is `claude`, `codex`, `pi`, `omp`, `llm` and
`antigravity` (the `agy` CLI) on one
machine.

One file (`~/.config/agents.edn`) declares the desired state. `agentctl`
computes the difference against what is actually on disk and converges it.

```
agentctl apply       # what would change; read-only; exit 2 when drift exists
agentctl apply!      # converge
agentctl validate    # check config + environment; exit 1 on errors
agentctl import      # emit the agents.edn implied by the current environment
agentctl import!     # write it (existing file backed up first)
agentctl state       # what agentctl currently owns
agentctl gui         # the same, in a browser, with the dry run live beside it
```

## Design rules

1. **Tool config files are tool-owned.** Every target rewrites itself at
   runtime (`codex` maintains `[hooks.state]` and trust dialogs, `claude` owns
   `~/.claude.json`, `pi`/`omp` rewrite their model catalogues). agentctl never
   writes a whole file it does not own: TOML is edited by *segment surgery*,
   JSON/YAML by key path, and MCP servers go through each CLI's own subcommand.
2. **Nothing is deleted unless agentctl created it.** Ownership is recorded in
   `~/.config/agentctl/state.edn`. Hand-installed skills and hand-added MCP
   servers survive every apply; only resources that agentctl added and that
   have since left `agents.edn` are removed.
3. **`apply` is genuinely read-only** — no state write, no directory creation —
   and exits 2 on drift so it is usable from cron or CI.
4. **Secrets are references, never literals.** `agents.edn` holds
   `!bw://…`/`$ENV` refs; values are resolved at apply time. A ref that cannot
   be resolved (locked vault, unset variable) leaves any existing credential
   untouched instead of blanking it.
5. **Unknown ≠ failure.** A locked Bitwarden vault or an unreachable endpoint
   is reported as `?`, not as an error.
6. Every mutation is backed up under `~/.config/agentctl/backups/<run>/`, one
   directory per run, first-write-wins so the copy is the pre-run state.

## Entities

| Entity | claude | codex | pi | omp | llm | antigravity |
|---|---|---|---|---|---|---|
| `:executors` (settings) | `settings.json` | `config.toml` | `settings.json` | `config.yml` | default model, aliases | `~/.gemini/antigravity-cli/settings.json` |
| `:mcps` | `claude mcp --scope user`, or the project's entry in `~/.claude.json` | `codex mcp` | `mcp.json` | `mcp.json` | — | `~/.gemini/config/mcp_config.json` |
| `:skills` | `~/.claude/skills` | `~/.codex/skills` | `~/.pi/agent/skills` | `~/.omp/agent/skills` | — | `~/.gemini/config/skills` |
| `:memory` | `~/.claude/CLAUDE.md` | `~/.codex/AGENTS.md` | `~/.pi/agent/AGENTS.md`&nbsp;¹ | `~/.omp/agent/AGENTS.md`&nbsp;¹ | — | `~/.gemini/config/rules/AGENTS.md` |
| `:extra-providers` | — | `[model_providers.*]` | `models.json` | `models.yml` | `extra-openai-models.yaml` + `keys.json` | — |
| `:projects` | project `settings.json`, trust, MCP enablement | `[projects."…"]` trust | `trust.json` | — | — | `trustedWorkspaces`, `<project>/.agents/skills` |
| `:skill-packs` | shared checkout under `~/.agents/skill-packs` | | | | | |

¹ Unverified: the claude and codex paths are confirmed, the `pi`/`omp` global
memory paths are the documented convention but were not tested against a
running agent. Check before relying on them.

The antigravity binary is `agy`, not the tool key. Its two roots are distinct on
purpose: `~/.gemini/antigravity-cli` holds the CLI's own settings blob, while
`~/.gemini/config` is the *customization root* the agent scans — the same layout
a project's `.agents/` directory uses. `~/.gemini/settings.json`,
`~/.gemini/GEMINI.md` and `~/.gemini/skills` belong to the separate Gemini CLI
and are never touched.

Antigravity spells an MCP endpoint `serverUrl` and inverts the flag as
`disabled`; agentctl translates both directions, so one server declared for
several tools stays a single entry in `agents.edn`. `agy mcp add` is not used —
it rejects `--env` on http servers, which makes an http server carrying a token
unexpressible through the CLI.

`trustedWorkspaces` is a bare array rather than a map keyed by path, so agentctl
unions the declared projects onto it and never removes an entry. `:trusted
false` is a no-op there, not a revocation. Because the whole list is written by
one op, that op carries no project: `-p <id>` provisions the project's skills but
leaves trust alone. Apply without `-p` to grant it.

`:model` for antigravity is a display name (`"Gemini 3.7 Flash (Medium)"`), not
a slug — a value copied from another tool's stanza fails silently.

## DSL

A complete file is in [`examples/agents.edn`](../examples/agents.edn); the
sections below explain each part of it.

```clojure
{;; ---- root-level bindings; `$name` expands anywhere below -----------------
 ;; Root level only: :#def inside a project or an mcp is an error, not a scope.
 ;; A SHOUTED $NAME that is not bound here stays an environment reference.
 :#def {effort "high"
        workspace "~/projects"}

 ;; ---- global per-CLI settings -------------------------------------------
 ;; :on / :off are the terse spelling of boolean flags.
 :executors
 {:claude {:model "sonnet" :output-style "Proactive" :thinking $effort
           :on #{:auto-compact}}
  :codex  {:model "gpt-5.6-sol" :personality "pragmatic" :reasoning-effort $effort}
  :pi     {:model "coder-small" :provider "gateway" :thinking $effort}
  :omp    {:personality "pragmatic"
           :model-roles {:default "gateway/vendor/model-a"
                         :tiny    "gateway/anthropic/claude-haiku-4.5"}}
  :llm    {:model "gpt-5.6-luna" :aliases {:fast "vendor-mini"}}
  ;; antigravity models are display names, not slugs
  :antigravity {:model "Gemini 3.7 Flash (Medium)" :mode "accept-edits"}}

 ;; ---- MCP servers, fanned out to the tools that support them ------------
 ;; :cmd is one shell line (quotes honoured); :command + :args is the same
 ;; thing exploded. :type is an alias for :transport.
 :mcps
 {:search  {:cmd "/usr/local/bin/search-mcp --stdio"}
  :repl    {:cmd "bun run $workspace/skills-repo/mcp-servers/repl-mcp/index.ts"}
  :tracker {:command "~/.local/bin/tracker-mcp"
            :args ["-t" "stdio" "-url" "http://tracker.internal"]
            :env {"TRACKER_ACCESS_TOKEN" "!bw://dev-keys/tracker/token"}
            :tools [:claude :codex]}
  :notes   {:url "https://notes.example.com/mcp" :bearer-token-env "MCP_BEARER_TOKEN"
            :type :http
            :tools [:codex]}}

 ;; ---- where skills come from -------------------------------------------
 :skill-packs
 {:shared      {:uri "https://github.com/example/agent-skills" :ref "main" :dir "skills"}
  :skills-repo {:uri "file://$HOME/projects/skills-repo"       :dir "skills"}}

 ;; ---- installed skills --------------------------------------------------
 :skills
 {:review {:from :skills-repo :tools [:claude :codex :pi :omp :antigravity]}
  :adhoc  {:path "~/experiments/skills/adhoc" :mode :copy :tools [:claude]}}

 ;; ---- one memory file, linked into every agent --------------------------
 :memory
 {:shared {:from "~/notes/AGENTS.md" :mode :symlink}}

 ;; ---- providers and provider overrides ----------------------------------
 :extra-providers
 {:gateway {:url "https://api.example.com/v1" :key $EXAMPLE_API_KEY
            :api "responses" :models :all}
  :hosted  {:url "https://api.vendor.example" :key "!bw://dev-keys/hosted/api-key"}
  :local   {:url "http://127.0.0.1:8080" :models ["vendor/model-a"]
            :overrides {"vendor/model-a" {:maxTokens 128000}}
            :tools [:pi :omp :llm]}}

 ;; ---- per-project overrides ---------------------------------------------
 ;; :permissions is a mini-DSL:
 ;;   {:Bash ["bb:*"]}             -> "Bash(bb:*)"
 ;;   {:Bash :all}                 -> "Bash"
 ;;   {:Mcp {:repl #{list_repls}}} -> "mcp__repl__list_repls"
 ;;   {:Mcp {:repl :all}}          -> "mcp__repl"
 ;; A flat vector of rule strings is already canonical and passes through.
 :projects
 {:sample {:path "~/projects/sample"                 ; default: ~/projects/<key>
           :trusted true
           :executors {:claude {:model "opus" :on #{:ultracode}}}
           :permissions {:allow {:Bash ["bb:*" "clj:*"]
                                 :Mcp {:repl #{list_repls}}}
                         :ask   {:Mcp {:repl #{eval}}}}
           :mcp [:tracker :search :repl]
           :skills [:review]}}}
```

### `:#def`

Root-level bindings, expanded before anything else reads the file. `$name`
resolves as a bare symbol and inside strings (`"bun run $ws/a/index.ts"`), so a
binding can be spliced into a path or a command line.

Root level only — a `:#def` nested inside a project or an mcp is an error, not a
local scope, and a binding may not reference another binding (map order is not
defined, so it would not be deterministic).

An unbound `$NAME` is left alone: that spelling is the environment reference.
An unbound *lowercase* `$name` warns, since it is far more likely a typo than a
deliberate env var.

### `:on` / `:off`

Terse spelling of boolean settings. `:on #{:a :b}` is `:a true :b true`;
`:off #{:c}` is `:c false`. An explicit `:a false` in the same map still wins.
Flags a tool does not know are reported as unsupported settings, not silently
dropped.

### `:permissions`

A mini-DSL that compiles to the rule strings Claude Code stores:

| declared | written |
|---|---|
| `{:Bash ["bb:*" "clj:*"]}` | `"Bash(bb:*)"`, `"Bash(clj:*)"` |
| `{:Bash :all}` | `"Bash"` |
| `{:Mcp {:repl #{list_repls}}}` | `"mcp__repl__list_repls"` |
| `{:Mcp {:repl :all}}` | `"mcp__repl"` |
| `["Bash(git status:*)"]` | unchanged — already canonical |

Buckets are `:allow` / `:ask` / `:deny`; each is written as its own key, so
`defaultMode` and `additionalDirectories` already in a project's settings.json
survive. Rule lists compare as sets — the CLI is free to reorder them without
that reading as drift.

Project scope only. Global `~/.claude/settings.json` permissions are hand-curated
and are never rewritten.

### `:hooks`

Claude Code only, global scope, declared under `:executors :claude :hooks`:

```clojure
:executors
{:claude {:hooks {:lock-acquire {:event :PreToolUse :matcher "Write|Edit"
                                 :command "$HOME/.claude/hooks/memory-file-lock.sh acquire"
                                 :timeout 25}
                  :session-start {:event :SessionStart
                                  :command "$HOME/.claude/hooks/session-start.sh"}}}}
```

Each id owns one `hooks[event][]` array element — `:matcher` omitted fires
unconditionally; `:command` defaults `:type` to `"command"`. `:async`,
`:async-rewake`, `:shell`, `:if`, `:status-message` and `:args` pass through
to the native command object for the hook kinds that use them.

`settings.json`'s `hooks` arrays are not agentctl's alone — Orca, moshi and
similar tools inject their own entries directly. An array carries no keys, so
agentctl locates *its* element by value, not by index or position, and never
touches an entry it did not write: declaring a hook here never disturbs
what another tool put in the same array, and dropping one from agents.edn
prunes only that element. Declare only hooks you actually authored; a
third party's own hook does not belong in agents.edn and reproducing it
here would fight that tool's own writes to the file.

`:hooks` can instead be grouped by event, a vector of declarations per event
rather than one id-keyed map:

```clojure
:hooks {:SessionStart [{:id :session-start :command "$HOME/.claude/hooks/session-start.sh"}
                       {:id :moshi-hook :command "'/opt/homebrew/bin/moshi-hook' claude-hook"
                        :async true}]}
```

`config/norm-hooks` flattens this into the same `{id decl}` shape the id-keyed
form already produces, with `:event` filled in from the group key — every
other hook-consuming path (`hook-ops`, `core/inventory`, structural
validation) sees one shape regardless of which was written, and the two
forms can be mixed in the same `:hooks` map. `:id` stays mandatory per entry:
it is what the ownership manifest keys on, not `:event`. An entry missing one
gets a positional placeholder (`:SessionStart-0`) so two id-less hooks can't
collide onto the same key, and `structural-findings` reports the missing
`:id` as an error rather than planning against a name nobody chose.

### Project location

`:path` is the whole location. `:parent` is the directory it sits in, for the
usual case of one workspace root holding many projects — the project's key
supplies the last segment. Neither given falls back to `~/projects/<key>`.

```clojure
:projects {:sample {:parent $workspace}}   ; -> $workspace/sample
```

### Project executors

A project's `:executors` says either *who it is for* or *what to override*:

```clojure
:projects {:sample   {:executors #{:claude}}                   ; claude only
           :workshop {:executors {:claude {:model "opus"}
                                  :codex  {}}}}                ; claude + codex
```

Both forms also decide `:for-tools` — naming executors is how a project says
which agents it applies to, so the `:sample` block above produces no codex, pi
or omp ops at all. An explicit `:tools` / `:for` wins over the inference;
neither given fans the project out to every tool that supports projects.

Naming executors also decides which tools agentctl touches **at all**. Once any
project names its executors, the union of those names is the whole set of tools
planned for: with only `:sample {:executors #{:claude}}` declared, a run plans
claude and nothing else — no codex settings, no llm or omp providers, even where
the top-level `:executors` or `:extra-providers` would otherwise fan out.
Settings for a tool no project names are reported as a warning rather than
applied. Two escapes: `--tool codex` is a direct order and outranks the config,
and a config whose projects name no executors (or has no projects) narrows
nothing.

Skills follow the same line: a skill a project names is installed only for that
project's executors, unless the skill declaration carries its own `:tools`.

### MCP scope

An MCP named in a project's `:mcp` belongs to that project, not to the machine.
Declaring it under `:mcps` gives it a definition; listing it in a project says
where it lives:

```clojure
:mcps     {:search "/usr/local/bin/search-mcp --stdio"
           :docs   "/usr/local/bin/docs-mcp --stdio"}
:projects {:sample {:mcp [:search]}}     ; search -> project, docs -> global
```

A project's server defaults to Claude Code's **local** scope: the project's own
entry in `~/.claude.json`, under `projects.<path>.mcpServers`. That is the one
scope private to this machine, which is where a server carrying a token belongs.

| `:scope` | Where it lands | For |
| --- | --- | --- |
| `:local` (default for a project's servers) | `~/.claude.json`, under the project's entry | anything with a credential; anything only you run |
| `:project` | `<project>/.mcp.json`, merged onto what is there, with `enabledMcpjsonServers` pre-approving it | a server the whole team should get from the repo |
| `:global` (default otherwise) | `~/.claude.json`, user-wide | a server wanted everywhere |

codex, pi, omp and antigravity have no project-level MCP config; they report the
skip as a warning rather than silently installing the server user-wide.

`.mcp.json` is normally committed, so a `:scope :project` server whose `:env`
carries a credential — a `!bw://` ref that resolves at apply time, or a token
written out literally — puts that credential in the repo. The plan marks it
`⚠ secret value written into the project`, and `validate` scans every project's
`.mcp.json`.

A server that agentctl now writes at local scope but that is *also* still
declared in the project's `.mcp.json` is reported, not deleted — agentctl never
wrote that file and will not edit what it does not own:

```
= projects/sample mcps/search
    ⚠ also declared in ~/projects/sample/.mcp.json — local scope owns it now; remove it there, the copy carries a credential
```

Until the tracked copy is removed the credential is still in the repo, and
Claude Code still offers the `.mcp.json` definition.

Dropping a server from a project's `:mcp` removes the entry agentctl wrote,
whichever scope it landed in — the user-wide prune loop deliberately skips
namespaced ids, since `claude mcp remove --scope user` would take out an
unrelated server of the same name, so the project's own entry is cleaned up
separately. A project deleted from `agents.edn` outright is the one gap: the
ownership manifest stores the id, never the path, so there is nothing left to
point a delete at.

### Project skills

A project's `:skills` names a whole skill-pack, a skill declared under
`:skills`, or — the common case — a skill directory found inside a declared
pack, with no `:skills` entry at all:

```clojure
:skill-packs {:skills-repo {:uri "file://$HOME/projects/skills-repo"}}
:projects    {:sample {:skills [:review]}}
```

`:review` needs no `:skills {:review {:from :skills-repo}}` entry as long as
exactly one declared pack has a `review` skill directory — that entry only
earns its keep for an override (`:mode`, `:per-tool`, a non-default `:scope`)
or to break a tie when the same skill name lives in more than one pack, which
is reported as an error rather than guessed. Naming a pack instead of a skill
asks for every skill in it — the way to follow a pack that grows.

Resolved skills are linked into `<project>/.claude/skills/`, not the user's
home: a skill a project asked for is that project's, and a link already
pointing elsewhere (a hand-made one into some other checkout, or a dangling
relative one) is repointed at the pack cache under `~/.agents/skill-packs`.
`:scope :global` on an explicit `:skills` declaration keeps a skill user-wide
even when a project names it.

A pack that is not cloned yet can enumerate nothing, so a dry `apply` reports
`pack not fetched yet` for it. `apply!` clones first, then re-plans and links
what the clone brought — one run, not two.

claude (`<project>/.claude/skills`) and antigravity (`<project>/.agents/skills`)
have a project-level skills directory. For codex, pi and omp a project's
declared skills are installed user-wide instead — that is the only place those
tools read skills from — and are owned there.

### MCP shorthand

A bare string *is* the declaration — a command line, or a URL:

```clojure
:mcps {:search "/usr/local/bin/search-mcp --stdio"
       :remote "https://notes.example.com/mcp"}    ; -> :url, http transport
```

Anything beyond that (env, cwd, per-tool selection) needs the map form.

`:cmd "srv --transport stdio"` is one shell-ish line, split on whitespace with
single and double quotes honoured. `:command` + `:args` is the same thing
exploded; giving both keeps `:command` and appends `:args`. `:type` is an alias
for `:transport`.

### `:tools`

Every resource takes an optional `:tools [...]` selector. Omitted, it fans out
to every tool that supports the entity — that is the point of the file: declare
an MCP server once, get it in all four agents. `:projects` takes the same
selector (`:tools [:claude :pi]` trusts a path in those two only).

### `:per-tool` overrides

Fan-out is the default, not a straitjacket. When one agent genuinely needs a
different value, override just that field:

```clojure
:mcps
{:tracker {:command "~/.local/bin/tracker-mcp"
           :args ["-t" "stdio" "-url" "http://10.0.0.5"]
           :per-tool {:codex {:args ["-t" "stdio" "-url" "http://tracker.internal"]}}}}

:extra-providers
{:gateway {:url "http://gateway.internal:8080/v1"
           :per-tool {:codex {:url "https://api.example.com/v1" :key $EXAMPLE_API_KEY
                              :api "responses"}
                      :llm  {:key-name "gateway-llm-cli" :models ["tool-use" "expert"]}}}}

:skills
{:helper {:from :skills-repo
          :per-tool {:codex {:path "~/.codex/skills/helper"}}}}
```

`import` writes these automatically wherever it finds a resource configured
differently per tool, which is what makes `import` → `apply` a no-op.

### Fields agentctl does not model

Tool-native MCP keys (`lifecycle`, `idleTimeout`, `directTools`, …) are merged
onto the existing entry and never dropped; declare new ones under `:extra`:

```clojure
:mcps {:helper {:command "~/.local/bin/helper-mcp"
                :extra {:lifecycle "lazy" :idleTimeout 10}
                :tools [:pi]}}
```

The same holds for providers: agentctl writes the fields it manages and leaves
the rest of the entry (`compat`, `discovery`, `modelOverrides`, catalogues) as
the tool wrote it.

### Secret references

| Form | Meaning |
|---|---|
| `$FOO` or `"!env://FOO"` | environment variable |
| `"!bw://item/field"`, `"!bw://folder/item/field"` | Bitwarden item field |
| `"!file://~/path"` | file contents, trimmed |
| `"!cmd://some command"` | stdout of a command |
| `"anything else"` | literal |

Plans print `bw:dev-keys/hosted/api-key`, never the resolved value. Changes that
end up writing a resolved secret into a tool's config file are tagged
`⚠ writes resolved secret` and counted before the confirmation prompt.

`codex` reads provider keys from the environment only — declare those as
`:key $VAR`; `validate` flags anything else.

## Commands

```
-f, --file PATH    config file (default ~/.config/agents.edn)
-t, --tool TOOL    restrict to a tool (repeatable)
-k, --kind KIND    restrict to settings|mcps|skills|providers|memory|projects|skill-packs
-p, --project ID   restrict to one declared project — selects everything that
                   project owns, whatever its kind (its servers and skills are
                   :mcps / :skills ops, so --kind projects would miss them)
    --json         machine-readable output
-v, --verbose      per-field diffs and target paths
    --show-noop    include unchanged and informational entries
    --deep         validate: probe the network (provider /models, git remotes)
    --replace      import!: overwrite instead of merging into the existing file
-y, --yes          apply!: skip the confirmation prompt
    --port N       gui: listen on this port (default: any free one)
    --no-open      gui: do not open a browser
```

### Reading a plan

```
AGENTCTL
+ skill-packs/shared
  !gh repo clone example/agent-skills ~/.agents/skill-packs/shared

CLAUDE
~ projects/sample mcps/{repl search}
    edit `~/.claude.json`:
      ~ repl.command: "/opt/homebrew/bin/bun" -> "bun"
= settings/{model output-style ultracode}
+ projects/sample skills/{review}
  !ln -s ~/projects/skills-repo/skills/review ~/projects/sample/.claude/skills/review
```

`+` create, `~` change, `-` remove, `=` unchanged or informational; `⚠` on a
line of its own is a config warning. A line starting `!` is a shell command the
run will execute, printed exactly as it will run — everything else is a file
edit.

A line reads `<sigil> [projects/<id>] <kind>/<ids>`: the section heading already
names the tool, and `projects/<id>` says the scope when the resource belongs to
one project. Ops writing one file for one project collapse into a single block,
their ids in braces (`mcps/{repl search}`); a shared prefix is factored out
(`permissions.{allow ask}`). A `=` block is a check that already passes — the
setting is declared, it was read, and the file agrees.

`AGENTCTL_HOME` repoints every path (and the `codex`/`claude`/`llm` CLIs it
drives) at a scratch tree — that is how the e2e test provisions a throwaway
home.

### gui

A loopback HTTP server and one page: the config on the left, its dry run on the
right. It adds no capability the CLI does not have — `/api/plan` is `apply` and
`/api/apply` is `apply!` — and exists because a plan read *while* editing is a
different thing from a plan read after saving and re-running.

- **The buffer is the config.** `config/parse-config` normalizes text that need
  not be on disk; the path only names the source and is what relative paths
  resolve against. Every keystroke re-plans, debounced. Invalid EDN is the
  normal state of a file being typed into, so it comes back as a message and
  the last good plan stays on screen, dimmed.
- **The controls edit the text, not a parsed copy of it.** A form that
  re-serialized the config would erase the comments, the blank lines and the
  `$name` references that make it readable, so `agentctl.edit` runs the change
  through a `rewrite-clj` zipper and touches only the node named — the same
  rule the codex adapter follows for `config.toml`. A new key copies the line
  break and indent its siblings already use.
- **The form is derived from the buffer as written**, `edn/read-string` and not
  `parse-config`: after `expand-defs`, `:thinking $effort` reads as `"high"`,
  and writing that back would bake the binding into one tool and silently
  leave the others pointing at nothing. A field bound to a `$name` shows the
  reference. Coming the other way, a whole value that reads as `$name` is
  written as a symbol; anything else, `~/$workspace/x` included, is a string.
- **A control is a picker only where the tool closes the set.** Claude Code
  publishes a JSON schema, so `theme` (seven values plus a `custom:<slug>`
  pattern) and `effortLevel` (low/medium/high/xhigh) are offered as pickers.
  `model` is deliberately not one: the same schema types it as a plain string,
  because a full model id is as valid as an alias, and a picker would forbid
  values the tool accepts — the aliases are suggestions instead. codex, pi, omp
  and antigravity publish nothing this can be read off, so their settings stay
  open text. A declared value outside a closed set is not an error either: it
  falls back to the text box it came from rather than vanishing from a picker
  that cannot represent it.
- **`:on` / `:off` are three-position switches**, one per boolean setting the
  tool has, because the DSL has three states and not two: on, off, and
  unstated — and unstated leaves the tool's own default alone rather than
  writing `false`. Moving a flag from one to the other rewrites two nodes, so
  the switch sends both keys in one batch. Which settings are boolean is read
  off the same table that types them, so a new flag appears without a second
  list to keep.
- **A binding is renamed in place.** `:#def` renders as a table of names and
  values because both halves are editable, and a rename is its own op: removing
  and re-adding the key would send it to the bottom of the map and drop the
  comment above it. A blank or malformed name, and a rename onto a name the map
  already holds, are refused by `edit` and not only by the browser — a
  duplicate key does not read back, and the pane that would report the problem
  is the one that could no longer render. References are deliberately not
  chased: the now-unbound `$name` shows up as the warning it is.
- **A pack's skills are switches.** The Skills section lists each declared pack
  with every skill directory it has on disk, on when the file installs it. The
  checkout is only known after normalization (`:root` is derived, not written),
  so the raw declaration goes through `config/norm-pack` first; a pack that is
  not on disk yet says so, because an empty group and a pack with no skills
  must not look the same.
- **The fields on offer come from the adapters** — `claude/setting-keys`,
  `omp/setting-paths` and the rest, plus `config/capabilities` for which tools
  can take which kind. A control that exists is one a plan would act on, and it
  cannot drift from the adapter that writes it.
- **Everything the schema does not model stays editable as EDN in place**:
  permissions, `:model-roles`, `:overrides`, `:per-tool`, and any key agentctl
  does not read. The bar is that nothing in the file is out of reach from the
  controls, not that every corner of the DSL gets a bespoke widget.
- **`/api/edit` returns the rewritten buffer and its plan, and writes nothing.**
  Each request carries the whole buffer, so the page keeps one edit in flight
  and coalesces the rest behind it; a second request built on pre-edit text
  would clobber the first, and apply is held until the queue drains so it can
  never converge a buffer one edit behind. Ops are order-independent within a
  batch: expanding a shorthand server is `:expand`, which does nothing if the
  node is already a map, because the controls keep sending it until they get a
  re-render. The form is never re-rendered under a field that has focus — that
  eats the caret and the last keystrokes with it.
- **The plan crosses the wire as rendered text, never as ops.** Masking lives in
  `plan/render-val`; serializing `:diffs` would route around it and put a
  credential read out of an existing tool config into a JSON payload.
  `plan/*color*` is bound to `false` and the page colours by sigil.
- **Apply is gated three ways**: an explicit `confirm` in the request body (the
  browser dialog is a courtesy, not the gate), a re-plan on the server, and the
  summary line the user was looking at — a plan that no longer says what it said
  is refused with 409 rather than applied.
- **Apply saves the buffer** to `agents.edn` after backing it up. Converging
  onto text that was never saved would show up as drift on the next CLI run.
- **A backup directory per request.** `util/backup!` assumes one run per
  process — its stamp is a per-process `delay` and the first copy of a file
  wins. A long-lived server breaks that, so each apply binds `*backup-root*` to
  `backups/gui-<timestamp>-<n>/`.
- **Loopback only, `Host` checked, one token per run**, handed out once in the
  URL the command prints. The server writes files; it is a capability, not a
  page. Plan and apply are serialized against each other.

## validate

Checks CLI presence, skill sources and `SKILL.md` frontmatter, pack checkouts,
MCP commands on `PATH`, provider URLs and pinned model ids (`--deep`), vault
reachability, dangling references between sections, per-tool unsupported
settings, plaintext credentials sitting in tool configs, and pending drift.

`SETTINGS` validates `~/.claude/settings.json` and every project's
`.claude/settings.json` against the community schema at
`https://json.schemastore.org/claude-code-settings.json` — the same URL
`apply!` writes into each file's `$schema` key. The schema is fetched under
`--deep` and cached to `~/.config/agentctl/cache/`; a plain `validate` reuses
that cache and reports `unknown` if none exists yet. A violation in a key
agentctl itself manages is an `error`; anything else in the file (it is
otherwise hand-curated) is a `warn` and never fails the exit code.

## Import is lossy w.r.t. sugar

`import!` writes the canonical shape: `:command` + `:args`, explicit booleans,
compiled permission rule strings. `:#def`, `:on`/`:off`, the permissions
mini-DSL and `:cmd` are surface syntax and do not survive a round trip — the
result is semantically equal, not textually equal. Import over a hand-written
file backs the original up first.

## Tests

```
tests/run.sh                       # everything, against a scratch HOME
bb -cp src tests/agentctl_test.clj  # unit
tests/test-agentctl.sh              # e2e
```

The e2e suite drives the GUI as a real subprocess (step 16): it parses the URL
the command prints, plans a buffer the file does not hold, asserts an
unconfirmed and a stale apply write nothing, and checks that a credential in an
existing `.mcp.json` never reaches the browser. Step 17 drives the controls the
same way — one field, a shorthand expansion, an added and a removed entry, an
unreadable value — and asserts the comments, the `:#def` references and the file
on disk all come through untouched.
