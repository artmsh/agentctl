# agentctl — design

The reference for the DSL and the decisions behind it. `README.md` is the short
version: install, usage, safety model; [GUIDE.md](GUIDE.md) covers MCP scope,
project skills and reading a plan.

Declarative provisioning for coding agents — Terraform/Ansible shaped, but the
managed infrastructure is `claude`, `codex`, `pi`, `omp`, `llm`,
`antigravity` (the `agy` CLI), `kimi` (the Kimi Code CLI) and `dsh` (the
DeepSeek Harness) on one machine.

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
   be resolved (locked vault, unset variable, a vault field holding `""` or
   `"null"`) leaves any existing credential untouched instead of blanking it:
   the Claude adapter writes nothing for that server, and `apply!` exits 1
   until the ref resolves.
5. **Unknown ≠ failure.** A locked Bitwarden vault or an unreachable endpoint
   is reported as `?`, not as an error.
6. Every mutation is backed up under `~/.config/agentctl/backups/<run>/`, one
   directory per run, first-write-wins so the copy is the pre-run state.

## Entities

| Entity | claude | codex | pi | omp | llm | antigravity | kimi | dsh |
|---|---|---|---|---|---|---|---|---|
| `:executors` (settings) | `settings.json` | `config.toml` | `settings.json` | `config.yml` | default model, aliases | `~/.gemini/antigravity-cli/settings.json` | `config.toml` | — (Cordis patch layers) |
| `:mcps` | `claude mcp --scope user`, the project's entry in `~/.claude.json`, or `.mcp.json` approved in `.claude/settings.local.json` | `codex mcp` | `mcp.json` | `mcp.json` | — | `~/.gemini/config/mcp_config.json` | `mcp.json` | — (Cordis patch layers) |
| `:skills` | `~/.claude/skills` | `~/.codex/skills` | `~/.pi/agent/skills` | `~/.omp/agent/skills` | — | `~/.gemini/config/skills` | `~/.kimi-code/skills` | `$DSH_HOME/skills` |
| `:memory` | `~/.claude/CLAUDE.md` | `~/.codex/AGENTS.md` | `~/.pi/agent/AGENTS.md` | `~/.omp/agent/AGENTS.md` | — | `~/.gemini/config/rules/AGENTS.md` | `~/.kimi-code/AGENTS.md` | `$DSH_HOME/AGENTS.md` |
| `:extra-providers` | — | `[model_providers.*]` | `models.json` | `models.yml` | `extra-openai-models.yaml` + `keys.json` | — | `[providers.*]` + `[models.*]` | — (Cordis patch layers) |
| `:trust` / legacy `:projects` | project `settings.json`, trust | `[projects."…"]` trust | `trust.json`; project `.agents/skills` from `:skills` | — | — | `trustedWorkspaces`, `<project>/.agents/skills` | — | —; project `.agents/skills` from `:skills` |
| `:skill-packs` | shared checkout under `~/.agents/skill-packs` | | | | | | | |
| `:plugins` | `claude plugin marketplace add` + `install`, then `extraKnownMarketplaces` / `enabledPlugins` in the placement's settings file | — | — | — | — | — | — | — |
| `:agents` | symlink `~/.claude/agents/<name>.md` or `<project>/.claude/agents/<name>.md` | — | — | — | — | — | — | — |

