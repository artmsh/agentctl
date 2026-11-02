#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEST_HOME=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-tests.XXXXXX")
TEST_HOME=$(cd "$TEST_HOME" && pwd -P)
trap 'rm -rf "$TEST_HOME"' EXIT
export AGENTCTL_HOME="$TEST_HOME"

# The harness exports DSH_HOME/DSH_AGENTS_HOME into every child process, which
# would point the DeepSeek Harness adapter at the developer's real home instead
# of the scratch one. Unset them; the dsh tests set what they need themselves.
unset DSH_HOME DSH_AGENTS_HOME

# `build-plan` skips a tool whose CLI is not installed, so the same test sees a
# full plan on a developer machine (which has all of them) and an empty one on CI
# (which has none) — a difference that has nothing to do with what is being
# tested. Stub them so both runs see the same world. The adapters exercised
# here write their files directly; the stub only has to exist and succeed.
mkdir -p "$TEST_HOME/bin"
for cli in claude codex pi omp llm agy kimi; do
  printf '#!/bin/sh\nexit 0\n' > "$TEST_HOME/bin/$cli"
  chmod +x "$TEST_HOME/bin/$cli"
done
export PATH="$TEST_HOME/bin:$PATH"

# project skills are installed by `npx -y skills`; the stub does the same on
# disk for local sources, without the network
export AGENTCTL_SKILLS_CMD="$ROOT/tests/skills-stub"

bb -cp "$ROOT/src" "$ROOT/tests/agentctl_test.clj"
"$ROOT/tests/test-agentctl.sh"
"$ROOT/tests/test-extensions.sh"
"$ROOT/tests/test-hosts.sh"
"$ROOT/tests/test-awl.sh"
