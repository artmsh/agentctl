# art/agentctl — fg.jo issues

Generated 2026-11-01 · <https://fg.jo/art/agentctl> · mirror of `github.com/artmsh/agentctl`

**18 open**, 4 closed, 22 total.** Pull requests are disabled on this mirror (`has_pull_requests: false`), and GitHub reports 0 open issues for `artmsh/agentctl` — fg.jo is the only issue tracker.

## By label

| Label | Count |
| --- | ---: |
| `kind/feature` | 7 |
| `area/claude` | 7 |
| `size/S` | 6 |
| `kind/bug` | 6 |
| `size/XS` | 4 |
| `area/codex` | 4 |
| `size/M` | 3 |
| `status/blocked` | 2 |
| `area/import` | 2 |
| `priority/high` | 1 |
| `kind/docs` | 1 |
| `kind/infra` | 1 |
| `size/L` | 1 |

## Open issues

### #2 — fix: deleting a project from agents.edn leaks its project-scoped MCP entries

https://fg.jo/art/agentctl/issues/2  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `area/claude` `kind/bug` `size/S` · assignees: —

> Audited 2026-09-20.

**Context.** At HEAD `0382de3` a project deleted outright from `agents.edn` leaves its project-scoped MCP entries behind in `~/.claude.json` under `projects.<path>.mcpServers`, forever. `HEAD:src/agentctl/core.clj:169` records a project MCP as a bare key with no data, so the manifest entry is just `{:at "..."}`; `project-mcp-prune-ops` (`HEAD:src/agentctl/adapters/claude.clj:317`) can therefore only find the project through `(get-in cfg [:projects ...])` and drops the row on `:when proj` at `:336`. The code comment there states the defect outright.

Reproduced end to end at HEAD in a sandbox `AGENTCTL_HOME`, with one project `:sample` declaring `:mcp [:search]`:

| scenario | plan |
|---|---|
| `:mcp [:search]` dropped, project kept (the e2e 13 path) | `- projects/sample mcps/search` ... `1 to remove` |
| whole `:sample` stanza deleted | `+ mcps/search` ... `0 to remove`; the entry survives in `~/.claude.json` |

**The fix already exists, uncommitted.** The same two runs against the working tree give the opposite result. The tree records the path (`src/agentctl/core.clj:390` writes `{:entity-id ... :project-id ... :project-path (:path proj) :scope ...}`, which shows up in the sandbox manifest as `:project-path` / `:resolved-path` under `:version 2`), and `src/agentctl/adapters/claude.clj:340-341` falls back to it with `(when (:project-path old) {:path (:project-path old)})`. Deleting the project then plans `- projects/sample mcps/search` ... `1 to remove`, and a hand-made sibling server added to the same project path survives the `apply!` untouched. That work is part of the uncommitted scope-DSL slice tracked by #1, so the defect is live for anyone on `main`.

**Target.** Land the recorded-path prune with #1, and pin it with a regression step so it cannot be lost again.

**Done when.**
- #1 is committed, carrying the `core.clj` `:project-path` record and the `claude.clj` prune fallback.
- `tests/test-agentctl.sh` gains a step that deletes a whole project stanza and asserts the owned entry is pruned from `~/.claude.json` `projects.<path>.mcpServers` **and** from the project's `.mcp.json`, while a hand-made sibling survives (the e2e 14 guarantee).
- Both `:scope :local` and `:scope :project` are covered: the working-tree prune branches on the recorded `:scope`.

**Open questions.**
- The manifest gained `:version 2` with `:placements`, and nothing migrates a `:version 1` file. On the first v2 `apply`, are pre-existing project MCPs adopted or orphaned?
- A `:scope` flip is handled by the new branch. Is a flip on a project that is *also* gone from `agents.edn` (no `cfg` entry, `:scope` known only from the manifest) covered by the same step?

<details><summary>Original description</summary>

When a project is deleted from `agents.edn` outright, its project-scoped MCP entries in `~/.claude.json` `projects.<path>.mcpServers` are never pruned: the ownership manifest stores the namespaced id (`:sample/search`), never the project path, so `project-mcp-prune-ops` has nothing to point a delete at. Dropping a single server from a project that still exists is handled (e2e 13).

**Done when:** the manifest records the path a project-scoped entry was written to, a removed project's owned entries are planned for removal, hand-made entries still survive (as in e2e 14), and an e2e step covers project deletion.

