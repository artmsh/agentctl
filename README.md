# agentctl

`agentctl` declaratively provisions coding-agent configuration across Claude
Code, Codex, Pi, and OpenCode-compatible OMP installations.

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
