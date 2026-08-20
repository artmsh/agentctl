# agentctl

`agentctl` declaratively provisions coding-agent configuration across Claude
Code, Codex, Pi, OpenCode-compatible OMP installations, and Simon Willison's
`llm` CLI.

One EDN file describes settings, MCP servers, skills, shared memory, model
providers, and project-specific configuration. `agentctl` compares that desired
state with the files on disk, prints a plan, and applies only the changes it
owns.

## Safety model

- `apply` is read-only and exits with status 2 when drift exists.
- `apply!` backs up every file before its first write in a run.
- Resources not created by `agentctl` are never deleted.
- Secrets are references such as `$ENV_VAR` or `!bw://folder/item/field`, not
  plaintext values.
- Imports redact credential-shaped values instead of printing them.
- Unavailable vaults and endpoints are reported as unknown, not as successful.

## Requirements

- [Babashka](https://babashka.org/) (`bb`)
- Any agent CLIs you choose to manage
- Optional: Bitwarden CLI (`bw`) for `!bw://` references

## Quick start

```sh
git clone https://github.com/YOUR-ORG/agentctl.git
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
```

Use `--tool`, `--kind`, or `--project` to narrow an operation. Set
`AGENTCTL_HOME` to point all managed paths at a scratch home for testing.

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

## Project skills

A project's `:skills` names declared skills, whole skill-packs, or both:

```clojure
:skills   {:review {:from :shared}}
:projects {:sample {:skills [:shared :review]}}
```

Naming a pack asks for every skill in it — the way to follow a pack that grows.
They are linked into `<project>/.claude/skills/`, not the user's home: a skill a
project asked for is that project's, and a link already pointing elsewhere (a
hand-made one, or a dangling relative one) is repointed at the pack cache under
`~/.agents/skill-packs`. `:scope :global` on the declaration keeps a skill
user-wide even when a project names it.

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