Wiki: [Ownership and pruning](https://fg.jo/art/agentctl/wiki/ownership-and-pruning)

</details>

### #3 — fix: import drops Codex http_headers

https://fg.jo/art/agentctl/issues/3  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `area/codex` `area/import` `kind/bug` `size/S` · assignees: —

> Audited 2026-09-20.

**Context.** Still actual at `0382de3` and in the working tree. Reproduced end to end against a sandbox `CODEX_HOME` declaring:

```toml
[mcp_servers.docs]
url = "https://example.com/mcp"
bearer_token_env_var = "DOCS_TOKEN"

[mcp_servers.docs.http_headers]
X-Api-Key = "<secret>"
```

`codex mcp list --json` (Codex CLI 0.155.1) returns it as `{"transport": {"type": "streamable_http", "url": ..., "bearer_token_env_var": "DOCS_TOKEN", "http_headers": {"X-Api-Key": ...}, "env_http_headers": null, "http_headers_helper": null}}`. `agentctl import` against that home emits exactly:

```clojure
:mcps
{:docs {:url "https://example.com/mcp", :tools [:codex]}}
```

The headers and the bearer-token env var are gone, with no note printed.

**Why, precisely** — the original "nothing reads the key" is true but too weak, and the `:extra` round-trip does not save it. `normalize-entry` (`HEAD:src/agentctl/imports.clj:109`) computes `extra` from the **top-level** entry, and every top-level key Codex emits (`:name :enabled :disabled_reason :transport :startup_timeout_sec :tool_timeout_sec :auth_status`) is already in `modelled-mcp-keys` (`:103`). The transport map is then read field by field (`:command :args :url :env :headers :cwd`), so anything else nested inside `:transport` is dropped rather than round-tripped. Four keys are lost this way: `http_headers`, `bearer_token_env_var`, `env_http_headers`, `http_headers_helper`. Lossy, not leaky - confirmed.

**Second, independent drop path.** `codex/mcp-list`'s config.toml fallback (`HEAD:src/agentctl/adapters/codex.clj:53-58`, unchanged in the working tree) rebuilds `:transport` as `{:command :args :url}` only, discarding headers before `imports.clj` ever sees them. That path runs whenever the Codex CLI refuses to start. Fixing `imports.clj` alone leaves it lossy.

**Target.** A Codex HTTP server survives `import` with its credential modelled and redacted like every other header source.

**Done when.**
- `http_headers` is unwrapped to `:headers` and passed through `redact-map`, as `imports.clj:120` already does for `:headers`.
- `bearer_token_env_var` is either modelled or explicitly declared a non-goal in the code comment: silently dropping a credential pointer is the same class of bug.
- The config.toml fallback in `codex/mcp-list` carries the same fields, or the gap is named as an explicit non-goal in its docstring.
- A round-trip test covers a Codex HTTP server with headers, beside the existing "credential lives in a header" fixture in `tests/test-agentctl.sh`.

**Open questions.**
- Should `env_http_headers` / `http_headers_helper` round-trip verbatim through `:extra` rather than being modelled? They are Codex-specific indirections the DSL has no shape for.
- Does `apply` write headers back to Codex at all? If the write path cannot emit `http_headers`, `import` then `apply` is not a fixpoint for these servers, which feeds #5.

<details><summary>Original description</summary>

`import` models Codex's `:transport` but cherry-picks fields from it, and nothing in `src/` reads Codex's `http_headers` key (verified by grep, 2026-09-19). A Codex HTTP server's headers — typically its whole credential — are therefore silently dropped on import. Lossy, not leaky.

**Done when:** Codex `http_headers` are imported as `:headers` and go through `redact-map` like the other header sources, with a round-trip test.

Wiki: [Secrets, validation and import](https://fg.jo/art/agentctl/wiki/secrets-validation-and-import)

</details>

### #4 — feat: report converged hooks in the plan

https://fg.jo/art/agentctl/issues/4  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `area/claude` `kind/feature` `size/S` · assignees: —

> Audited 2026-09-20.

**Context.** Still actual at `0382de3`. `plan/json-array-merge-op` accepts `:report-converged?` (`HEAD:src/agentctl/plan.clj:512`) and emits a `:noop` op when the value is already present (`:516-517`). `settings-ops` passes it (`HEAD:src/agentctl/adapters/claude.clj:70`); `hook-ops` does not - its `json-array-merge-op` call at `HEAD:src/agentctl/adapters/claude.clj:127-131` passes `:tool :kind :id :file :path :old :value :summary` and nothing else.

Reproduced at HEAD in a sandbox `AGENTCTL_HOME` with one converged hook (`:guard`), one converged settings pair, and one new hook (`:guard2`):

```
CLAUDE
+ hooks/guard2
= settings/{model output-style}

1 to add, 0 to change, 0 to remove, 2 unchanged
```

`guard` is converged and absent from both the listing and the arithmetic, while converged settings render their `=` line.

**A second gap the original text did not name.** With *everything* converged, the same command prints no plan at all:

```
no changes — environment matches agents.edn

0 to add, 0 to change, 0 to remove, 1 unchanged
```

`HEAD:src/agentctl/main.clj:132-134` calls `plan/render-plan` only when `(seq changes)`, so `--show-noop` is inert in the fully-converged case - which is exactly the case this issue exists to serve ("confirm that a hooks change landed"). Passing `:report-converged?` in `hook-ops` fixes the mixed plan; it does not fix that one. Same short-circuit in the working tree (`main.clj:136-138`).

**Target.** After an `apply!`, a plain `apply -v --show-noop` shows every declared hook as `= hooks/<id>`, so a hooks change can be confirmed without diffing config files by hand.

**Done when.**
- `hook-ops` passes `:report-converged? true`, and a converged hook renders `= hooks/{...}` under `--show-noop` in a mixed plan (the repro above should read `3 unchanged`).
- The fully-converged case is decided (see below) and the behaviour matches the decision.
- A test asserts the `= hooks/...` line, beside the existing settings-op assertion in `tests/agentctl_test.clj`.

**Open questions.**
- Should `--show-noop` render the plan when there are zero changes? The "no changes — environment matches agents.edn" short-circuit reads deliberate, so this is a UX call, not an oversight. Without it the issue's stated purpose is unmet; with it the flag's meaning changes for every command.
- `project-hook-ops` exists only in the uncommitted tree and is gated on `:entity-dsl?`. Whatever is decided here has to apply to project hooks too once #1 lands.

**Size.** Raised `XS -> S`. The one-keyword change is XS; the open question above is a design call plus a second file (`main.clj`) and its own test. Drop back to `size/XS` if the short-circuit is ruled out of scope.

<details><summary>Original description</summary>

`plan/json-array-merge-op` accepts `:report-converged?`, but the Claude hook ops do not pass it (only settings keys do, `adapters/claude.clj`). An unchanged hook therefore emits no op and no `=` line even under `-v --show-noop`, so `apply` cannot confirm that a hooks change landed; today this has to be checked by counting `claude/hook-ops` against the previous config.

**Done when:** converged hooks render as `= hooks/{…}` under `--show-noop`, with a test.

Wiki: [Hooks](https://fg.jo/art/agentctl/wiki/hooks)

</details>

### #5 — verify: full-config apply! on the real home, and import → apply as a fixpoint

https://fg.jo/art/agentctl/issues/5  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `area/import` `kind/infra` `size/L` `status/blocked` · assignees: —

> Audited 2026-09-20.

**Context.** Still actual, and blocked. Two premises in the original text are wrong as of 2026-09-20.

**Correction 1 - real-home `apply!` has run more than once.** Every backup directory under `~/.config/agentctl/backups/` that holds a real-home file (88 directories in total; all the others hold only test-sandbox paths):

| run | files written |
|---|---|
| `20260820-174135` | `~/.omp/agent/models.yml`, `~/.pi/agent/models.json`, the `llm` `extra-openai-models.yaml` and `keys.json` |
| `20260830-130110` | `~/.claude.json`, `~/.codex/config.toml` |
| `20260830-143338` | `~/.claude/settings.json` |
| `20260830-143450` | `~/.claude/settings.json` |

So providers (2026-08-20) **and** MCP plus settings (2026-08-30) have converged live. What has never happened is one `apply!` over the whole config across all six tools; no `memory`, `skills`, `trust` or antigravity file has ever been written on the real home.

**Correction 2 - the live `agents.edn` is not the full config, and HEAD cannot read it.** `~/.config/agents.edn` is now a 12-line entity-DSL file declaring only `:skill-packs` and `:skills`. The full config is `~/.config/agents.edn.old` (2026-09-07), still in the map form. `agentctl validate` from `0382de3` against the live file reports `2 ok · 4 warning(s) · 0 unknown · 10 error(s)` - every `:skill-packs` and `:skills` entry fails to parse - and its `DRIFT ✓ environment matches agents.edn` is an artefact of a config that parsed to nothing, not evidence of convergence. The same `validate` from the working tree reports `4 ok · 8 warning(s) · 0 error(s)` and `6 pending change(s)`. **This issue cannot start until #1 ships**: the scope DSL is the only thing that can read the user's own config.

**Target.** One `apply!` converges the whole declared config on the real home, and a following `import` then `apply` plans nothing.

**Done when.**
- The full config (the content of `agents.edn.old`) is ported onto the entity DSL and is the live `~/.config/agents.edn`.
- One `apply!` converges it, with real-home backups under `~/.config/agentctl/backups/<stamp>/` covering each tool's file.
- A following `import` then `apply` plans nothing.

**Open questions.**
- Import is semantically but not textually stable with respect to sugar forms. Is the fixpoint asserted on the parsed config or on the file text? Only the first is reachable without a canonical printer.
- `-t` / `-k` filter by target and resource kind, not by entity id, so there is no way to converge one entity of a shared tool on the real home without converging its siblings. Is a narrower filter in scope here, or its own issue?
- Codex HTTP servers lose their headers on import (#3), so `import` then `apply` cannot be a fixpoint for them until that lands.
- The live manifest carries two ids that no `agents.edn` on this machine declares (a `:pi` provider and a `:pi` project, both stamped `20260908-094733`), and all 88 backup directories were produced by test runs. Those have to be cleaned or explained before a real-home convergence can be read as meaningful. Filed separately - the unit suite writes into the real home when it is not started through `tests/run.sh`.

**Blocked by** #1: the live config does not parse at HEAD.

<details><summary>Original description</summary>

Real-home `apply!` has only been verified for a one-provider manifest (2026-08-20). Full-config convergence against the real home is intentionally untested, and `import → apply` is not yet a fixpoint, because the machine still lacks resources the full config declares. Import is also semantically but never textually stable with respect to sugar forms.

**Done when:** a full-config `apply!` has converged on the real home (backups under `~/.config/agentctl/backups/`), and a following `import` then `apply` plans nothing.

Wiki: [Testing and live apply!](https://fg.jo/art/agentctl/wiki/testing-and-live-apply)

</details>

### #6 — verify: pi / omp load ~/.{pi,omp}/agent/AGENTS.md as global memory

https://fg.jo/art/agentctl/issues/6  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `kind/docs` `size/XS` · assignees: —

> Audited 2026-09-20.

**Context.** Still actual, but the targets are now confirmed from primary sources, so what remains is a documentation edit rather than an investigation.

- **omp - verified from the shipped loader.** `omp 17.3.5` registers a context-file provider described as "Load AGENTS.md from .omp/ directories" whose loader does `const userPath = path.join(getAgentDir(), "AGENTS.md")` for the `user` level. `getAgentDir()` returns `dirs.agentDir`, the same directory that holds the `config.yml` and `mcp.json` the omp adapter already writes, i.e. `~/.omp/agent`. `HEAD:src/agentctl/adapters/omp.clj:16` is correct.
- **pi - verified from the vendor docs shipped with the installed package.** `pi 0.84.2`, `docs/usage.md` lines 100-102: "Pi loads `AGENTS.md` or `CLAUDE.md` at startup from: ... `~/.pi/agent/AGENTS.md` for global instructions". `HEAD:src/agentctl/adapters/pi.clj:17` is correct.

Neither is the sentinel run the original "done when" asks for - that needs a live agent turn - but both are considerably stronger than "documented convention". Neither file exists on this machine today, so nothing has yet been observed from a running agent. `HEAD:docs/AGENTCTL.md:58` still carries the footnote.

**Target.** The docs entity table stops hedging the pi and omp memory paths, and cites what confirmed them.

**Done when.** `docs/AGENTCTL.md` drops the `¹ Unverified ...` footnote (lines 58-60) and the two `&nbsp;¹` markers, citing pi's `docs/usage.md` and omp's context-file provider - or, if a sentinel run contradicts them, the adapter constants are corrected instead.

**Open questions.**
- omp's `getAgentDir()` is `dirs.agentDir`, which omp's profile machinery can relocate (it carries profile env keys and a pre-profile agent-dir override), while `HEAD:src/agentctl/adapters/omp.clj:11` hardcodes `~/.omp/agent`. Is a non-default profile in scope?
- pi honours `PI_CODING_AGENT_DIR` to move its whole config directory (`docs/environment-variables.md`), while `HEAD:src/agentctl/adapters/pi.clj:11` hardcodes `(str u/home "/.pi/agent")`. Same question, and probably its own issue.

This is the only open issue in the repo that depends on neither #1 nor an upstream release.

<details><summary>Original description</summary>

agentctl targets `~/.pi/agent/AGENTS.md` and `~/.omp/agent/AGENTS.md` as the global memory files for pi and omp. That is convention, not verified against a running agent, and the docs table marks it as such.

**Done when:** each agent is shown to load the file (e.g. with a sentinel instruction), and the docs drop the "unverified" note, or the targets are corrected.

Wiki: [External ground truth](https://fg.jo/art/agentctl/wiki/external-ground-truth)

</details>

### #7 — feat: alt-t opens the Codex model picker (blocked on Codex keymap support)

https://fg.jo/art/agentctl/issues/7  
created 2026-09-19 · updated 2026-09-21 · 0 comment(s)
labels: `area/codex` `kind/feature` `size/XS` `status/blocked` · assignees: —

> Audited 2026-09-20.

**Context.** Still blocked, and the blocking version has moved: the installed CLI is `codex-cli 0.155.1`, not the 0.153.4 named below. Re-probed by pointing `CODEX_HOME` at a sandbox that declares the binding and running `codex mcp list --json`:

```
[tui.keymap.global]
open_model = "alt-t"
```
```
Error: failed to load bootstrap configuration

Caused by:
    unknown field `open_model`, expected one of `open_agents`, `open_transcript`,
    `open_external_editor`, `copy`, `clear_terminal`, `submit`, `queue`,
    `toggle_shortcuts`, `toggle_vim_mode`, `toggle_fast_mode`, `toggle_raw_output`,
    `toggle_side_conversation`
    in `tui.keymap.global`
```

The error enumerates the accepted set, which turns this into a cheap exact test rather than a bare failure. Probing every context the same way - one deliberately bogus field per table - gives the whole 0.155.1 keymap surface:

| context | actions |
|---|---|
| `global` | `open_agents open_transcript open_external_editor copy clear_terminal submit queue toggle_shortcuts toggle_vim_mode toggle_fast_mode toggle_raw_output toggle_side_conversation` |
| `chat` | `toggle_voice_mute interrupt_turn decrease_reasoning_effort increase_reasoning_effort previous_permission_mode next_permission_mode edit_queued_message prompt_stack_back skip_question` |
| `composer` | `submit queue toggle_shortcuts history_search_previous history_search_next` |
| `editor` | cursor motions and kill-ring only |
| `list` | `move_up move_down move_left move_right page_up page_down jump_top jump_bottom accept cancel` |
| `agents` | `resume search new_task rename stop archive delete hide toggle_grouping` |
| `approval` | `open_fullscreen open_thread approve approve_for_session approve_for_prefix deny decline cancel` |
| `pager` | `scroll_up scroll_down page_up page_down half_page_up half_page_down jump_top jump_bottom close close_transcript` |
| `vim_normal` `vim_operator` `vim_search` `vim_text_object` | vim editing |

(The full context list is `global chat composer editor vim_normal vim_operator vim_search vim_text_object pager list agents approval`.) No context exposes a model-picker action; `chat` offers only `decrease_reasoning_effort` / `increase_reasoning_effort`. `/model` remains the only way in.

Nothing is missing on the agentctl side: `keymap-ops` shipped in `451c091` and lives at `HEAD:src/agentctl/adapters/codex.clj:64`, validating only the value shape and leaving action names to Codex. This issue waits on upstream, not on code.

**Target.** `alt-t` opens the Codex model picker, declared through the codex `:keymap` in `agents.edn`.

**Done when.** A Codex release exposes a keymap action that opens the model picker, and `~/.config/agents.edn` binds `alt-t` to it under the codex `:keymap`.

**How to re-probe (about ten seconds, no agentctl involved).**

```sh
CX=$(mktemp -d)
printf '[tui.keymap.global]\nzzz = "alt-t"\n' > "$CX/config.toml"
CODEX_HOME=$CX codex mcp list --json
```

Read the `expected one of ...` list in the error, then repeat with `[tui.keymap.chat]`. If a picker action appears, this issue is unblocked; otherwise update the version recorded here and move on.

**Blocked by** upstream `openai/codex`. No agentctl dependency.

<details><summary>Original description</summary>

Preference (2026-09-05): `alt-t` should open the Codex model selector. Codex CLI 0.153.4 rejects `tui.keymap.global.open_model`, and `/model` is the only way to open the picker today, so the codex `:keymap` entry cannot be declared yet.

**Done when:** after a Codex upgrade the supported keymap actions include one that opens the model picker, and `~/.config/agents.edn` declares `alt-t` for it through the codex `:keymap`.

Wiki: [External ground truth](https://fg.jo/art/agentctl/wiki/external-ground-truth)

</details>

### #8 — Open-issue audit 2026-09-20 — findings and burn-down plan

https://fg.jo/art/agentctl/issues/8  
created 2026-09-21 · updated 2026-09-21 · 0 comment(s)

Audited **2026-09-20** against `main` `0382de3` (2026-09-09) and, where stated, against the dirty working tree of the same date. First audit pass on this repo — no issue carried a prior `> Audited` marker.

**7 open, 7 still actual, 0 closed, 0 filed.** Every body was rewritten with a dated verification block naming the commit it was checked against; the original text is preserved verbatim in a `<details>` block on each.

**Method.** Every issue with a reproducible defect was reproduced, not inferred: #2, #3, #4 and #7 were run end-to-end under a sandbox `AGENTCTL_HOME` / `CODEX_HOME` against **both** trees, #6 was settled from the vendor sources shipped with the installed `pi` and `omp`, #5 from `validate` output, the backup directory and the live manifest. Two standing cautions, both re-confirmed this pass:

- `tests/run.sh` is the **only** valid runner (`core/build-plan` silently skips any tool whose CLI is off `PATH`, so a bare `bb -cp src …` one-liner yields a false green), and it **is** sandboxed — it exports `AGENTCTL_HOME=$(mktemp -d)` before `bb` loads `util.clj`. A full green run on 2026-09-20 (124 tests, 599 assertions, 0 failures, plus 20 e2e checks) left `~/.config/agentctl/backups/` at 88 entries before and 88 after, and `git status --porcelain` byte-identical. Running the suite during an audit is safe; the earlier "never run it, it writes into the real home" rule was wrong.
- `apply!` against the real home is still off-limits. `-t` / `-k` filter by target and resource kind, **not** by provider or entity id, so a "one provider" apply converges every sibling sharing those tools.

---

## Tracker changes

| # | change |
|---|---|
| #1 | Body rewritten. Size **S → M** — the code is now *proven* green, so the M is entirely the docs reconciliation (`docs/AGENTCTL.md` has 12 mentions of `:tools` and 0 of `:in` against a 348-line `docs/SCOPE-DSL.md`), not risk. Dependencies narrowed to **#2, #5**; #3/#4 moved from dependencies to sequencing. Corrected: the three untracked files are **not** gitignored, so plain `git add` works — no `-f`. |
| #2 | Body rewritten. Size **M → S**. The fix already exists in the uncommitted tree; what is left is the e2e guard plus riding #1, not an implementation. Records the `:version 1` → `:version 2` manifest change that makes the prune possible. |
| #3 | Body rewritten. Size `size/S` confirmed. Mechanism corrected (see below) and a **second** drop path recorded. Dependency on #1 dropped — the defect is identical at HEAD and in the working tree. |
| #4 | Body rewritten. Size **XS → S**: the one-keyword edit is XS, but the case the issue's own motivation describes needs a design call plus `main.clj` plus a test. Dependency on #1 dropped. |
| #5 | Body rewritten. `status/blocked` **added** — it is blocked on #1 and cannot even be planned today. Size `size/L` held. Two body premises corrected with primary evidence. |
| #6 | Body rewritten. Size `size/XS` confirmed. Both targets now confirmed from vendor sources, so the remaining work is deleting the hedge in `docs/AGENTCTL.md`; the live sentinel run the original "done when" asks for is still outstanding and the title stays `verify:`. |
| #7 | Body rewritten. Size `size/XS` and `status/blocked` both confirmed. Blocking version refreshed **0.153.4 → 0.155.1** and the full accepted-action surface recorded so the next check is ten seconds. |

---

## Corrections that change what a fixer should do

| # | correction |
|---|---|
| **#3** | The body's *"nothing in `src/` reads `http_headers`"* names the wrong mechanism. `extra` is computed from the **top-level** entry (`HEAD:src/agentctl/imports.clj:111`) and every top-level key Codex emits is already in `modelled-mcp-keys` (`:103`) — so the nested `:transport` keys are dropped rather than round-tripped into `:extra`. **Four** keys are lost, not one: `http_headers`, `bearer_token_env_var`, `env_http_headers`, `http_headers_helper`. The fix is a read off the unwrapped transport, not a new reader. |
| **#3** | Second drop path the body does not mention: `HEAD:src/agentctl/adapters/codex.clj:53-58` — the `config.toml` fallback in `mcp-list` rebuilds `:transport` as `{:command :args :url}` only. Unchanged in the working tree. Decide explicitly whether it carries the same fields or record the non-goal. |
| **#4** | The one-keyword fix does **not** deliver the issue's stated purpose. With everything converged, `apply -t claude -v --show-noop` prints `no changes — environment matches agents.edn` and no `=` lines **at all** — not even for settings — because `HEAD:src/agentctl/main.clj:132-134` calls `render-plan` only `(if (seq changes) …)` (same short-circuit at working-tree `main.clj:136-138`). Confirming a hooks change landed is exactly the fully-converged case. |
| **#4** | `project-hook-ops` does **not** exist at HEAD; it is uncommitted and gated on `:entity-dsl?`. Any patch written against it is a patch against #1's slice. |
| **#2** | The defect is real at HEAD (deleting a whole project stanza plans `+ mcps/search` / `0 to remove`, and `~/.claude.json` keeps `projects.<path>.mcpServers.search`), but the **fix already exists in the uncommitted tree** and was observed working: the manifest now records `:project-path`/`:resolved-path` (`core.clj:390`), `claude.clj:340-341` falls back to it, deleting the project plans `- projects/sample mcps/search` / `1 to remove`, and a hand-made sibling survives. Do not implement it a second time — write the e2e guard. |
| **#5** | *"only verified for a one-provider manifest (2026-08-20)"* is **false**. Of the 88 directories in `~/.config/agentctl/backups/`, four hold real-home files: `20260820-174135` (omp `models.yml`, pi `models.json`, llm `extra-openai-models.yaml` + `keys.json`), `20260830-130110` (`~/.claude.json`, `~/.codex/config.toml`), `20260830-143338` and `20260830-143450` (`~/.claude/settings.json`). MCP and settings have converged live, on 2026-08-30. |
| **#5** | New blocker, not in the body: the live `~/.config/agents.edn` is a **12-line entity-DSL stub** declaring only `:skill-packs` and `:skills`; the full config is `~/.config/agents.edn.old` (6.2k, 2026-09-07). `validate` from a clean checkout of HEAD reports `2 ok · 4 warning(s) · 0 unknown · 10 error(s)` against it — its `DRIFT ✓ environment matches agents.edn` is an **artefact of an empty parse**, not convergence. From the working tree the same command reports `4 ok · 8 warning(s) · 0 error(s)` and `6 pending change(s)`. |
| **#6** | Both targets are now confirmed from **primary vendor sources**, not convention: pi 0.84.2 `docs/usage.md:100-102` ("`~/.pi/agent/AGENTS.md` for global instructions") and omp 17.3.5's context-file provider, whose loader does `path.join(getAgentDir(), "AGENTS.md")` at the `user` level. `HEAD:src/agentctl/adapters/pi.clj:17` and `omp.clj:16` are both **correct** — the ticket is no longer "is the path right", it is "delete the hedge". |
| **#7** | The installed CLI is **codex-cli 0.155.1**, not the 0.153.4 the body names, and it still rejects the binding. The rejection enumerates the whole accepted set — `open_agents`, `open_transcript`, `open_external_editor`, `copy`, `clear_terminal`, `submit`, `queue`, `toggle_shortcuts`, `toggle_vim_mode`, `toggle_fast_mode`, `toggle_raw_output`, `toggle_side_conversation` for `tui.keymap.global` — across 12 contexts, and **no context has a model-picker action**. This is a positive enumeration, not an inference from a failure. Nothing is missing agentctl-side: `keymap-ops` shipped in `451c091` (`HEAD:src/agentctl/adapters/codex.clj:64`). |

---

## Burn-down plan

| order | # | size | blocker | first step |
|---|---|---|---|---|
| 1 | **#1** | M | none | `cd ~/projects/agentctl && git add docs/SCOPE-DSL.md src/agentctl/skills_cli.clj tests/skills-stub && git ls-files -s tests/skills-stub` — must print mode `100755`; then `bash tests/run.sh`, commit the whole slice, push to `github.com/artmsh/agentctl`, and reconcile `docs/AGENTCTL.md` against `docs/SCOPE-DSL.md` |
| 2 | **#6** | XS | none — ride #1's docs pass | In the same `docs/AGENTCTL.md` edit, delete the `¹` footnote at lines 58-60 and the two `&nbsp;¹` markers, citing pi 0.84.2 `docs/usage.md:100-102` and omp 17.3.5 `loadContextFiles2`. Same file #1 rewrites — doing it separately guarantees a conflict |
| 3 | **#3** | S | none (sequenced after #1: `imports.clj` is dirty) | In `imports.clj` `normalize-entry`, read `:http_headers` off the unwrapped transport into `:headers` beside the existing `(:headers t)` source so `redact-map` covers it; assert with the sandbox `CODEX_HOME` repro in the body |
| 4 | **#4** | S | none technically; one UX decision (see Open questions) | Add `:report-converged? true` to the `json-array-merge-op` call in `claude/hook-ops` (`HEAD claude.clj:127`), re-run the mixed-plan repro, confirm `= hooks/guard` appears and the count reads `3 unchanged`. Raise the `main.clj` short-circuit separately |
| 5 | **#2** | S | **#1** | Write the regression step in `tests/test-agentctl.sh`: `apply!` a project declaring one MCP, delete the whole project stanza, assert the owned entry is pruned from `~/.claude.json` `projects.<path>.mcpServers` **and** from the project `.mcp.json` while a hand-made sibling survives. It passes on the worktree and fails at HEAD — that asymmetry is the guard #1 needs |
| 6 | **#5** | L | **#1**, **#3** | Port `~/.config/agents.edn.old` onto the entity DSL as a candidate file, then dry-run `agentctl validate` and `agentctl apply -A` from the working tree against it under a sandbox `AGENTCTL_HOME`. Never `apply!` the real home until the dry plan holds no surprises |
| 7 | **#7** | XS | **upstream Codex CLI** | Nothing until the next `codex update`. Then point `CODEX_HOME` at a sandbox containing a bogus `[tui.keymap.global]` field, run `codex mcp list --json`, and read the `expected one of …` enumeration for `global` and `chat` |

### Effort

- **Unblocked work: ≈2.5 days** — #1 (M, ~1.5d) + #6 (XS, <1h) + #3 (S, ~0.5d) + #4 (S, ~0.5d). Orders 1-4 need no decision from anyone but the `--show-noop` call in #4, which has a default below.
- **Behind #1: ~0.5 day** — #2 (S). Its e2e step can be *written* today; it cannot land green until the scope-DSL slice is committed.
- **Behind #1 + #3: 3-5 days** — #5 (L). It additionally needs all six agent CLIs on `PATH`.
- **Behind upstream Codex: <1h** — #7 (XS), and only if OpenAI ever adds a model-picker `tui.keymap` action.

Six of the seven issues are reachable inside a week of focused work; the seventh is not ours to move.

---

## Cross-repo

- **`github.com/artmsh/agentctl`** is the push target for #1. fg.jo `art/agentctl` git is a **read-only pull mirror**; its issues and its 15-page wiki are native and writable. Never propose a git action against fg.jo.
- **`github.com/openai/codex`** — #7 is blocked on it accepting a `tui.keymap` action that opens the model picker; absent at 0.155.1. #3 also depends on the `codex mcp list --json` transport schema (probed at 0.155.1: `type`, `url`, `bearer_token_env_var`, `http_headers`, `env_http_headers`, `http_headers_helper`).
- **`npx -y skills`** — the external installer `skills_cli.clj` shells out to; `tests/skills-stub` stands in for it via `AGENTCTL_SKILLS_CMD`, which is why that file's `0755` bit must survive the commit in #1.
- **`art/agents-setup#4`** (port `house-mcp` to babashka) names "any agentctl template that generates it" in its done-when: when it lands, the `house` MCP declaration in `~/.config/agents.edn` must be repointed at the bb wrapper. That lands inside #5's scope — sequence #5 after agents-setup#4, or accept one more converge pass afterwards.
- **`art/lathe-tutorials#9`** asks for a tutorial on "writing a declarative tool like nix or ansible/terraform", linking agentctl as the worked example. Writing it against the legacy `:tools` map would bake in the format #1 is replacing — hold it until #1 ships.
- **#5 needs all six target CLIs installed** (claude, codex, pi, omp, llm, agy). `core/build-plan` silently skips any tool whose CLI is off `PATH`, with no error either way, so a partial install produces a false green rather than a failure.
- No dependency on any other `art/*` repo. agentctl was split out of `art/agents-setup` on 2026-08-19/20, but no other open issue there is coupled to these seven.

---

## Minor, not filed

- **Worth filing, deliberately not filed this pass** (this phase writes no new issues): the real home carries residue from test runs. 84 of the 88 directories in `~/.config/agentctl/backups/` contain **only** sandbox paths, and the live `~/.config/agentctl/state.edn` `:managed` holds two ids no `agents.edn` on this machine declares — a `:pi` provider and a `:pi` project, both stamped `20260908-094733`, matching backup dir `20260908-094731`, which holds only `agentctl-test` sandbox files. **This is not current behaviour:** today's `tests/run.sh` is properly sandboxed (88 backups before, 88 after a full green run), so the residue predates the current `AGENTCTL_HOME` export. Recommend a small ticket to (a) hand-prune the two phantom ids and (b) decide whether `agentctl state` should offer a prune for ids with no declaration.
- **Worth filing, not filed**: two override gaps surfaced while confirming #6 — pi honours `PI_CODING_AGENT_DIR` and omp's `agentDir` is relocatable, but `adapters/pi.clj:11` and `omp.clj:11` hard-code `~/.pi/agent` and `~/.omp/agent`. Nothing is broken today; it only bites anyone who relocates either agent dir. `size/XS` if filed.
- `docs/AGENTCTL.md` still documents the legacy `:tools` map format end to end (12 mentions of `:tools`, 0 of `:in`). Not a separate ticket — it is the bulk of #1's M.
- Neither `~/.pi/agent/AGENTS.md` nor `~/.omp/agent/AGENTS.md` exists on this machine, and both agents keep other memory stores (`~/.pi/agent/memory/MEMORY.md`, `~/.omp/agent/memories/`). Expected: agentctl only writes those files when the config declares memory for those providers.
- This repo has **no** `AGENTS.md` and no `CLAUDE.md`. Project knowledge lives in the fg.jo wiki + `README.md` + `DESIGN.md` + `docs/`. Deliberate; not a gap.
- Audit-tooling note: the run inventory recorded `has_wiki: false` for `art/agentctl`. It has a 15-page Diátaxis wiki that every open issue body links into. Two fetch traps for the next pass — page titles use **spaces** (the hyphenated URLs printed in issue bodies 404 through the API), and the payload field is **`content_base64`**, not `content`.

---

## Open questions for the author

1. **#1 — does the commit include the bundled GUI restyle, or should the scope-DSL slice be split out?** The 1343 dirty lines span 22 files and mix the scope DSL, the skills CLI, ownership, hooks and the GUI. *Recommended default: one commit for the whole tree.* The suite is green as a unit, e2e covers the GUI only through checks 16/17, and splitting 22 files after the fact costs more than the tidier history buys.
2. **#4 — should the fully-converged case render `=` lines, i.e. should `--show-noop` bypass the `main.clj:132-134` short-circuit?** *Recommended default: yes, but only under `--show-noop`* — keep the bare `no changes — environment matches agents.edn` line for a plain `apply`. If you rule the short-circuit out of scope, flip #4 back to `size/XS`; the hook-ops keyword alone is a one-file, no-design edit.
3. **#5 — port `~/.config/agents.edn.old` by hand onto the entity DSL, or regenerate it with `agentctl import` from the current real home?** *Recommended default: import, then diff against `agents.edn.old`.* It answers the ticket's second half (is `import → apply` a fixpoint?) with the same run, and the diff is the review.
4. **#3 — does the `config.toml` fallback in `codex.clj mcp-list` have to carry the header fields too, or is it a declared non-goal?** *Recommended default: non-goal, recorded in the docstring.* The fallback exists for the pre-`mcp list --json` path; widening it doubles the surface for a path that is on its way out.
5. **#7 — keep it open indefinitely, or close it as blocked-upstream with a re-probe reminder?** *Recommended default: keep open with `status/blocked`.* The probe is ten seconds per `codex update` and the body now carries the exact recipe; closing it loses the enumeration.
6. **#2 — should it carry `priority/high`?** Its label description is "Corrupts user state or blocks daily use", and #2 leaks entries into `~/.claude.json` that nothing later removes. *Recommended default: no.* The fix already exists in the working tree and ships with #1, so the window is days, not months — raising the flag now would only make the queue noisier. Raised here rather than applied silently.

### #9 — fix: make README.md shorter

https://fg.jo/art/agentctl/issues/9  
created 2026-09-25 · updated 2026-09-25 · 0 comment(s)

_(no description)_

### #10 — feat: link project-committed .agents/skills/<name> into .claude/skills without self-copy

https://fg.jo/art/agentctl/issues/10  
created 2026-10-22 · updated 2026-10-22 · 0 comment(s)

## Behavior requested
Declare a skill that is already committed in a project's own `.agents/skills/<name>` for Claude Code in that project, so `.claude/skills/<name>` links to it. The skill source and the project-scoped destination are the same directory.

## Scope
Claude Code, project scope (`projects/outnordic`).

## Reproduction (agentctl, installed build)
```edn
:skill-packs {[:uri "file://$HOME/projects/outnordic/.agents/skills"] {:alias outnordic-skills}}
:skills {[outnordic-skills "shared-machine"] {:in "projects/outnordic" :acli [:claude]}}
```
`agentctl apply -p projects/outnordic -t claude -k skills -k skill-packs --verbose` plans:
```
exec: rm -r ~/projects/outnordic/.agents/skills/shared-machine
exec: ln -sfn ~/projects/outnordic/.agents/skills/shared-machine ~/projects/outnordic/.claude/skills/shared-machine
```
The `rm -r` deletes the committed source before linking; the link would then dangle. Not applied.

## Acceptance criteria
- A project-scoped skill whose source path resolves inside the target project's `.agents/skills` is never removed or copied onto itself.
- Plan for it is a single relative symlink `.claude/skills/<name>` -> `../../.agents/skills/<name>` (no `rm`, no `skills-lock.json` change).
- `apply` reports no drift afterwards; `apply!` is idempotent.

### #11 — feat: place project-scoped MCPs for Codex (.codex/config.toml) instead of silently dropping them

https://fg.jo/art/agentctl/issues/11  
created 2026-10-22 · updated 2026-10-22 · 0 comment(s)
labels: `area/codex` `kind/feature` `size/M` · assignees: —

Codex MCP rows with a project `:in` selector are dropped without a warning. The Codex adapter only provisions `common/global-mcps`.

**Repro (agentctl 9a7015a)**
```edn
{:id :dbx :in "projects/eva-api-sample" :acli [:codex]}
```
`agentctl validate -t codex -k mcps` reports no error. `agentctl apply --project projects/eva-api-sample -t codex -k mcps` prints "no changes". `src/agentctl/adapters/codex.clj` `mcp-ops` reads only `global-mcps`. The target dir is not a declared `:projects` entry, so `project-scope-skip-ops` does not warn either.

**Want**
Codex reads project-level `<project>/.codex/config.toml` `[mcp_servers.*]` for trusted projects. Project-scoped MCP rows for `:codex` should be written there, or validate should warn when it can't place them.

**Acceptance**
- The repro row plans `+ mcps/dbx` with target `~/projects/eva-api-sample/.codex/config.toml`. A user-wide row does not.
- `codex mcp list`, run inside the project, shows `dbx`. Run elsewhere, it does not.
- An unplaceable Codex project MCP row raises a validate warning instead of silently doing nothing.

**Workaround in use:** `{:id :dbx :in :all :acli [:codex]}`.

### #12 — feat: declare Codex [permissions.<name>] profiles (filesystem/network grants)

https://fg.jo/art/agentctl/issues/12  
created 2026-10-22 · updated 2026-10-22 · 0 comment(s)
labels: `area/codex` `kind/feature` `size/M` · assignees: —

agentctl can pick Codex's active permissions profile (`:sandbox` → `default_permissions`), but it can't declare the profile itself. You can't write `[permissions.<name>]` tables from agents.edn, including filesystem grants for paths outside the workspace.

**Request:** give Codex profile `local-dev` (currently `extends = ":workspace"` plus network rules, hand-written in `~/.codex/config.toml`) access to `/Users/art/Library/Application Support/Firefox/Profiles`. User-wide scope.

**Evidence (agentctl 9a7015a, codex-cli 0.163.0):** `adapters/codex.clj` `setting-keys` maps only model/personality/reasoning/provider/approval/`default_permissions`/service-tier. Any other `:settings` key is reported as "unsupported setting … ignored". Under `local-dev`, `codex sandbox -- touch ~/Library/Application\ Support/com.dbx.app/x` fails with EPERM, while reads outside the workspace succeed.

**Acceptance**
- agents.edn can declare a Codex permissions profile (`extends`, network rules, filesystem read/write roots). `apply` plans it as a `toml-set-op` on `[permissions.<name>]` and preserves everything else.
- `import` brings in the existing `local-dev` profile without loss.
- With write access declared for a path, `codex sandbox -- touch <path>/x` succeeds. Undeclared paths still fail.

### #13 — feat: preserve Pi provider model capabilities and compatibility metadata

https://fg.jo/art/agentctl/issues/13  
created 2026-10-25 · updated 2026-10-25 · 0 comment(s)
labels: `kind/feature` · assignees: —

Configuring user-wide Pi custom provider `sw` with `opus-5-5` and `sonnet-5-5` exposes a provisioning gap: models load and route, but Pi reports thinking=no and images=no and uses its generic 128K context / 16K output defaults.

Please allow explicit model capability metadata through the provider DSL and Pi adapter, including `reasoning`, `input`, `contextWindow`, `maxTokens`, `cost`, `thinkingLevelMap`, and `compat` (notably `forceAdaptiveThinking`). Native Pi supports these fields in models.json; aliases require explicit metadata rather than built-in model detection.

Installed-source evidence: `src/agentctl/config.clj` `norm-provider-model` retains only id/name/api/url/headers; `src/agentctl/adapters/pi.clj` `model-entry` emits only those five fields. Provider `:overrides` is normalized but not applied by Pi. Adding unsupported fields to the live manifest was avoided.

Acceptance criteria:
- Document and validate supported model capability/compatibility fields in the DSL.
- Preserve those declared values in Pi models.json, including false and null where meaningful.
- A custom alias with reasoning and image input enabled appears correctly in Pi --list-models; adaptive-thinking compatibility survives generation.
- Repeated apply has no drift; unrelated providers and native fields are preserved; unsupported metadata must not be silently discarded.

This request covers provisioning support, not assumptions about the limits or prices of particular upstream routes.

### #14 — feat: declare Claude cleanupPeriodDays (transcript retention) in :settings

https://fg.jo/art/agentctl/issues/14  
created 2026-10-28 · updated 2026-10-28 · 0 comment(s)
labels: `area/claude` `kind/feature` `size/XS` · assignees: —

## Requested behavior
Declare Claude Code transcript retention in agents.edn and provision it to `~/.claude/settings.json` as `cleanupPeriodDays`:

```clojure
:settings [{:cleanup-period-days 3650 :acli [:claude] :in :all}]
```

Target: claude, user scope (`:in :all`). Project scope is optional.

## Why
Without this key Claude Code uses its 30-day default and deletes old `~/.claude/projects/*/<id>.jsonl` transcripts, after which `claude --resume <id>` fails with `No conversation found`. A lathe-tutorials session from 2026-09-19 was lost this way; on 2026-10-28 the oldest surviving transcript was from exactly 30 days earlier.

## Evidence (installed source `103b869`)
`src/agentctl/adapters/claude.clj` `setting-keys` is a closed whitelist (model, output-style, theme, effort/thinking, auto-compact, ultracode, enable-workflows, skip-auto, status-line, model-settings, env). Any other key falls into `unsupported` and plans as `unsupported setting … for claude — ignored`.

## Acceptance criteria
- `:cleanup-period-days` maps to `:cleanupPeriodDays` in `setting-keys`.
- `agentctl apply --all -t claude -k settings` plans a set of `cleanupPeriodDays` = 3650 and does not print an `unsupported` noop.
- `apply! --yes` writes the integer to `~/.claude/settings.json`, and a re-run reports converged.
- `validate` checks it against the schemastore schema (integer, >= 0).
- `import` round-trips an existing `cleanupPeriodDays` into `:cleanup-period-days`.
- A test in `tests/agentctl_test.clj` covers it.

### #15 — fix: Claude project MCP approvals go to ~/.claude.json, which Claude Code clears (perpetual drift)

https://fg.jo/art/agentctl/issues/15  
created 2026-10-28 · updated 2026-10-28 · 0 comment(s)
labels: `area/claude` `kind/bug` `size/S` · assignees: —

## Problem
`settings/mcp` ("enable project MCP servers") writes `projects.<path>.enabledMcpjsonServers` in `~/.claude.json`. Claude Code 2.1.312 no longer keeps that key there. Any `claude` invocation in the project (even `claude mcp get dbx`) rewrites it to `null`. Claude Code reads approvals from `<project>/.claude/settings.local.json` (`enabledMcpjsonServers`), which already listed the servers.

So `apply` reports `+ enabledMcpjsonServers: [...]` again after every Claude run: drift that is permanent and has no effect.

## Evidence (agentctl 6dfa397, Claude Code 2.1.312, 2026-10-28)
- `apply! -t claude -k settings` → `enabledMcpjsonServers: ["dbx"]` in `~/.claude.json`
- `claude mcp get dbx` in `~/projects/outnordic` → the key is `null`
- `~/projects/outnordic/.claude/settings.local.json` holds `{"enabledMcpjsonServers":["dbx","newrelic"]}`, which is what Claude Code honours

## Acceptance
- The approval is written to `<project>/.claude/settings.local.json` (merged, existing entries kept), not `~/.claude.json`
- `apply` is a no-op after `apply!` followed by any `claude` command in the project
- Owned entries left in `~/.claude.json` from the legacy location are pruned or ignored

### #16 — fix: apply! writes MCPs with unresolved secret refs (locked vault) and drops the secret silently

https://fg.jo/art/agentctl/issues/16  
created 2026-10-28 · updated 2026-10-28 · 1 comment(s)
labels: `area/claude` `kind/bug` `priority/high` `size/M` · assignees: —

## Problem
When a Bitwarden ref can't be resolved (vault locked), `validate` reports it as `unknown`. `apply!` still writes the MCP entry, silently dropping the header or env var that carried the secret. The result is a configured but unauthenticated server, and `apply` then reports no drift.

## Evidence (agentctl 6dfa397, 2026-10-28)
- Declaration: `{:id :newrelic :type :http :url "https://mcp.newrelic.com/mcp/" :headers {"api-key" "!bw://newrelic/NEW_RELIC_API_KEY"} :in "projects/outnordic" :acli [:claude]}`
- `bw status` → `locked`; `validate` → `? bitwarden vault locked — refs reported as unknown`
- `apply! -t claude -k settings -k mcps --yes` → `applied 2 change(s)`. The stored entry has keys `type` and `url` only, with no `headers`. `claude mcp get newrelic` → `! Needs authentication`
- Next `apply` → no drift (`desired-shape` ignores headers/env)

## Acceptance
- `apply!` refuses, or skips with a visible warning, any op whose `:env`/`:headers` contain an unresolved ref. It never writes a partial entry.
- The plan marks such ops (e.g. `⚠ secret unresolved: vault locked`)
- Missing or changed header/env names count as drift (compare key names, not secret values)

### #17 — fix: :settings :env passes !bw:// refs through literally

https://fg.jo/art/agentctl/issues/17  
created 2026-10-28 · updated 2026-10-28 · 0 comment(s)
labels: `area/claude` `kind/bug` `size/S` · assignees: —

## Problem
`:settings` `:env` values are written verbatim. A `!bw://…` ref there lands in `settings.json` as the literal string `!bw://…`. MCP `:env`/`:headers` resolve refs through `common/resolve-env`, but the Claude settings `env` mapping (`adapters/claude.clj`, `:env :env`) does not. The plan masks it as `"!bw:…EY"`, so the preview doesn't reveal it.

Use case: a project `.mcp.json` server that expands `${NEW_RELIC_API_KEY}` from the environment. The natural place to supply the value is the project's gitignored `.claude/settings.json` `env`, sourced from the vault.

## Acceptance
- Settings `:env` values go through the same ref resolution as MCP env, for Claude and the other adapters that write an env map
- An unresolved ref behaves as in the locked-vault issue (no partial write)
- The plan notes that a secret is written into the target file, as the MCP path does

### #18 — fix: MCP transport change merges onto the old entry, leaving stale stdio keys

https://fg.jo/art/agentctl/issues/18  
created 2026-10-28 · updated 2026-10-28 · 0 comment(s)
labels: `area/claude` `kind/bug` `size/XS` · assignees: —

## Problem
A project-local (machine-scope) MCP whose transport changes from stdio to http is updated by merging the new fields onto the current entry (`mcp-json-entry`: `(merge cur …)`, with nils pruned). The stale `command`, `args` and `env` of the stdio entry would survive next to `type: "http"`/`url`. The plan only shows `~ type: "stdio" -> "http"`.

Seen 2026-10-28 (agentctl 6dfa397) converting outnordic `newrelic` from `npx newrelic-mcp` to `https://mcp.newrelic.com/mcp/`. Worked around with `claude mcp remove newrelic -s local` before `apply!`.

## Acceptance
- On a transport change, the entry is replaced, not merged: keys belonging to the old transport are removed
- The plan shows the removed keys
- Unknown or extra keys the user added to a same-transport entry are still preserved, as today

### #21 — feat: provision awl skills on remote hosts (--host)

https://fg.jo/art/agentctl/issues/21  
created 2026-10-30 · updated 2026-10-30 · 1 comment(s)
labels: `kind/feature` `size/S` · assignees: —

`agentctl --host` provisions Codex skills only, so a remote awl has to be set up by hand.

**Requested:** let a host's `:skills` and `:skill-packs` name `:awl` in `:acli`, placing them in a directory awl reads on that host (`~/.agents/skills/<name>`).

**Target:** awl, user scope, on hosts declared under `:hosts`.

**Why local behaviour doesn't cover this:** locally, `:awl` plans nothing, because awl reads `~/.config/agents.edn` itself. A remote host has no `agents.edn` when it's managed from the Mac, so awl there sees nothing unless something is placed on disk.

**Evidence (agentctl 1818933):** `docs/AGENTCTL.md` says of hosts that "any tool but codex" is "reported as not provisioned". On host `:wsl`, `agent-config` is placed in `~/.codex/skills`, and awl sees it only through a hand-made symlink `~/.agents/skills/agent-config -> ~/.codex/skills/agent-config`.

**Acceptance criteria**
- `{:acli [:awl]}` on a host skill plans an add, and `apply! --host` places it in `~/.agents/skills/<name>`, with ownership recorded in the host state file.
- `{:acli [:codex :awl]}` leaves the skill visible to both tools, with no duplicate entry in Codex.
- A later `apply --host` exits 0 with no drift, and removing the declaration removes the placement.
- `/skills` in awl on the host lists the skill.

## Closed issues

- [#1](https://fg.jo/art/agentctl/issues/1) chore: commit the scope DSL / skills-CLI work, including the untracked files — closed 2026-11-01
- [#19](https://fg.jo/art/agentctl/issues/19) Provision remote hosts over SSH, including Windows — closed 2026-10-30
- [#20](https://fg.jo/art/agentctl/issues/20) feat: accept :awl as an :acli value for :skills and :skill-packs (a consumer, nothing to provision) — closed 2026-10-30
- [#22](https://fg.jo/art/agentctl/issues/22) feat: support for Kimi Code — closed 2026-11-01