A `:plugins` key is `[:gh "owner/repo" "plugin"]` or `[:uri "…" "plugin"]`. Its
value takes `:marketplace` (the name the marketplace's own manifest gives it;
default the plugin's name), `:ref`, a branch, tag or commit of a git
marketplace, written as `source.ref` and passed to the CLI as `owner/repo#ref`,
`:version` (`:latest` or a pinned version string; without it only presence is
checked) and `:build`, a shell command run in each new install path. See
[GUIDE.md](GUIDE.md#claude-plugins) and [Plugin versions](GUIDE.md#plugin-versions).

An `:agents` key is `[pack "name"]` or `[pack ["a" "b"]]`, `pack` being a
declared `:skill-packs` entry's `:alias` or id. It links
`<pack root>/agents/<name>.md` as a Claude Code subagent definition; `:in`
places it user-wide or in projects. See [GUIDE.md](GUIDE.md#claude-subagents).

Pi's shipped `docs/usage.md` names `~/.pi/agent/AGENTS.md` as its global
instructions file. Omp's context-file provider loads `AGENTS.md` from its
`getAgentDir()` at the user level (normally `~/.omp/agent`).

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

The Kimi Code CLI resolves its data directory from `$KIMI_CODE_HOME`, falling
back to `~/.kimi-code`; agentctl resolves it the same way, so the two always
read and write the same files. Three of its tables need translating:

- A provider's `type` is the wire format, and Kimi names them differently from
  the DSL: `anthropic`, `openai`, `openai_responses`. A provider that names a
  dialect the DSL does not know is reported and skipped.
- A model is a table of its own, `[models.<alias>]`, naming the provider it
  routes through. The DSL carries one id per model, so an existing entry keeps
  the upstream id it already had.
- Kimi's schema *requires* `maxContextSize` on a model. A model that declares
  no context window is reported and left out rather than written, because a
  model without one leaves Kimi unable to load its own configuration.

A credential naming an environment variable is written as `apiKeyEnv` — the
variable's name, not its value — so the secret stays out of the file and Kimi
reads the variable when it starts. That also means an unset variable is not a
failure at apply time, unlike a `!bw://` ref that cannot be resolved.

The DeepSeek Harness resolves its home from `$DSH_HOME`, falling back to
`~/.dsh` (its `dsh-home-paths` package), and agentctl resolves it the same
way. Its local skill provider scans roots by rank: a project's `.dsh/skills`
(100) and `.agents/skills` (200), then the user's `$DSH_HOME/skills` (400)
and the shared `$DSH_AGENTS_HOME`/`~/.agents/skills` (500). agentctl links
user-wide skills into the Harness home and uses the shared `.agents/skills`
for a project's, so one project install serves codex, antigravity, kimi and
the Harness; a hand-authored `<repo>/.dsh/skills` is left alone. Instructions
come from `$DSH_HOME/AGENTS.md`, then the shared `$DSH_AGENTS_HOME`/
`~/.agents/AGENTS.md`, then the project `AGENTS.md` chain, so `:memory` is
one link at the Harness home.

Settings, MCP servers and model providers are **not** written: the Harness
composes them from Cordis patch layers — `$DSH_HOME/profiles/<profile>/cordis.patch.yml`
and the home-level `$DSH_HOME/cordis.patch.yml` that outranks every profile —
which are the user's own live configuration, are the settings UI's write
target, and may carry `!!js` expressions. A `:settings`, `:mcps` or
`:providers` entry naming `:dsh` is reported by validation and by the plan
rather than silently dropped. The `skills` CLI has no Harness agent id, so a
project install it must copy goes through that CLI's `universal` agent, which
writes `.agents/skills`.

## DSL

The current file shape is **entity-first**. Each declaration chooses its own
placement with `:in`; there is no project registry to keep in sync. Most kinds
are vectors of declarations. `:skills` and `:skill-packs` are maps keyed by
their source selector. [`SCOPE-DSL.md`](SCOPE-DSL.md) specifies discovery,
placement and ownership in detail.

```clojure
{:#def {workspace "~/projects"}
 :settings [{:id :claude-defaults :model "sonnet" :tools [:claude] :in :all}
            {:id :codex-defaults :model "gpt-5.6-sol" :tools [:codex] :in :all}]
 :mcps [{:id :search :cmd "search-mcp --stdio" :in "projects/sample"}
        {:id :notes :url "https://notes.example.com/mcp" :type :http
         :bearer-token-env "MCP_BEARER_TOKEN" :tools [:codex] :in :all}]
 :permissions [{:allow {:Bash ["bb:*"]} :in "projects/sample"}]
 :skill-packs {[:gh "example/agent-skills"] {:alias shared}}
 :skills {[shared "review"] {:in "projects/sample" :acli [:claude :codex]}}
 :memory [{:from "~/notes/AGENTS.md" :mode :symlink :in :all}]
 :providers [{:id :gateway :url "https://api.example.com/v1"
              :key $EXAMPLE_API_KEY :in :all}]
 :trust [{:in "projects/sample"}]}
```

`:in :all` means one user-wide install; `:in :none` declares a source without
placing it. A path such as `"projects/sample"` is relative to `$HOME` and
selects a repository. A directory that is not itself a repository selects the
repositories beneath it. `:in ["projects" "!projects/vendor"]` excludes a
subtree; exclusions win regardless of order. Discovery stops at repository
roots, does not follow child symlinks and skips common dependency and VCS
directories. Narrower paths win when declarations overlap.

For MCPs, `:at :machine` (the default) uses the machine-local placement;
`:at :repo` writes the project's `.mcp.json`. `:scope` remains a deprecated
alias. Tool support still applies, and unsupported placements produce warnings.
The ownership manifest records resolved paths so a repository that disappears
from a selector can still have its owned entries pruned. An old-format run
records none of its writes. It takes over from the manifest each key of a DSL
write that it declares again at the same project or home, so a later `{}` leaves
that key in place. A later DSL run that declares the key claims it back.

The older project-registry form remains readable for compatibility. The
following example and its project-specific sections describe that legacy form;
new imports emit the entity-first shape. A complete legacy file is in
[`examples/agents.edn`](../examples/agents.edn).

### Legacy map form

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
            :tools [:pi :omp :llm]}
  ;; one host, two wire formats and two upstream pools behind one router
  :router  {:url "https://router.example" :key $ROUTER_TOKEN
            :api ["anthropic-messages" "openai-completions"]
            :headers {"x-router-tenant" "acme"}
            :models [{:id "big"   :headers {"x-router-pool" "fast"}}
                     {:id "small" :headers {"x-router-pool" "cheap"}}]
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

### Codex `:keymap`

Declare shortcut overrides under `:executors :codex :keymap`:

```edn
{:executors
 {:codex {:keymap {:global {:open_transcript "alt-t"}
                   :composer {:submit ["enter" "ctrl-enter"]
                              :queue []}}}}}
```

This writes `[tui.keymap.global]` and `[tui.keymap.composer]` in
`~/.codex/config.toml`. Each action accepts a string or a vector of strings;
`[]` unbinds it. Use Codex's native context and action names, including
underscores; keyword and string keys both work. Codex validates the supported
actions and shortcuts for the installed version. See the
[official keymap reference](https://learn.chatgpt.com/docs/config-file/config-reference).

Codex CLI 0.169.0 has no keymap action that opens the model picker
(`open_model` is rejected in `global` and `chat` under strict configuration
checking); bind `chat.open_presets` and declare a `/model` preset instead (see
`:presets` below). `/keymap` shows the actions the installed release exposes.
An accepted `codex mcp list --json` invocation is no longer proof that an
action exists: unknown fields are ignored unless strict checking is enabled.
Re-probe in a scratch `CODEX_HOME` without changing the real configuration:

```sh
probe_dir=$(mktemp -d)
printf '[tui.keymap.global]\nopen_model = "alt-t"\n' > "$probe_dir/config.toml"
CODEX_HOME="$probe_dir" codex app-server --strict-config --print-startup-config
rm -rf "$probe_dir"
```

Repeat with `tui.keymap.chat`. Compare with `open_transcript`, a known valid
action, before treating a failure as evidence about the model-picker binding.

Only declared actions are updated. Undeclared bindings, other TUI settings,
comments, and Codex-owned state are preserved. Omitting an action (or using an
empty `:keymap`) leaves its existing binding alone. Import includes the current
keymap, and the control panel offers it as an EDN field.

### Codex `:presets`

Saved prompts for the Codex presets browser, keyed by slot:

```edn
{:executors
 {:codex {:keymap {:chat {:open_presets "alt-t"}}
          :presets {0 {:input "/model" :label "model picker"}
                    1 {:input "/model m-b --reasoning=low" :label "fast"}}}}}
```

Each slot writes its own `[tui.presets.<n>]` table in `~/.codex/config.toml`.
`:input` is required and is submitted exactly as typed, so a slash command with
arguments works; `:label` names the entry in the browser. `chat.open_presets`
opens the browser, and a digit then runs that slot: with the declaration above,
alt-t then 0 opens the model picker. `validate` rejects a slot outside 0-9, a
missing or non-string `:input`, and any field other than `:input` and `:label`
(codex-cli 0.169.0 accepts only those two and ignores the rest silently).

The write is surgery on the slot's table: the rest of `[tui]`, including the
counters Codex keeps there (`[tui.model_availability_nux]`), is never replaced.
A declared slot is owned whole, not key by key (`codex/whole-table?`). The table
is made to hold exactly the declared fields, so a preset Codex saved in that
slot is replaced, label included, and the plan shows the label going. Under the
entity DSL, dropping the slot removes the whole table. Owning only `input`
would leave `[tui.presets.N] label = …` behind, and Codex refuses that config
(`missing field input`). A slot saved from Codex itself (`n · new` in the
browser) that the config never declared is not agentctl's and stays. Import reads presets back as `{0 {:input …}}`.

### Codex `:status-line`

The native footer, `[tui] status_line`, as an item list:

```edn
{:executors
 {:codex {:status-line [:model-with-reasoning :five-hour-limit :weekly-limit
                        :used-tokens :context-used :current-dir :git-branch]}}}
```

Only that key of `[tui]` is set; Codex keeps its own state in the same table.
A multi-line array Codex wrote is replaced whole. Items are keywords or strings
in Codex's spelling. codex-cli 0.169.0 accepts any string at load time and drops
an unknown one at startup with "Ignored invalid status line items", so
`validate` checks each item against what that version renders (the list its
`/statusline` picker shows, plus the older spellings `project` and `model-name`)
and names the accepted ones on error. A map value is Claude Code's
command-style status line; from a unified settings row it reaches codex as an
unsupported setting, not an error. Import reads the list back as keywords.

### Codex `:permission-profiles`

`[permissions.<name>]` profiles, beside `:sandbox` (which writes
`default_permissions`, the active one):

```edn
{:executors
 {:codex {:sandbox "local-dev"
          :permission-profiles
          {:local-dev {:extends ":workspace"
                       :filesystem {"~/Library/Application Support/Example" "write"
                                    ":project_roots" {"." "write"}}
                       :network {:enabled true :domains {"localhost" "allow"}}}}}}}
```

A profile is plain data in Codex's spelling (codex-cli 0.169.0):
`description`, `extends` (`:read-only`, `:workspace` or another profile's
name), `workspace_roots` (root → boolean), `filesystem` (path → `read` / `write`
/ `none` / `deny`, or a map of subpaths; paths absolute, `~/…` or `:special`;
`glob_scan_max_depth`) and `network` (`enabled`, `domains` host → `allow` /
`deny`, and the rest of Codex's network keys, passed through). `validate`
rejects anything else in a profile, a non-string or unknown built-in
`extends`, a relative filesystem path or unknown mode, and a non-boolean
`enabled`; Codex itself ignores an unknown field silently.

`plan/toml-tree-ops` turns the nested map into one `toml-set-op` per table,
each owning only its declared keys. A nested map becomes a sub-table, unless
`config.toml` already holds that key as an inline table
(`domains = { localhost = "allow" }`). Then the declared entries are merged
into it where it stands, the hand-written ones are kept, and a write never
defines a table twice. An inline table a prune empties goes with its key. A
table defined by dotted keys (`network.domains = …`) is not recognised, and
the write is refused as invalid TOML rather than redefining it. Ownership is key
by key: under the entity DSL, removing a declared rule removes that key, a
removed profile loses every key agentctl wrote and the tables they leave
empty, and a rule added by hand is never touched.

Codex 0.169.0 will not start with `[permissions]` tables and no
`default_permissions`. It also refuses a default that names no profile the file
holds, or a built-in other than `:read-only`, `:workspace` and
`:danger-full-access`. `codex/permission-findings` checks both rules against
`config.toml` as it would be after the apply: the declared profiles, plus those
on disk that agentctl will not prune (everything under the legacy
`:executors` form, which never prunes). The default is `:sandbox` if declared,
otherwise what is on disk, unless agentctl wrote it and will prune it. A
violation is an error in `validate` and refuses `apply` before any op, with the
declared, kept and built-in names listed. It is checked only when agentctl
touches permissions, so a file broken by hand does not block an unrelated
setting. The refusal applies only to a run whose plan, after `-t`, `-k`, `-p`,
`--only` and `--user`, still writes Codex settings. Any other run, such as
`-k mcps -t codex`, cannot change permissions, so it shows the finding as a
warning and goes on. Import reads profiles back with field
names as keywords and paths and hosts as strings.

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

Claude Code and Codex (Codex: see below). In the entity DSL a hook is a `:hooks`
row placed like any other entity: `:in :all` writes `~/.claude/settings.json`,
and a project selector writes that repository's `.claude/settings.json`. A row
reaches Codex only when its `:acli` names `:codex`:

```clojure
:hooks [{:id :lock-acquire :event :PreToolUse :matcher "Write|Edit"
         :command "$HOME/.claude/hooks/memory-file-lock.sh acquire" :timeout 25 :in :all}
        {:id :session-start :event :SessionStart :command "./scripts/session-start.sh"
         :in "projects/sample"}]
```

The old format declares user-scope hooks under `:executors :claude :hooks` (or
`:executors :codex :hooks`); a hook under a project's `:executors :claude` there
is reported, not written:

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
prunes only that element. Changing a hook's `:event` moves it: the same
apply removes the element from the old event's array and adds it to the new
one, in Claude's `settings.json` and Codex's `hooks.json` alike. Declare only hooks you actually authored; a
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

#### Codex hooks

The same shape under `:executors :codex :hooks`, written to
`~/.codex/hooks.json`, whose layout matches Claude's (`hooks[event][]` of
`{:matcher … :hooks [{:type "command" …}]}`). In the entity DSL a `:hooks` row
reaches Codex only when its `:acli` names `:codex`; a row without `:acli`, with
`:acli :all`, or a `:hooks` key in a `:settings` row that does not name `:codex`
stays Claude's, because events and tool-name matchers differ between the two. Codex
hooks are user-wide only: a row placed in a project is reported, not written.
A `:settings` row's `:hooks` and the `:hooks` rows for the same tool add up,
hook by hook. Declaring one id in both with different bodies is an error.

```clojure
{:hooks [{:id :session-note :event :SessionStart :command "$HOME/bin/session-note"
          :timeout 10 :status-message "noting" :acli [:codex] :in :all}]}
```

codex-cli 0.169.0 dispatches `SessionStart`, `SessionEnd`, `UserPromptSubmit`,
`PreModelRequest`, `PostModelRequest`, `PreToolUse`, `PermissionRequest`,
`PostToolUse`, `PreCompact`, `PostCompact`, `SubagentStart`, `SubagentStop`,
`Stop` and `Interrupt`, and loads any other event key without a word, so
`validate` rejects an unknown event. A command hook takes `:command`
(required), `:timeout` (seconds), `:async`, `:status-message`,
`:command-windows` and `:additional-context-limit`; any other field is an
error. Ownership is Claude's: each id owns one element, found by value, and
vendor hooks in the file (installers write there) are never touched or pruned.
The comparison ignores keys whose value is null, because installers write
`"matcher": null` where agentctl omits the key. Declaring a vendor's exact hook
therefore adopts that group instead of appending a duplicate, and dropping the
declaration later removes it. The plan reports a converged hook as unchanged
(`--show-noop`). A hooks file that does not parse (one trailing comma is
enough) is never rewritten. The plan says so, `apply!` fails that op, and the
state keeps the element it last wrote. This holds for Claude's settings.json
hooks as well.

Codex will not run a hook until it is reviewed: it keeps a trust hash per hook
in `config.toml`, under `[hooks.state."<hooks.json path>:<event>:<group>:<index>"]`.
agentctl never writes those entries and never pre-trusts. Every new or changed
command needs review in Codex (it asks at startup, or `/hooks`). The keys are
positional, so agentctl keeps positions where it can: a new hook is appended
after everything already there, and a changed one is rewritten at its own
index, so only that hook needs review. Removing a hook moves every later group
of the same event up one index, and Codex then asks to review those again.

Import rebuilds a Codex hook only when agentctl's state records it and it is
still in `hooks.json`; the rest are a vendor's or hand-made, and import says
how many it left out. Declaring one would make it agentctl's to prune.

### Project location (legacy)

`:path` is the whole location. `:parent` is the directory it sits in, for the
usual case of one workspace root holding many projects — the project's key
supplies the last segment. Neither given falls back to `~/projects/<key>`.

```clojure
:projects {:sample {:parent $workspace}}   ; -> $workspace/sample
```

### Project executors (legacy)

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

### MCP scope (legacy)

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
| `:project` | `<project>/.mcp.json`, merged onto what is there, and approved in `<project>/.claude/settings.local.json` | a server the whole team should get from the repo |
| `:global` (default otherwise) | `~/.claude.json`, user-wide | a server wanted everywhere |

codex, pi, omp and antigravity have no project-level MCP config; they report the
skip as a warning rather than silently installing the server user-wide.

A `:project` server is approved by adding its name to `enabledMcpjsonServers` in
the project's `.claude/settings.local.json`, the list Claude Code reads, so it
starts without the approval prompt. Names already there, added by hand or by
that prompt, are kept. A name agentctl added is removed again when its server is
dropped or leaves `:project` scope; the manifest records which names those are,
so a server the user had approved before agentctl declared it stays approved. A
server held back because a secret did not resolve is not approved either:
nothing was written for it in `.mcp.json`. Older releases wrote the list under
the project in `~/.claude.json`; Claude Code moves that copy into
`settings.local.json` and deletes it on its next run, and agentctl no longer
plans it.

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
separately. The ownership manifest also records the resolved path, so deleting
the entire project declaration still prunes entries agentctl wrote there.

A user-wide server is written with `claude mcp add`, which cannot edit an
entry: an update removes the server and adds it again, after backing up
`~/.claude.json`, and puts the previous entry back if the add fails. Drift there is a declared env or header name the entry lacks,
never a value and never a name added by hand, and the add carries every env var
or header the declaration does not name, so a hand-added `Authorization`
survives. A name agentctl declared and the declaration has since dropped is
removed.

Both project placements are merged onto the entry already there, so an env
var, header or unmodelled key added by hand survives an update; the declared
type, command, args and url replace their old values. An env var or header
agentctl declared and the declaration has since dropped is removed: the
manifest records the names each server declares, so a dropped secret does not
stay in the file. A transport change
(stdio to http, or back) replaces every transport key instead: merged, the
stdio server's `command`, `args` and `env` would stay beside the new `url`. The
plan lists the keys that go:

```
~ projects/sample mcps/docs
      - args: ["docs-mcp"]
      - command: "/usr/local/bin/docs-mcp"
      ~ type: "stdio" -> "http"
      + url: "https://docs.example.com/mcp"
```

### Project skills (legacy)

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

Resolved local skills are linked into `<project>/.claude/skills/`, not the user's
home: a skill a project asked for is that project's, and a link already
pointing elsewhere (a hand-made one into some other checkout, or a dangling
relative one) is repointed at the pack cache under `~/.agents/skill-packs`.
`:scope :global` on an explicit `:skills` declaration keeps a skill user-wide
even when a project names it.

A skill whose source sits inside the project, such as one committed in its own
`.agents/skills`, is linked relatively (`../../.agents/skills/<name>`), so the
link resolves in any checkout and can be committed. The source directory itself
is never removed, copied over, or linked onto itself.

A pack that is not cloned yet can enumerate nothing, so a dry `apply` reports
`pack not fetched yet` for it. `apply!` clones first, then re-plans and links
what the clone brought — one run, not two.

For current entity-first declarations, a repository placement uses the skills
CLI. Claude reads `<project>/.claude/skills`, while Codex, Antigravity, Kimi,
Pi and the DeepSeek Harness share `<project>/.agents/skills`. Pi reads it only
in a project it trusts; see [Pi](GUIDE.md#pi) for how that is decided and how
it settles a skill name found both there and user-wide. A copy there is kept
while an agent placed in the project reads it and has no user-wide copy. The legacy project registry is kept for
existing files; see [`SCOPE-DSL.md`](SCOPE-DSL.md) for current placement rules.

### MCP shorthand

A bare string *is* the declaration — a command line, or a URL:

```clojure
:mcps {:search "/usr/local/bin/search-mcp --stdio"
       :remote "https://notes.example.com/mcp"}    ; -> :url, http transport
```

Anything beyond that (env, cwd, per-tool selection) needs the map form.

`import` redacts every MCP header value, for every tool, to a `!bw://<server>/<header>`
placeholder: agentctl writes headers resolved, and the value on disk says
nothing about whether it was a secret. A whole `$VAR` header is kept. Env
values, an MCP server's and Claude's settings `env` (`!bw://claude-env/<NAME>`),
are redacted the same way unless they are provably no secret: a whole `$VAR`, a
boolean, a number of up to five digits, or a common settings word such as
`debug` or `fast`. A token under a plain name, `DOCS_AUTH` or `UPSTREAM`, is a
placeholder too, and the import notes list each vault field to fill in.
An MCP argument that follows a credential-looking flag — `--api-key X`,
`--token=X` — becomes a `!bw://<server>/<flag>` placeholder the same way
(`!bw://docs/api-key`); a flag naming where the credential lives, such as
`--token-file`, keeps its value.

An MCP server's argv takes a reference wherever a credential goes: the
argument after a flag whose name says credential (`--token`, `--api-key`,
`--password`), or such a flag's `=value`. It is resolved when the plan is made,
masked in every output, and held back like an env value when it does not
resolve; `validate` checks it. Anywhere else in argv, `$VAR` is expanded when
the config is read and `!bw://` is plain text.

```clojure
{:mcps [{:id :docs :cmd "docs-mcp --api-key !bw://vendor/docs/api-key" :in :all}]}
```

For Codex HTTP servers, import maps `http_headers` to `:headers` and
`bearer_token_env_var` to `:bearer-token-env`. Header values are redacted to
`!bw://` references; apply resolves them and writes the native
`http_headers` table. Codex's `env_http_headers` and `http_headers_helper` are
not yet modelled by this DSL.

Kimi and Antigravity keep env vars and headers added to a server by hand; the
manifest records the names agentctl declared, and a name the declaration drops
is removed from the server, as for Claude.

An http server's `:url` may itself be a reference (`!bw://vendor/docs/url`),
for an endpoint that carries its credential in the path or the query. It is
resolved, masked and held back like a header, and `validate` checks it.

OMP writes an http or sse server's `:headers` resolved into the server's
`headers` map in `~/.omp/agent/mcp.json`; a header that does not resolve holds
the server back, as an env value does.

`:cmd "srv --transport stdio"` is one shell-ish line, split on whitespace with
single and double quotes honoured. `:command` + `:args` is the same thing
exploded; giving both keeps `:command` and appends `:args`. `:type` is an alias
for `:transport`.

### `:tools` and `:acli`

Every resource takes an optional `:tools [...]` selector. `:acli` is the short
spelling accepted by entity-first declarations, including the aliases `:cc`
and `:agy`. Omitted, a resource fans out to every tool that supports its kind.
The legacy `:projects` map also accepts `:tools`.

### `:per-tool` overrides (legacy)

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
differently per tool, preserving those differences in the emitted declaration.

The plan names the source each tool actually links: a skill whose `:per-tool
:path` differs from its base source is its own line for that tool, and
`--verbose` adds the `ln` command and `link <dest> -> <source> (was <old>)`
under every skill line, user-wide ones included.

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

### Dialects and headers

`:api` is one wire format, or a vector of them in preference order. A vector
says the provider serves all of them and lets each tool take the one it can
speak: pi and omp read the format off the entry, `llm` writes an
OpenAI-completions file and nothing else. A provider that offers a tool no
dialect it speaks is reported as a skipped provider, not silently dropped.

A vector `:api` also means `:url` names the host and nothing more — agentctl
appends the path root the chosen dialect is served under (`/v1` for
`openai-completions` and `responses`, nothing for `anthropic-messages`, which
clients suffix themselves). A scalar `:api` is passed through untouched, path
and all, so existing entries keep the URL they were written with.

`:headers` rides on every request to the provider, and a model may carry its
own on top:

```clojure
:router {:url "https://router.example"
         :api ["anthropic-messages" "openai-completions"]
         :headers {"x-router-tenant" "acme"}
         :models [{:id "big" :headers {"x-router-pool" "fast"}}]}
```

Values take the same `$VAR` / `!bw://` forms as `:key`. This is what lets one
entry cover two upstream pools of the same router: the pool is a header, so it
rides on the models that belong to it instead of forcing a second provider row
whose only difference is one header value. `llm` has no provider-level header
field, so the provider's headers are merged onto each of its models there.

A model may also declare what it can do. A custom id or alias is nothing a
tool can look up, so without this pi treats it as text-only, without thinking,
at its generic 128K context / 16K output:

```clojure
:sw {:url "https://sw.example" :api "anthropic-messages"
     :models [{:id "opus-5-5"
               :reasoning true
               :input [:text :image]
               :context-window 1000000
               :max-tokens 128000
               :cost {:input 5 :output 25 :cacheRead 0.5 :cacheWrite 6.25}
               :thinking-level-map {:minimal nil :xhigh "xhigh"}
               :compat {:forceAdaptiveThinking true}}]}
```

| DSL key | pi `models.json` field | Checked |
|---|---|---|
| `:reasoning` | `reasoning` | `true` / `false` |
| `:input` | `input` | non-empty, of `text` and `image` |
| `:context-window` | `contextWindow` | positive integer |
| `:max-tokens` | `maxTokens` | positive integer |
| `:cost` | `cost` | `input`, `output`, `cacheRead`, `cacheWrite` numbers (USD per 1M tokens); `tiers` passed through |
| `:thinking-level-map` | `thinkingLevelMap` | levels `off` … `max`; value a string, a number, or `nil` (level unsupported) |
| `:compat` | `compat` | a map, passed through; `forceAdaptiveThinking` must be boolean |

pi's own spelling (`:contextWindow`, …) is accepted as well, though not both for
one field. Nested maps are pi's own and are written as given. `false`, and
`nil` inside a map, are declarations and are written; a field left out is
left alone, so whatever pi already stores for that model — a hand-set value,
`inputLimits`, `samplingParams` — survives an apply. A key agentctl does not
know is reported by `validate` rather than dropped without a word. omp and
llm get no capability fields; their plans say so. The values are yours to know:
agentctl checks shapes, not what an upstream route really offers.

A provider's `:overrides` is written as pi's `modelOverrides` (and omp's), for
changing built-in models in place.

### `:hosts`

```clojure
{:hosts {:win {:ssh "user@example.com" ; ssh destination; options belong in ~/.ssh/config
               :os :windows           ; :windows, :linux or :macos
               :home "C:\\scratch"     ; optional: pins the remote home, ignoring CODEX_HOME
               :skill-packs {[:uri "file://$HOME/packs/x"] {:acli [:codex :awl]}}
               :skills {[:gh "owner/skill"] {:acli [:codex]}}
               :settings [{:acli [:codex] :sandbox "net"
                           :permission-profiles {:net {:extends ":workspace"
                                                       :network {:enabled true}}}}]}}}
```

A host holds its own declarations instead of selecting from the top level. A source
is a map key, so a pack placed in a local project for one tool could not otherwise
also be placed user-wide on a host for another. Top-level declarations stay local and
host ones never reach a local plan.

A host has no projects: `:in` is `:all` (or `:none` on a pack that a `[pack "skill"]`
key names), and anything else is a config error, as is a non-keyed `:skills` or
`:skill-packs`, or `:settings` that is not a vector of rows. Host
keys other than the ones above, and any tool but codex and awl, are reported as not
provisioned.

Codex settings on a host are planned by the codex adapter's own ops against a local
copy of the host's `config.toml`, read in the inventory's round trip. Once they have
run, the copy is written back whole: staged beside the file, checksummed, refused if
the file's SHA-256 is no longer the one read, then renamed over it. The replaced
file goes into the host's backups; the local copy is not backed up locally. The
writes are recorded in the host's state keyed by the host's path, and pruned there
as `ownership/prune-ops` prunes local ones. A symlinked `config.toml`, dangling or
not, is reported (`@` in the inventory, followed by what it points at) and planned
against, then never written through: `codex/withhold` turns the changes into one
warning that lists them and counts them as pending (`:withheld`), the rule the
local codex adapter follows.

A host has no `agents.edn` for awl to read, so `:awl` there places copies, in
`~/.agents/skills/<name>`. Codex keeps `$CODEX_HOME/skills`, and from 0.95.0 on also
reads `~/.agents/skills`, listing a skill once per copy. A skill selected by both
tools goes into `~/.agents/skills` alone when the host's `codex --version` is 0.95.0
or later. Otherwise it goes into both, with a warning when the version cannot be
read. Codex has to be on the host's `PATH` to be provisioned. awl is not looked for.
Ownership in the host's state file is per directory: `codex` for `$CODEX_HOME/skills`,
`awl` for `~/.agents/skills`. A skill whose placement changes is copied to its new
directory and pruned from the old one in the same run, writes first.

The transport is one generated script per operation, sent on stdin. Nothing is spliced
into the ssh command line, because Windows OpenSSH's `DefaultShell` may be cmd, PowerShell
or WSL's bash. Hence `powershell.exe` with an `-EncodedCommand` bootstrap that reads
stdin. Scripts are ASCII, with paths and contents in base64, since PowerShell 5.1 reads
stdin in the OEM codepage. An operation that does not end with a final `OK` line failed.

### `:statusline`

```clojure
{:statusline {:defaults {:segments [:model :context] :context-style :full
                         :warning-percent 50 :critical-percent 80}
              :agents {:pi {:segments [:model :context :cost]}}
              :bin "~/.local/bin/agent-statusline"     ; optional, agentctl's own
              :source "~/src/agent-statusline" ; optional: the renderer's checkout
              :acli [:cc :pi]}}                        ; optional: derive Claude's and Pi's hooks
```

The shared status-line file, `$XDG_CONFIG_HOME/agent-statusline/config.json` or
`~/.config/agent-statusline/config.json`, written whole and owned like any other
resource by path. Keys are the renderer's JSON keys in kebab-case, keyword values
become strings and `nil` becomes `null` (shown `∅` in a plan). Nothing else is
interpreted: the renderer's `--check` decides what is valid. Its ops are
`agentctl statusline/config`, selected by `-k statusline` or `--only statusline/config`
(which `-t` does not narrow) and never by `-t` alone. `config` is the only id `--only`
accepts for it. A symlinked file is replaced, never written through,
and `import` reads the file back. An `:awl` override plans nothing for awl,
which reads the same file only when built with `-Dstatusline`. `validate` and
`apply` report whether the `awl` on `PATH` was built that way.

`:acli` derives each listed CLI's own hook into the renderer. For `:cc`, that is Claude's
user `statusLine`, `'<bin>' --agent cc`, plus `--config` when the file is not at the
renderer's default path. `<bin>` is `:bin`, else `<source>/zig-out/bin/agent-statusline`.
For `:pi`, it is the extension `agent-statusline`, linked from
`<source>/adapters/pi-extension` and built with `zig build` in the checkout. Precedence: a
hand-written user-wide Claude `:status-line` wins, and so does an extension with that id
or from the same directory. Derived ops are `settings` and `extensions`, selected by `-t`
and `-k` like any other. Removing a CLI from `:acli` prunes only what was derived for it,
and a `statusLine` edited since it was written is kept. See
[the guide](GUIDE.md#shared-status-line) for validation, paths, ownership and awl.

### Secret references

| Form | Meaning |
|---|---|
| `$FOO` or `"!env://FOO"` | environment variable |
| `"$HOME/bin/x"` | a literal: `$VAR`, `${VAR}` and a leading `~` are expanded when the file is read. Only a whole `$NAME` is a reference |
| `"!bw://item/field"`, `"!bw://folder/item/field"` | Bitwarden item field; `password` and `username` read the item's login, as does the login's own username for its password |
| `"!bw://item"` | the item's login password |
| `"!file://~/path"` | file contents, trimmed |
| `"!cmd://some command"` | stdout of a command |
| `"anything else"` | literal |

Plans print `bw:dev-keys/hosted/api-key`, never the resolved value. Changes that
end up writing a resolved secret into a tool's config file are tagged
`⚠ writes resolved secret` and counted before the confirmation prompt.

Every value a reference resolved to is masked (`"sk-t…ef"`) in every output:
the text plan, `-v`, `--json`, `apply!` and the GUI, for every tool, whatever
the field is called. So is the old value on the before side of a change to
one, and any old env or header value a change replaces or removes: a rotated
or dropped secret is still a secret, though nothing resolves it any more.
Inside an env or header map an unchanged value is masked too unless it is
plainly no secret (a boolean, a small number, a common settings word, a `$VAR`
or a path): the text plan never prints it, but `--json` prints the whole map. In argv the position decides: the
argument after a credential-looking flag, or such a flag's `=value`, is masked
in diffs, summaries, command lines and failure messages whatever it holds,
however short. A resolved value found inside other text is masked only as a
token of its own, so one that is also a word in a host or a path leaves the
URL or the path readable.

A Bitwarden field that exists but holds `""` or the text `"null"` is missing,
not a value: written out, it is a header that authenticates nothing. So is an
`$ENV`, `!file://` or `!cmd://` reference that yields nothing but whitespace.
A field name the item does not have is missing too: a misspelt field never
falls back to the login password. When the item has a login, `validate` and the
plan say so — `did you mean item/password?` — since a ref that read the
password through that fallback now needs the field named. Claude's
settings `:env` takes the same forms as an MCP server's `:env`. A Claude MCP
server or settings `env` with any ref that does not resolve is held back whole,
as [GUIDE.md](GUIDE.md#unresolved-secrets) describes; a resolved one is printed
masked, and so is any old env or header value a change replaces or removes.

`codex` reads provider keys from the environment only — declare those as
`:key $VAR`; `validate` flags anything else.

## Commands

```
-f, --file PATH    config file (default ~/.config/agents.edn)
-t, --tool TOOL    restrict to a tool (repeatable)
-k, --kind KIND    restrict to a kind (repeatable; --help lists them). An
                   unknown one exits 1, and so does one the config's format
                   plans nothing as: projects in the entity DSL (which plans
                   trust, permissions and settings instead), trust in the
                   legacy map form (which plans all three as projects); with
                   --host too. A config no section of which decides the format
                   ({}, or only :statusline/:extensions/:hosts) gets a ⚠ for
                   -k trust instead, naming no format
-p, --project ID   restrict to one declared project — selects everything that
                   project owns, whatever its kind (its servers and skills are
                   :mcps / :skills ops, so --kind trust, or projects in the
                   legacy form, would miss them)
    --only KIND/ID apply/apply!: one entity wherever it is placed (repeatable)
    --user         apply/apply!: user scope only, no project
    --json         machine-readable output: apply, apply! and validate print
                   one JSON document, apply with config warnings in
                   `findings` and apply! with what it `applied`; apply! --json
                   needs -y; an error, a refused config included, is
                   {"error": …} with exit 1
-v, --verbose      per-field diffs and target paths; for skills, the link
                   commands and what each link points at, before and after
    --show-noop    include unchanged and informational entries
    --deep         validate: probe the network (provider /models, git remotes)
    --replace      import!: overwrite instead of merging into the existing file
-y, --yes          apply!: skip the confirmation prompt
    --port N       gui: listen on this port (default: any free one)
    --no-open      gui: do not open a browser
    --debug        on an error, also print its stack trace to stderr (so does
                   AGENTCTL_DEBUG=1); stdout keeps the one error line or document
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

A plan with nothing to change prints `no changes — environment matches
agents.edn`, unless it carries a warning: a `=` block whose indented line says
why a declared setting went nowhere (an unsupported setting, a placement a tool
cannot take). That plan is printed in full, here, under `--host`, in the GUI's
text view, and under `--json` (as ops with `"warn": true`), and the exit code
still says only whether anything would change: 0.

`AGENTCTL_HOME` repoints every path (and the `codex`/`claude`/`llm` CLIs it
drives) at a scratch tree — that is how the e2e test provisions a throwaway
home.

TOML configs (Codex, Kimi) are read and checked by python's `tomllib`, so they
need python3 3.11+. agentctl uses `AGENTCTL_PYTHON` when set, with no fallback,
and otherwise the first of `python3.15` … `python3.11`, `python3` on `PATH`
that has `tomllib`. With none, a run that has to read or write one of those
files stops before its first op. Reading the file as empty instead would plan
what exists as new, and apply would never converge. For the same reason, a
file this python's `tomllib` cannot parse is an error that names the file, the
interpreter and the parse error. Codex reads TOML 1.1 (for example, a
multi-line inline table), and `tomllib` reads TOML 1.1 only from python 3.15. Point
`AGENTCTL_PYTHON` at such a python, or rewrite the construct as TOML 1.0.

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
  `plan/mask-op`, which `render-plan`, `plan-data` and `--json`'s `op->data`
  all go through, and `refs/scrub` takes every resolved value out of what is
  left; serializing raw `:diffs` would route around both and put a credential
  into a JSON payload.
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

`STATUSLINE` is the renderer's own verdict on `:statusline`, from
`agent-statusline --check` run on the candidate JSON. A rejection is an `error`
that also stops `apply`, or a `warn` on a run narrowed so that it cannot write the
file. A missing renderer, or one without `--check`, is
`unknown`, and a renderer that would read a different file in this environment
is a `warn`. With an `:awl` override it also says whether the `awl` on `PATH`
links the renderer (`ok`), does not (`warn`), or cannot be inspected (`warn`).
All of this describes this machine, so `validate --host` leaves it out.

`HOSTS` covers every host under `:hosts`, or only the one `--host` names, in which
case this machine's checks are skipped. A host that does not answer, or answers
only in part, is `unknown`, and its drift is not reported as clean.

`SKILLS` also repeats the plan's reports that hold once it has converged: a
skill whose source is missing, a project copy kept beside a user-wide skill
and why, one pi loads in place of a
user-wide skill of the same name, and skills placed in a project pi asks
about first or does not read. They are warnings and never fail the exit code.

## Import is lossy w.r.t. sugar

`import!` writes the canonical shape: `:command` + `:args`, explicit booleans,
compiled permission rule strings. `:#def`, `:on`/`:off`, the permissions
mini-DSL and `:cmd` are surface syntax and do not survive a round trip — the
result is semantically equal, not textually equal. Import over a hand-written
file backs the original up first.

Native built-in provider overrides without a base URL are skipped with an
import note: the provider DSL requires an endpoint. Their native entries stay
untouched; the emitted config must not contain a provider it cannot validate.

Legacy conversion retains project trust's executor selection, whole-pack
placements and bare skill names that resolve in a fetched pack. A pack that
has not been fetched cannot resolve a bare skill name yet; fetch and validate
the legacy configuration before converting it.

Import does not currently reconstruct project MCPs, project skills, hooks or
extensions from the environment. Replacing the source can prune owned
resources omitted by the scanners. Even a merge that preserves existing
declarations can discover additional trusted projects and bring their stale
owned resources into the prune plan. Keep existing declarations and inspect
`apply --all` before writing; a full-config `import` → `apply` fixpoint is not
yet guaranteed.

For a full-config live verification, first run `tests/run.sh`, confirm all six
CLIs (`claude`, `codex`, `pi`, `omp`, `llm`, `agy`) are installed, and save private
copies of the current source and ownership manifest. Prepare a separate
candidate that preserves current declarations, compare any legacy additions,
and run `validate -f <candidate>` plus `apply --all -f <candidate>`. Missing
CLIs, unresolved credentials and unexpected removals leave verification
incomplete. Only after reviewing that complete plan should the candidate
become the source for a backed-up `apply!`; require a second read-only plan
with no drift. Compare parsed configuration and plans for the import check,
rather than text or sugar spelling.

## Tests

```
tests/run.sh                       # unit, e2e, extensions and hosts, in a scratch home
```

`tests/test-hosts.sh` runs the real POSIX scripts through a stub ssh
(`AGENTCTL_SSH_CMD`). No runner has PowerShell, so the Windows scripts are unit-tested
as text only and need a live pass against a Windows host, under a scratch `:home`,
whenever they change.

`tests/test-statusline.sh` validates through `tests/statusline-stub`, which answers
`--check` with the renderer's diagnostic names for the inputs the tests feed it.
`AGENTCTL_TEST_STATUSLINE_BIN=<built agent-statusline>` runs the same assertions
against the real renderer.

Use this runner even for local verification. It sets `AGENTCTL_HOME` before
loading the code and supplies all six CLI stubs; running the unit file directly
can skip adapters whose CLIs are missing and can write backups into the real
home.

The e2e suite drives the GUI as a real subprocess (step 16): it parses the URL
the command prints, plans a buffer the file does not hold, asserts an
unconfirmed and a stale apply write nothing, and checks that a credential in an
existing `.mcp.json` never reaches the browser. Step 17 drives the controls the
same way — one field, a shorthand expansion, an added and a removed entry, an
unreadable value — and asserts the comments, the `:#def` references and the file
on disk all come through untouched.
