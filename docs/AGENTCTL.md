# agentctl — design

The reference for the DSL and the decisions behind it. `README.md` is the short
version: install, safety model, MCP scope, project skills, reading a plan.

Declarative provisioning for coding agents — Terraform/Ansible shaped, but the
managed infrastructure is `claude`, `codex`, `pi`, `omp` and `llm` on one
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

| Entity | claude | codex | pi | omp | llm |
|---|---|---|---|---|---|
| `:executors` (settings) | `settings.json` | `config.toml` | `settings.json` | `config.yml` | default model, aliases |
| `:mcps` | `claude mcp --scope user`, or the project's entry in `~/.claude.json` | `codex mcp` | `mcp.json` | `mcp.json` | — |
| `:skills` | `~/.claude/skills` | `~/.codex/skills` | `~/.pi/agent/skills` | `~/.omp/agent/skills` | — |
| `:memory` | `~/.claude/CLAUDE.md` | `~/.codex/AGENTS.md` | `~/.pi/agent/AGENTS.md`&nbsp;¹ | `~/.omp/agent/AGENTS.md`&nbsp;¹ | — |
| `:extra-providers` | — | `[model_providers.*]` | `models.json` | `models.yml` | `extra-openai-models.yaml` + `keys.json` |
| `:projects` | project `settings.json`, trust, MCP enablement | `[projects."…"]` trust | `trust.json` | — | — |
| `:skill-packs` | shared checkout under `~/.agents/skill-packs` | | | | |

¹ Unverified: the claude and codex paths are confirmed, the `pi`/`omp` global
memory paths are the documented convention but were not tested against a
running agent. Check before relying on them.

## DSL

A complete file is in [`examples/agents.edn`](../examples/agents.edn); the
sections below explain each part of it.

```clojure
{;; ---- root-level bindings; `$name` expands anywhere below -----------------
 ;; Root level only: :#let inside a project or an mcp is an error, not a scope.
 ;; A SHOUTED $NAME that is not bound here stays an environment reference.
 :#let {effort "high"
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
  :llm    {:model "gpt-5.6-luna" :aliases {:fast "vendor-mini"}}}

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
 {:review {:from :skills-repo :tools [:claude :codex :pi :omp]}
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

### `:#let`

Root-level bindings, expanded before anything else reads the file. `$name`
resolves as a bare symbol and inside strings (`"bun run $ws/a/index.ts"`), so a
binding can be spliced into a path or a command line.

Root level only — a `:#let` nested inside a project or an mcp is an error, not a
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

codex, pi and omp have no project-level MCP config; they report the skip as a
warning rather than silently installing the server user-wide.

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

A project's `:skills` names declared skills, whole skill-packs, or both:

```clojure
:skills   {:review {:from :skills-repo}}
:projects {:sample {:skills [:shared :review]}}
```

Naming a pack asks for every skill in it — the way to follow a pack that grows.
They are linked into `<project>/.claude/skills/`, not the user's home: a skill a
project asked for is that project's, and a link already pointing elsewhere (a
hand-made one into some other checkout, or a dangling relative one) is repointed
at the pack cache under `~/.agents/skill-packs`. `:scope :global` on the
declaration keeps a skill user-wide even when a project names it.

A pack that is not cloned yet can enumerate nothing, so a dry `apply` reports
`pack not fetched yet` for it. `apply!` clones first, then re-plans and links
what the clone brought — one run, not two.

Only claude has a project-level skills directory. For codex, pi and omp a
project's declared skills are installed user-wide instead — that is the only
place those tools read skills from — and are owned there.

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
compiled permission rule strings. `:#let`, `:on`/`:off`, the permissions
mini-DSL and `:cmd` are surface syntax and do not survive a round trip — the
result is semantically equal, not textually equal. Import over a hand-written
file backs the original up first.

## Tests

```
tests/run.sh                       # everything, against a scratch HOME
bb -cp src tests/agentctl_test.clj  # unit
tests/test-agentctl.sh              # e2e
```
