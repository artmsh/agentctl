#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEST_HOME=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-tests.XXXXXX")
TEST_HOME=$(cd "$TEST_HOME" && pwd -P)
trap 'rm -rf "$TEST_HOME"' EXIT
export AGENTCTL_HOME="$TEST_HOME"

# `build-plan` skips a tool whose CLI is not installed, so the same test sees a
# full plan on a developer machine (which has all six) and an empty one on CI
# (which has none) — a difference that has nothing to do with what is being
# tested. Stub them so both runs see the same world. The adapters exercised
# here write their files directly; the stub only has to exist and succeed.
mkdir -p "$TEST_HOME/bin"
for cli in claude codex pi omp llm agy; do
  printf '#!/bin/sh\nexit 0\n' > "$TEST_HOME/bin/$cli"
  chmod +x "$TEST_HOME/bin/$cli"
done
export PATH="$TEST_HOME/bin:$PATH"

bb -cp "$ROOT/src" "$ROOT/tests/agentctl_test.clj"
"$ROOT/tests/test-agentctl.sh"
