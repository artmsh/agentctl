#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEST_HOME=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-tests.XXXXXX")
TEST_HOME=$(cd "$TEST_HOME" && pwd -P)
trap 'rm -rf "$TEST_HOME"' EXIT
export AGENTCTL_HOME="$TEST_HOME"

bb -cp "$ROOT/src" "$ROOT/tests/agentctl_test.clj"
"$ROOT/tests/test-agentctl.sh"
