# agentctl guide

How agentctl behaves, section by section, moved out of the README. The entity-first DSL is
specified in [SCOPE-DSL.md](SCOPE-DSL.md), every key in [AGENTCTL.md](AGENTCTL.md), and the
shipped example is [examples/agents.edn](../examples/agents.edn).

## Config format

The [entity-first scope DSL](SCOPE-DSL.md) places resources directly with
`:in`: `:all` installs once at user scope, while `"projects"` discovers repositories
under `$HOME/projects` on each apply. Most sections are vectors; for example:

```clojure
{:settings [{:model "sonnet" :tools [:claude] :in :all}]
 :mcps [{:id :tracker :cmd "tracker-mcp -t stdio" :in "projects"}]
 :trust [{:in ["Brain" "projects" "!projects/vendor"]}]
 :skill-packs {[:gh "obra/superpowers"] {:alias superpowers :in "projects/web"}}
 :skills {[superpowers "brainstorming"] {:in :all}
          [:gh "cyxzdev/Uncodixfy"] {:in "projects/app" :acli [:codex]}}}
```

`:skill-packs` and `:skills` are maps keyed by source: `[:gh "owner/repo"]`,
`[:uri "…"]`, or `[pack "skill"]` where `pack` is a pack's `:alias`. `:acli`
selects agent CLIs (`:cc`, `:codex`, `:pi`, `:omp`, `:llm`, `:agy`, `:kimi`,
`:dsh`, `:awl`). `:deepseek` and `:deepseek-harness` are accepted spellings of
`:dsh`. `:awl` is valid on these two sections only:
`awl` reads this file itself, so agentctl does not
provision it and the value only decides what awl takes. An entry naming `:awl`
alone plans nothing, and a git pack is not cloned for it.

