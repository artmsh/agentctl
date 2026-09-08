# agentctl

`agentctl` declaratively provisions coding-agent configuration across Claude
Code, Codex, Pi, OpenCode-compatible OMP installations, Google's Antigravity
CLI (`agy`), and Simon Willison's `llm` CLI.

One EDN file describes settings, MCP servers, skills, shared memory, model
providers, and project-specific configuration. `agentctl` compares that desired
state with the files on disk, prints a plan, and applies only the changes it
owns.

## Safety model

- `apply` is read-only and exits with status 2 when drift exists.
- `apply!` backs up every file before its first write in a run.
- `apply`/`apply!` narrow themselves to the project you are standing in, and say
  so on their first line — see [Where you run it is a scope](#where-you-run-it-is-a-scope).
- Resources not created by `agentctl` are preserved, except redundant project
  skill symlinks explicitly superseded by a global skill declaration.
- Secrets are references such as `$ENV_VAR` or `!bw://folder/item/field`, not
  plaintext values.
- Imports redact credential-shaped values instead of printing them.
- The GUI plans against the buffer but writes nothing until Apply is confirmed.
- Unavailable vaults and endpoints are reported as unknown, not as successful.

## Requirements

- [Babashka](https://babashka.org/) (`bb`)
- Any agent CLIs you choose to manage
- Optional: Bitwarden CLI (`bw`) for `!bw://` references

## Quick start

```sh
git clone https://github.com/artmsh/agentctl.git
cd agentctl
chmod +x agentctl
./agentctl --help
```

Create `~/.config/agents.edn`:

```clojure
{:executors
 {:claude {:model "your-model"}
  :codex  {:model "your-model"}}

 :mcps
 {:search {:command "/usr/local/bin/search-mcp"
           :args ["--stdio"]
           :tools [:claude :codex]}}

 :skill-packs
 {:shared {:uri "https://github.com/example/agent-skills"
           :ref "main"
           :dir "skills"}}

 :skills
 {:review {:from :shared :tools [:claude :codex]}}

 :extra-providers
 {:gateway {:url "https://api.example.com/v1"
            :key $EXAMPLE_API_KEY
            :models ["example-model"]
            :tools [:codex]}}

 :projects
 {:sample {:path "~/projects/sample"
           :trusted true
           :executors #{:claude :codex}
           :mcp [:search]
           :skills [:review]}}}
```

Preview and apply:

```sh
./agentctl validate
./agentctl apply
./agentctl apply!
```

`apply!` asks for confirmation unless `--yes` is supplied.

## Commands

```text
agentctl apply       Preview changes; exit 2 when drift exists
agentctl apply!      Apply the plan
agentctl validate    Check configuration and environment
agentctl import      Print configuration inferred from the environment
agentctl import!     Merge inferred configuration into the config file
agentctl state       Print resources owned by agentctl
agentctl gui         Edit the config in a browser beside its live dry run
```

Use `--tool`, `--kind`, or `--project` to narrow an operation. Set
`AGENTCTL_HOME` to point all managed paths at a scratch home for testing.

### Where you run it is a scope

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
`show noop` toggles are `--tool`, `-v` and `--show-noop`.

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
| `:project` | `<project>/.mcp.json`, merged onto what is there, with `enabledMcpjsonServers` pre-approving it | a server the whole team should get from the repo |
| `:global` (default otherwise) | `~/.claude.json`, user-wide | a server wanted everywhere |

Codex, Pi and OMP have no project-level MCP config; they report the skip as a
warning rather than silently installing the server user-wide.

`.mcp.json` is normally committed, so a `:scope :project` server whose `:env`
carries a credential — a `!bw://` reference that resolves at apply time, or a
token written out literally — puts that credential in the repo. The plan marks
it `⚠ secret value written into the project`, and `validate` scans every
project's `.mcp.json`.

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

## Hooks

Claude Code hooks, global scope, declared under `:executors :claude :hooks`:

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

To promote a skill already linked in several projects, set its scope explicitly:

```clojure
:skills {:wrap-up {:from :shared :scope :global :tools [:claude]}}
```

The global declaration takes precedence over both direct project references and
whole-pack references. `apply` previews a `- projects/<id> skills/wrap-up` unlink
for each redundant symlink in the configured projects; `apply!` installs the
global skill first, then removes those links. This also covers legacy symlinks
that predate agentctl's state file and projects that no longer name the skill.
Local directories are preserved. Project links are kept if the global skill
is unavailable (including a project-only apply before global installation).

A pack that is not cloned yet can enumerate nothing, so a dry `apply` reports
`pack not fetched yet` for it. `apply!` clones first, then re-plans and links
what the clone brought — one run, not two.

Only Claude Code has a project-level skills directory. For Codex, Pi and OMP a
project's declared skills are installed user-wide instead — that is the only
place those tools read skills from — and are owned there.

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

`AGENTCTL_HOME` repoints every path (and the `codex`/`claude`/`llm` CLIs it
drives) at a scratch tree — that is how the end-to-end tests provision a
throwaway home.

## Development

```sh
bb test
```

The test suite uses temporary directories and local fixture repositories. It
does not modify your real agent configuration.

## Security

See [SECURITY.md](SECURITY.md). Before publishing a configuration, keep secret
values in environment variables or Bitwarden references and review the output
of `agentctl import`.

## License

Licensed under the [Apache License 2.0](LICENSE).