A skill placed in a repository is installed by the [`skills`](https://skills.sh) CLI,
run there as `npx -y skills add <source> -a <agent> … -y`: a copy lands in
`.agents/skills` (read by codex, antigravity, kimi, pi and the DeepSeek Harness),
claude gets a link in `.claude/skills`, and the install is recorded in
`skills-lock.json`. A dropped
skill is removed with `npx -y skills remove`. User-wide skills stay symlinks
into each tool's own directory. `AGENTCTL_SKILLS_CMD` overrides the command.

A skill a project installed that is now declared `:in :all` is removed from the
project with `skills remove` once the user-wide one is installed, provided the
copy still matches its source. The links into the copy and its lock entry go
in the same step. A lock entry whose copy is already gone, with at most a
dangling `.claude/skills/<name>` link left behind, is removed the same way.
A project copy is kept, and the plan says why, when:

- it differs from the user-wide source, or from the source of the project
  skill that would be linked in its place, because it is another skill of
  the same name (a copy the `skills` CLI installed is the CLI's to replace);
- git tracks it, or a link git tracks leads into it, because it is the project's own;
- a declared placement needs it: a `:per-tool :path`, another skill's source,
  or a pack that lives there. Removing it would leave that tool's link dangling;
- an agent placed in the project reads it and has no user-wide copy of that
  skill: every agent placed there reads the shared `.agents/skills`, a copy
  or a link alike (pi only where it reads the project's skills), and claude
  reads a copy its `.claude/skills/<name>` link leads into.

The same holds wherever agentctl would put something: no link and no
`skills add` lands on a directory that stays, and nothing is linked over the
skill's own source. A kept copy that the `skills` CLI installed keeps its
lock entry, since `skills remove` would delete the copy as well — and the CLI
clears a name from every agent's directory in the repository (`.cursor/skills`
as much as `.agents/skills`), so a name is not removed while any of those
must stay. A whole remote pack names its skills only once the CLI has
fetched it, so its install is not checked this way. A redundant link into a kept
copy, such as claude's `.claude/skills/<name>`, is still removed. A plan
with nothing left to change prints no warnings, so `validate` repeats these
reports under SKILLS for every agent; they do not fail its exit code.

Imports emit this format. The older map-and-project format ([AGENTCTL.md](AGENTCTL.md#legacy-map-form))
remains supported; use one format per file.

## Where you run it is a scope

`apply` and `apply!` read the working directory, because standing in a project
is asking about that project:

| cwd | what is planned |
| --- | --- |
| inside a declared project | that project — the innermost one, so a project nested in another wins |
| the workspace the declared projects are filed under, or a directory inside it | every declared project below where you stand |
| anywhere else | the whole config, unchanged |

The workspace is the deepest directory the declared projects' parents share, so
`~` is not one just because the projects live somewhere below it. A narrowed run
says so on its first line:

```text
$ agentctl apply
scope: project sample — this directory is inside it (--all for the whole config)
```

`--all` plans the whole config from anywhere; `--project` is the same narrowing
asked for by hand. Either one outranks the directory.

A project-scoped run also fetches the skill packs that project's skills come
out of — a pack is cloned once for the machine and carries no project of its
own, but a project asking for skills from a pack that is not on disk yet would
otherwise get nothing. The config's other packs are left alone.

Two things follow from this that are worth saying out loud: inside a project,
`apply` exits 0 when only the rest of the machine has drifted, and from the
workspace root the global settings, MCP servers and providers are not planned
at all. `--all` is how you ask about the machine.

## Applying one entity

agentctl owns what it wrote, and an unscoped run removes whatever it owns that
the config no longer declares. A manifest holding only the one entity you want
to add is therefore not a narrow change: run unscoped, it plans the removal of
everything else agentctl owns. Narrow the run instead, against the full config:

```sh
agentctl apply  --only skills/review       # the skill, user-wide and in every project it is placed in
agentctl apply! --only skills/review --only mcps/search
agentctl apply  --user                     # user scope only: no project is planned
agentctl apply  --user -k skills           # selectors combine
```

`--only KIND/ID` keeps the ops of one entity. The id is the entity's own, so a
project's copy matches as well as the user-wide one; a declared skill brings the
op that fetches its pack, so a skill from a pack not on disk yet is cloned and
linked in one run. A skill no longer declared is only removed, and fetches
nothing. `--user` keeps every op that lands outside a project, the packs cloned
for user-wide skills included, and does not combine with `-p`. Both outrank the
working directory, as `--all` and `-p` do. `-k skills` keeps the pack fetches
too, for the same reason.

A narrowed run (`-t`, `-k`, `-p`, `--only`, `--user`, or the working
directory) forgets ownership only of what it removed itself, so nothing outside
the selection is pruned or orphaned. `--user` still removes user-scope
resources dropped from the config; only `--only` is a single-entity change.

`-k` and the kind in `--only` must be kinds an op carries — `agentctl --help`
lists them — or the run exits 1, as do an `--only` whose kind `-k` leaves out
and any option agentctl does not know. The kind must also be one the loaded
config's format plans: an entity-DSL config files a project's trust,
permissions and settings under `trust`, `permissions` and `settings`, so
`-k projects` there exits 1; an old-format config plans all three as
`projects`, so `-k trust` there exits 1. The message lists the kinds the
format has, `--help` marks `projects` as legacy, `--host` runs are held to the
same rule, and the GUI refuses the same kinds for the buffer it plans. A
config with nothing that decides the format yet — `{}`, or only `:statusline`,
`:extensions` and `:hosts` — is in neither: `-k trust` there selects nothing
and is reported with `⚠`, naming no format. An `--only` or `-p` that nothing
declares or owns is reported with `⚠` too (under `--json`, in `warnings`),
because a run narrowed to a typo plans nothing and would otherwise read as
converged.

## Claude session retention

Claude Code session retention can be declared in days:

```clojure
{:settings [{:cleanup-period-days 3650 :acli [:claude] :in :all}]}
```

This writes the integer `cleanupPeriodDays` to `~/.claude/settings.json`.
`validate` checks its type and minimum against the cached SchemaStore schema;
use `validate --deep` to fetch the current schema.

## GUI

```sh
agentctl gui                 # opens a browser on a free loopback port
agentctl gui --port 8791 --no-open
```

The right-hand pane is the dry run. The left-hand pane has two tabs over the
same buffer:

- **setup** — controls. One card per executor, MCP server, skill pack, skill,
  memory file, provider and project, with the fields each one understands: text
  boxes, checkboxes, transport and scope pickers, and chips for the tools a
  resource targets or the servers and skills a project names. The fields on
  offer for a tool are the settings its adapter actually writes, so a control
  that exists is a control that does something. `+ field` reveals a key the
  file does not declare yet; `×` removes one. Anything the form does not model
  — permissions, model roles, per-tool overrides — is editable as EDN in place,
  so nothing in the file is out of reach.

  Four of them are worth calling out:

  - **Bindings** is a two-column table — name and value, both editable, with
    add and delete row. A rename happens in place, so the binding keeps its
    line, its position and the comment above it. An empty or malformed name is
    refused, and so is renaming onto a name the map already holds.
  - **Features** is the `:on` / `:off` sugar as one three-position switch per
    boolean setting: on, off, or unset. Unset is not off — it leaves the tool's
    own default alone, which is why the switch has three positions and not two.
  - A setting is a **picker** only where the tool's own schema closes the set:
    claude's `theme` and effort level are, its `model` is not — the schema
    types it as a plain string because a full model id is as valid as an alias,
    so the aliases are offered as suggestions instead. A value the file already
    holds outside a set (a `custom:<slug>` theme) stays editable as the text it
    is, and `other...` in a picker types a fresh one.
  - **Skills** lists each declared pack with every skill it has on disk as a
    switch: on when the file installs it, off when it does not. A pack
    agentctl has not fetched yet says so rather than showing an empty list.
- **agents.edn** — the text, editable directly.

Both write to the same buffer and re-plan on every change, so the plan answers
what you are editing rather than what is on disk. Edits go through the server,
which rewrites only the node you touched: comments, blank lines and `$name`
references survive, and a `:#def` binding is shown as the reference it is rather
than the value it expands to. The tool chips in the header and the `verbose` /
`show noop` toggles are `--tool`, `-v` and `--show-noop`, and `-t` given to
`gui` picks the chips it starts with. `-k` and `-p` given to `gui` narrow
every plan the page shows, which says so under the findings; the page has no
control for them, so they hold until the server stops. A `-p` that selects no
declared project is a warning there, as it is in the CLI.

**Apply** is the only thing that writes. It asks for confirmation, then saves
the buffer to `agents.edn` (backed up first) and converges, exactly as
`apply!` would; the file has to be saved or the next CLI run would plan against
the old config. A plan whose summary moved between the render and the click is
refused rather than applied — the environment can change under an open page.
Backups land under `~/.config/agentctl/backups/gui-<timestamp>-<n>/`.

The server binds to `127.0.0.1`, rejects requests carrying any other `Host`,
and requires a token generated per run and handed out once, in the URL it
prints. Nothing else on the machine can reach it.

## MCP scope

An MCP named in a project's `:mcp` belongs to that project, not to the machine.
Declaring it under `:mcps` gives it a definition; listing it in a project says
where it lives:

```clojure
:mcps     {:search "/usr/local/bin/search-mcp --stdio"
           :docs   "/usr/local/bin/docs-mcp --stdio"}
:projects {:sample {:mcp [:search]}}      ; search -> project, docs -> global
```

A project's server defaults to Claude Code's **local** scope: the project's own
entry in `~/.claude.json`, under `projects.<path>.mcpServers`. That is the one
scope private to this machine, which is where a server carrying a token belongs.

| `:scope` | Where it lands | For |
| --- | --- | --- |
| `:local` (default for a project's servers) | `~/.claude.json`, under the project's entry | anything with a credential; anything only you run |
| `:project` | `<project>/.mcp.json`, merged onto what is there, and approved in `<project>/.claude/settings.local.json` | a server the whole team should get from the repo |
| `:global` (default otherwise) | `~/.claude.json`, user-wide | a server wanted everywhere |

Codex, Pi and OMP have no project-level MCP config; they report the skip as a
warning rather than silently installing the server user-wide.

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
carries a credential — a `!bw://` reference that resolves at apply time, or a
token written out literally — puts that credential in the repo. The plan marks
it `⚠ secret value written into the project`, and `validate` scans every
project's `.mcp.json`. In either project placement the plan, and `apply --json`,
print a resolved env or header secret masked (`"plai…34"`, marked `(changed)`
when a rotation masks the same), and so is every old env or header value a change
replaces or removes, whatever the new declaration says: it may have come from a
reference the declaration no longer makes.

A server written at local scope that is *also* still declared in the project's
`.mcp.json` is reported, not deleted — agentctl never wrote that file and will
not edit what it does not own:

```
= projects/sample mcps/search
    ⚠ also declared in ~/projects/sample/.mcp.json — local scope owns it now; remove it there, the copy carries a credential
```

Until the tracked copy is removed the credential is still in the repo, and
Claude Code still offers the `.mcp.json` definition.

Dropping a server from a project's `:mcp` removes the entry agentctl wrote, in
whichever scope it landed. A server agentctl never installed is left alone.

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

## Unresolved secrets

A value in an MCP server's `:env` or `:headers`, or in Claude's `:settings`
`:env`, may be a reference (`$VAR`, `!env://`, `!bw://`, `!file://`, `!cmd://`)
that is resolved when `apply!` runs. A settings `env` is where a project's
`.mcp.json` server that reads `${DOCS_API_KEY}` from the environment gets its
value:

```clojure
{:settings [{:env {"DOCS_API_KEY" "!bw://vendor/docs-api-key"} :acli [:claude] :in "projects/sample"}]}
```

The resolved value is what lands in `settings.json`, so the plan flags the op
`⚠ secret value written into the project` (or into `~/.claude/settings.json`)
and prints the value masked, `"sk-t…ef"`; a rotated secret whose mask reads the
same on both sides is marked `(changed)`. An old value that a change replaces
or drops is masked too, even when the new value is a plain literal. A project's
`.claude/settings.json` is usually committed; `.claude/settings.local.json` is
the private one.

When a reference does not resolve — the vault is locked, the variable is unset,
the Bitwarden field is empty or holds the text `null`, the variable, file or
command yields nothing but whitespace, the reference is malformed — the MCP server, the
provider, or Claude's settings `env` object holding it is not written at all, in
any scope and for every tool. Writing the rest of it would leave a server that is
configured and cannot authenticate, and because the missing value would then be
missing from the desired entry too, the next plan would report no drift. The plan
names the server, the key and the reason instead:

```
= mcps/docs
    ⚠ secret unresolved: bitwarden vault locked (headers.api-key) — nothing written
```

Every other change in the run still applies. `apply` exits 2 while such a server
remains and `apply!` exits 1, listing what it held back, so a partial run is never
reported as converged. A server held back this way is not recorded as owned: one
that never got written cannot be pruned later, and a hand-made server of the same
name stays the user's. The GUI asks the vault again for every plan, so unlocking
it or rotating a secret shows without restarting `agentctl gui`.

For a user-wide server, whose secret `claude mcp add` stores and agentctl does
not read back, drift is decided by the env and header *names*: a declared name
missing from `~/.claude.json` is drift, a changed value is not. A name added by
hand, such as an `Authorization` header, is not drift either. `claude mcp add`
cannot edit an entry, so an update removes the server and adds it again, after
backing up `~/.claude.json`. If the add fails, the previous entry is put back
and the op fails, naming both. The add carries every env var (stdio) or header
(http) the declaration does not name, so a hand-added one survives. A name
agentctl declared and the declaration has since dropped is removed: the
manifest records the names each server declares.

`validate` checks the same references before anything runs: every MCP server's
`:env` and `:headers` and Claude's settings `:env`, user-wide and per project.
One a locked vault keeps is reported `?` (unknown, not a failure); one that is
missing, emptied or malformed is an error (`✗`) and makes `validate` exit 1.

## Kimi Code

Kimi Code CLI keeps its configuration in `$KIMI_CODE_HOME`, or `~/.kimi-code`
when that is unset — the same resolution Kimi itself does, so agentctl and
Kimi always read and write the same directory.

| section | file |
| --- | --- |
| `:settings` | `config.toml` |
| `:providers` | `config.toml`, `[providers.<id>]` |
| `:mcps` | `mcp.json`, under `mcpServers` |
| `:skills` | `skills/` |
| `:memory` | `AGENTS.md` |

A provider names the wire format in its `type`, and agentctl translates the
DSL's dialect into Kimi's spelling — `anthropic`, `openai`, `openai_responses`.
A provider whose credential is an environment variable is written as
`apiKeyEnv`, the variable's *name*, so the secret stays out of the file and
Kimi reads the variable when it starts:

```clojure
{:providers [{:id :sw :url "https://switch.yard/v1" :key "$SW_KEY"
              :api "openai-completions" :acli [:kimi] :in :all
              :models [{:id "kimi-k3" :reasoning true
                        :context-window 1000000 :max-tokens 131072
                        :input [:text :image]}]}]}
```

A model is a table of its own — `[models.kimi-k3]` — naming the provider it
routes through and the capabilities Kimi needs to price and route it. Kimi's
schema *requires* `maxContextSize`, so a model that declares no context window
is reported and left out rather than written: a model without one would leave
Kimi unable to load its own configuration at all.

Kimi has no project-level MCP configuration of its own, so a server scoped to
a project is reported and skipped for it. Its skills are the shared
`<project>/.agents/skills` directory, so a project skill installed for codex
or antigravity is read by Kimi too.

## DeepSeek Harness

The DeepSeek Harness keeps its user data in `$DSH_HOME`, or `~/.dsh` when
that is unset — the same resolution the Harness itself does, so agentctl and
the Harness read and write one directory. It ships as a desktop app, so `dsh`
is not necessarily on `PATH`; an existing home — or an explicit
`$DSH_HOME` — counts as installed.

| section | file |
| --- | --- |
| `:skills` | `$DSH_HOME/skills`, one link per user-wide skill |
| `:memory` | `$DSH_HOME/AGENTS.md` |
| `:projects` | the project's shared `.agents/skills`, and its `AGENTS.md` |

The Harness scans its own `skills/` directory, the shared
`~/.agents/skills` (`$DSH_AGENTS_HOME/skills` when that is set), and inside a
project `<repo>/.dsh/skills` and the shared `<repo>/.agents/skills`. agentctl
uses the tool's own home for user-wide skills and the shared directory for a
project's, so one install serves codex, antigravity, kimi and the Harness; a
hand-authored `<repo>/.dsh/skills` is left alone. The `skills` CLI has no Harness agent id, so a project install it
has to copy goes through that CLI's `universal` agent — the shared
`.agents/skills` directory the Harness reads.

Instructions are `AGENTS.md`: the user-global `$DSH_HOME/AGENTS.md`, then the
shared `~/.agents/AGENTS.md` (which agentctl does not write), then a
project's own chain.

The Harness composes its settings, MCP servers and model providers from Cordis
patch layers — `$DSH_HOME/profiles/<profile>/cordis.patch.yml` and the
outranking `$DSH_HOME/cordis.patch.yml` — which are the user's own live
configuration, are edited by the Harness's settings UI, and may carry `!!js`
expressions. agentctl does not write them. A `:settings`, `:mcps` or
`:providers` entry naming `:dsh` is reported by `validate` and by the plan
instead of being silently dropped.

## Hooks

Claude Code hooks. In the entity DSL a hook is a `:hooks` row placed like any other entity:
`:in :all` writes `~/.claude/settings.json`, and a project selector writes that
repository's `.claude/settings.json`:

```clojure
:hooks [{:id :lock-acquire :event :PreToolUse :matcher "Write|Edit"
         :command "$HOME/.claude/hooks/memory-file-lock.sh acquire" :timeout 25 :in :all}
        {:id :session-start :event :SessionStart :command "./scripts/session-start.sh"
         :in "projects/sample"}]
```

The old format declares user-scope hooks under `:executors :claude :hooks`; a
hook under a project's `:executors :claude` there is reported, not written:

```clojure
:executors
{:claude {:hooks {:lock-acquire {:event :PreToolUse :matcher "Write|Edit"
                                 :command "$HOME/.claude/hooks/memory-file-lock.sh acquire"
                                 :timeout 25}
                  :session-start {:event :SessionStart
                                  :command "$HOME/.claude/hooks/session-start.sh"}}}}
```

Each declared hook owns one element of `settings.json`'s `hooks[event]` array
— `:matcher` omitted fires the command unconditionally. `hooks` arrays are
not agentctl's alone: Orca, moshi and similar tools inject their own entries
directly into the same file. Since a JSON array carries no keys, agentctl
locates its own element by value, not by position, and never touches an
entry it did not write — declaring a hook here leaves every other tool's
entries alone, and dropping one from agents.edn prunes only that element.
Declare only the hooks you actually authored; a third party's hook belongs
to that tool, not to agents.edn.

Hooks can also be grouped by event instead of id, which reads better once
several share one:

```clojure
:hooks {:SessionStart [{:id :session-start :command "$HOME/.claude/hooks/session-start.sh"}
                       {:id :moshi-hook :command "'/opt/homebrew/bin/moshi-hook' claude-hook"
                        :async true}]}
```

`:event` is then implied by the group and left off each entry. `:id` is not
— it is still what the ownership manifest tracks, so it stays mandatory
there; a missing one is a structural error, not a guess. Both forms normalize
to the same thing and can be mixed in one `:hooks` map.

## Extensions

An extension is code a CLI loads, so installing one is more than a link: the
source is fetched and, when it ships sources rather than artifacts, built.
`:extensions` is a map keyed by source, like `:skill-packs`:

```clojure
{:extensions {[:gh "artmsh/pi-codemode-clj"]
              {:alias codemode-clj
               :acli  [:pi]
               :build "./build.sh"
               :tools ["+codemode_clj"]}}}
```

`apply!` clones the repository to `~/.agents/extensions/<id>`, runs `:build`
in that directory, links it into `~/.pi/agent/extensions/<id>`, and adds each
`:tools` entry to `defaultTools` in pi's `settings.json`, leaving the entries
it did not add alone. `<id>` is `:alias`, or the repository name without one.

| Key | Meaning |
| --- | --- |
| `:acli` | CLIs that load the extension. Only pi has extensions today, and it is the default. |
| `:build` | Shell command run in the checkout. It runs again when the revision or the command changes; a failed build fails `apply!` and nothing is linked or switched on. |
| `:tools` | Tool names the extension registers, as pi's `defaultTools` spells them (`"+name"` adds to the defaults). Not a CLI selector — that is `:acli`. |
| `:type` | `:git` or `:file`, when the URI does not say. |

A `[:uri "file:///path"]` source is used where it stands: nothing is cloned,
`:build` runs in that directory, and the link points at it — the shape for an
extension under development. A git source follows its remote: when the remote
moves, `apply` plans a fetch, a hard reset and a rebuild, so local edits in
`~/.agents/extensions` do not survive. Dropping an entry removes the link and
the `defaultTools` entries it brought; the checkout stays as a cache.
Extensions are user-wide, and `:in` does not apply to them.

## Shared status line

An external renderer, `agent-statusline`, draws the status line of several agents from one
JSON file: `defaults` for every agent, plus overrides under `agents.cc`, `agents.codex`,
`agents.awl` and `agents.pi`. `:statusline` declares that file, and agentctl writes it whole:

```clojure
{:statusline {:defaults {:segments [:model :limits :context :directory :branch]
                         :separator " · "
                         :color :auto
                         :context-style :compact
                         :short-model true
                         :show-pacing true
                         :warning-percent 50
                         :critical-percent 80}
              :agents {:pi {:segments [:model :context :directory :branch :cost]}
                       :codex {:context-style :percent}
                       :cc {:color nil}}
              :bin "~/.local/bin/agent-statusline"}}
```

The EDN is the renderer's JSON with kebab-case keys, which agentctl turns back into the
renderer's snake_case (`:context-style` is `context_style`). Keyword values are written as
strings, a namespaced keyword in full (`:x/auto` is `"x/auto"`), and `nil` is JSON `null`,
which in an agent's override means "inherit the default"; the plan shows it as `∅`. The
translation knows no option or value. A key agentctl has never heard of is written as
given, and the renderer judges it, so new renderer options need no agentctl release. Two
spellings that land on one JSON key (`:context-style` and `:context_style`) are a config
error. `:bin` is agentctl's own and is not written. Each override lands under its own agent
only.

**Validation** is the renderer's. `validate` and `apply` write the candidate JSON to a
temporary file and run `agent-statusline --check --config <tmp>`, which reads no stdin and
runs no git. Exit 1 is a config error naming the renderer's diagnostic, such as
`InvalidEnumTag`, `DuplicateSegment` or `InvalidThresholds`, and `apply` refuses to plan
anything until it is fixed. A run that cannot write the file (narrowed by `-t` or `-k` to
other things, or to projects) gets the same finding as a warning instead. The binary is
`:bin`, else `AGENT_STATUSLINE_BIN`, else `agent-statusline` on `PATH`, else the
`agent-statusline` a declared Claude `:status-line` command runs by path. A binary
implements `--check` only if it accepts `{}` and rejects a document with an unknown key
with exit 1. When none is found, or the one found fails either probe (one that predates
`--check` rejects everything, and something like `true` accepts everything), or it times out
after 10 s, `validate` reports `unknown`, and the planned write carries a "not validated"
note. None of these counts as a pass.

**Where.** The renderer reads `--config`, else `AGENT_STATUSLINE_CONFIG`, else
`$XDG_CONFIG_HOME/agent-statusline/config.json`, else `~/.config/agent-statusline/config.json`.
agentctl writes the last two, which are the files the renderer finds without being told:
`$XDG_CONFIG_HOME` when it is set to an absolute path, `~/.config` otherwise, with `.` and
`..` resolved. Under `AGENTCTL_HOME`, an `XDG_CONFIG_HOME` outside that directory, compared
after resolving `..` and symlinked directories, is ignored, so a scratch run never writes
the real home. When the renderer, run in the current environment, would read another file
(`AGENT_STATUSLINE_CONFIG` is set, or `XDG_CONFIG_HOME` is empty or relative), `validate`
warns and names both paths. An empty `AGENT_STATUSLINE_CONFIG` gets its own warning,
because the renderer then fails to read any file.

**Ownership.** `apply!` backs the file up before each write and records the path it wrote
in `state.edn`; ownership is of that path. Removing `:statusline` deletes the file only if
agentctl owns it, at the recorded path. When the target moves (`XDG_CONFIG_HOME` changed),
the file at the old path is removed and the one at the new path is treated as anyone's:
replacing a file there is flagged and backed up first. A hand-made file is never deleted. A
file whose settings already match but whose layout differs is rewritten, because agentctl
owns the bytes. agentctl never writes through a symlink: a `config.json` that links into,
say, a dotfiles checkout is backed up as the content it resolves to and replaced by a
regular file, which the plan notes, and the linked file is left as it was. A symlink found
at an owned path on removal is left in place. `import` reads an existing file back into
`:statusline`, so `import! --replace` does not drop the declaration and with it the file.

The file belongs to no single CLI, so `-t` leaves it out and `-k statusline` selects it,
whatever `-t` says.
A run narrowed to projects, whether by the working directory or by `-p`, leaves it out as
well, as it does every user-wide resource; `--all` includes it. Codex does not read this
file: its status line is a native setting, and the `codex` override only feeds the
renderer's `--codex-config` preset. A host is not provisioned with the file, so
`apply --host` and `validate --host` neither run the local renderer nor report on it.
Only a malformed `:statusline` (not a map, or two spellings of one key) stops them,
as any config error does.

### awl

awl needs nothing from agentctl beyond this file. It links the renderer in process when it
is built with `-Dstatusline=<renderer source>` and then loads the same file once at startup,
so an `:awl` override is delivered by writing the file, and agentctl plans no op of its
own for awl. Whether awl uses the file is fixed when awl is built, which agentctl cannot
provision. awl has no flag that reports the build option, and `--help` reads the same with
and without it, so agentctl never runs awl to find out. Instead it reads the `awl` binary
on `PATH` for a string that only the renderer's config loader puts there. The result is a
finding under `STATUSLINE`, given whenever `:agents` has an `:awl` key:

| awl on `PATH` | Finding |
| --- | --- |
| a native binary with the renderer linked in | `ok`: it reads the file at startup |
| a native binary without it | `warn`: it keeps its own status row; rebuild with `-Dstatusline` |
| absent, or a wrapper script | `warn`: whether it reads the file is unknown |

`apply` prints these warnings on every run, including a converged one. Once the file is
written, "no changes" is true of the file, and the warning beside it says that awl may
still not use it. Restart awl after a change, because it reads the file only at startup.
`:awl` under `:statusline :agents` is a renderer agent key, not an `:acli` value, so it
plans nothing for awl's skills.

## Claude plugins

A Claude Code plugin bundles skills, subagents and hooks, and is installed
from a marketplace. `:plugins` is keyed by `[:gh "owner/repo" "plugin"]` or
`[:uri "…" "plugin"]`; the marketplace name defaults to the plugin name
(`:marketplace` overrides it):

```clojure
{:plugins {[:gh "Egonex-AI/Understand-Anything" "understand-anything"]
           {:in "projects/api" :at :repo :acli [:claude]}}}
```

`apply!` runs `claude plugin marketplace add` and `claude plugin install
<plugin>@<marketplace>` at the placement's scope, then writes
`extraKnownMarketplaces` and `enabledPlugins` into that scope's settings file.
`:in :all` is user scope (`~/.claude/settings.json`); in a project `:at :repo`
is project scope (`.claude/settings.json`, committed) and the default
`:at :machine` is local scope (`.claude/settings.local.json`). A plugin enabled
in settings but missing from Claude's own install record
(`~/.claude/plugins/installed_plugins.json`) is drift and is reinstalled. A
failed CLI step fails `apply!`: nothing is written and nothing is owned.
A `file://` or path source is added by path and declared as a `directory`
marketplace; other URLs are `git`, or `url` for a hosted `marketplace.json`.
Dropping a placement uninstalls the plugin there and removes its
`enabledPlugins` entry; the marketplace declaration stays.

Marketplace names are global to Claude: two declarations whose sources share a
marketplace name re-register it, and every project follows the last one.

## Project skills

A project's `:skills` names a whole skill-pack, a skill declared under `:skills`,
or — the common case — a skill directory found inside a declared pack, with no
`:skills` entry at all:

```clojure
:skill-packs {:shared {:uri "https://github.com/example/agent-skills"}}
:projects    {:sample {:skills [:review]}}
```

`:review` needs no `:skills {:review {:from :shared}}` entry as long as exactly
one declared pack has a `review` skill directory — that entry only earns its
keep for an override (`:mode`, `:per-tool`, a non-default `:scope`) or to break
a tie when the same skill name lives in more than one pack, which is reported
as an error rather than guessed. Naming a pack instead of a skill asks for
every skill in it — the way to follow a pack that grows.

Resolved skills are linked into `<project>/.claude/skills/`, not the user's
home: a skill a project asked for is that project's, and a link already
pointing elsewhere (a hand-made one, or a dangling relative one) is repointed
at the pack cache under `~/.agents/skill-packs`. `:scope :global` on an
explicit `:skills` declaration keeps a skill user-wide even when a project
names it.

A skill whose source sits inside the project, such as one committed in its own
`.agents/skills`, is linked relatively (`../../.agents/skills/<name>`), so the
link resolves in any checkout and can be committed. The source directory itself
is never removed, copied over, or linked onto itself.

To promote a skill already linked in several projects, set its scope explicitly:

```clojure
:skills {:wrap-up {:from :shared :scope :global :tools [:claude]}}
```

The global declaration takes precedence over both direct project references and
whole-pack references. `apply` previews a `- projects/<id> skills/wrap-up` unlink
for each redundant symlink in the configured projects; `apply!` installs the
global skill first, then removes those links. This also covers legacy symlinks
that predate agentctl's state file and projects that no longer name the skill.
A local directory is removed only when it matches the source and nothing keeps
it (see the list near the top of this guide). Project links are kept if the global skill
is unavailable (including a project-only apply before global installation).

A pack that is not cloned yet can enumerate nothing, so a dry `apply` reports
`pack not fetched yet` for it. `apply!` clones first, then re-plans and links
what the clone brought — one run, not two.

Claude Code gets its own `.claude/skills`; Codex, antigravity, kimi, Pi and
the DeepSeek Harness read the shared `<repo>/.agents/skills`, so a project's
declared skills live there. OMP reads skills only from the user's home, so for
OMP a project's declared skills are installed user-wide instead and are owned
there.

### Pi

Pi reads a project's skills from `.pi/skills` and from `.agents/skills` in the
working directory and each directory above it, up to the repository root,
once it trusts the project. Trust is the project's `:trust` for pi when the
config places one, else the nearest decision in pi's `trust.json`, else pi's
`defaultProjectTrust` setting: `"always"` trusts every project, `"never"`
none, and `"ask"` (the default) asks at startup and reads nothing until the
project is trusted. The plan says when a project's skills sit where pi asks
first or does not read them. Pi's own `~/.pi/agent/skills` and
`~/.agents/skills` are its user-wide directories. Earlier versions of
agentctl installed a project's skills for pi user-wide; `apply!` now moves
each one into the project, removing the user-wide link it made. In a project
pi does not trust, that takes the skill away from pi until the project is
trusted.

Pi keys a skill on the `name:` in its SKILL.md, keeps the first one it finds —
a trusted project's before the user's, `~/.pi/agent/skills` before
`~/.agents/skills` — and warns at startup about every later one. Two paths to
the same SKILL.md are one skill to it, but an identical copy is a second one.
So for pi agentctl:

- removes a project copy, in `.agents/skills` or `.pi/skills`, that is
  identical to a skill installed user-wide for pi, as it does for the other
  agents, and only in a project pi trusts (and keeps it in the cases listed
  above — a copy codex also reads stays until codex has the skill user-wide);
- reports a project skill that differs from a user-wide one of the same name:
  pi loads the project's one there and warns of the collision. Rename one, or
  leave `:pi` out of the user-wide skill to keep the project's quietly;
- reports a skill in `~/.agents/skills` that has the name of a user-wide one;
- says when a project's skills are placed where pi asks before reading them,
  or does not read them at all. Removals and collisions are left alone there.

These reports are warnings: `apply` shows them while it has changes to make,
and `validate` repeats them under SKILLS once the plan has converged.

## Remote hosts

A host is declared under `:hosts` with its own placements, and is only ever
touched by a run that names it with `--host`. The far side needs OpenSSH and
PowerShell (Windows) or `sh`, `base64` and `sha256sum`/`shasum` (Linux, macOS):
no agentctl, Python or git.

```clojure
{:hosts {:win {:ssh "windows.example.com" :os :windows
               :skill-packs {[:uri "file://$HOME/packs/shared"] {:acli [:codex]}}}}}
```

```text
agentctl apply  --host win -t codex -k skills    # read-only; exit 0 clean, 2 drift
agentctl apply! --host win                       # copy, back up, record ownership
agentctl validate --host win                     # unreachable is reported as unknown
agentctl state  --host win
```

`:os` picks the interpreter, and with it the home. On a Windows machine whose OpenSSH
login shell is WSL, one destination reaches two separate codex installs: `:os :linux`
provisions WSL's `~/.codex` and `:os :windows` the Windows `%USERPROFILE%\.codex`.
Declare one host for each side you want provisioned; neither sees the other's skills.

Host sections take the keyed `:skills` and `:skill-packs` forms, and a pack there
defaults to `:in :all`. A source already placed at the top level can be placed
again on a host, for different tools. Only codex's user-wide skills are provisioned
so far: other tools and kinds are reported in the plan and by `validate`, never
dropped silently. `:home` overrides the remote home, for a scratch tree.

Skills are copied, not linked: sources are read on this machine (a git pack is
fetched here) and sent over SSH. Drift is a per-file comparison of byte hashes,
ignoring `.git`, `__pycache__` and `.DS_Store`; a file mode change alone is not
drift. An identical copy already on the host is adopted without a write. What a run
replaces or prunes moves to `.config/agentctl/backups/<stamp>` in the remote home,
and ownership is kept per host in `~/.config/agentctl/hosts/<name>.edn`, apart from
local state. An unreachable host exits 1 from `apply` and `apply!`.

## Reading a plan

```
AGENTCTL
+ skill-packs/shared
  !gh repo clone example/agent-skills ~/.agents/skill-packs/shared

CLAUDE
~ projects/sample mcps/{docs search}
    edit `~/.claude.json`:
      ~ search.command: "/usr/local/bin/search-mcp" -> "search-mcp"
= settings/{model output-style}
+ projects/sample skills/{review}
  !ln -s ~/.agents/skill-packs/shared/review ~/projects/sample/.claude/skills/review
```

`+` create, `~` change, `-` remove, `=` unchanged or informational; `⚠` on a
line of its own is a config warning. A line starting `!` is a shell command the
run will execute, printed exactly as it will run — everything else is a file
edit.

A line reads `<sigil> [projects/<id>] <kind>/<ids>`: the section heading already
names the tool, and `projects/<id>` says the scope when the resource belongs to
one project. Ops writing one file for one project collapse into a single block,
their ids in braces (`mcps/{docs search}`); a shared prefix is factored out
(`permissions.{allow ask}`). A `=` block is a check that already passes — the
setting is declared, it was read, and the file agrees.

A plan with nothing to change prints `no changes — environment matches
agents.edn`, unless it carries a warning: a `=` block whose indented line says
why a declared setting went nowhere, such as an unsupported setting. That plan
is printed in full — by `apply`, `apply!`, `--host` and the GUI alike, and under
`--json` as ops with `"warn": true` — and still exits 0, since nothing would
change.

Under `--json`, `apply`, `apply!` and `validate` print one JSON document on
stdout: the plan's `ops`, the selector `warnings`, the config's own warnings
and this machine's (the status line's) in `findings` (`level`, `where`,
`message`, as `validate --json` prints them), the number of changes that write
a resolved secret in `secrets`, and the `summary`. `apply!` adds `applied` —
`done` and `failed`, each op with its `tool`, `kind`, `id` and `action`, a
failed one with its `error` — and `backups`. A confirmation prompt cannot share
stdout with that document, so `apply! --json` needs `-y`. An error is
`{"error": …}` with exit 1 instead — a config with errors carries its
`findings` too; an unknown command or option, or a config file missing,
unreadable or not a file, only the message. Without `--json` the same errors
are one `error: …` line.

A skill whose source is not on disk, such as a `:per-tool :path` into a
project copy that has since gone, is not linked: the plan says `source
missing: <path> — link not changed` and leaves whatever link is there, so it
does not plan the same change on every run. `validate` repeats it under SKILLS.

`AGENTCTL_HOME` repoints every path (and the `codex`/`claude`/`llm` CLIs it
drives) at a scratch tree — that is how the end-to-end tests provision a
throwaway home.
