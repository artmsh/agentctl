#!/usr/bin/env bash
# Regenerates docs/images/gui.png: `agentctl gui` on the demo config below,
# planned against a throwaway home with stub agent CLIs. Nothing outside a temp
# dir is read or written, and the server is stopped on exit.
#
# Needs: bb, Google Chrome or Chromium, ImageMagick (magick), git, timeout (coreutils).
# Usage: docs/images/capture-gui.sh [OUT.png]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
OUT=${1:-$ROOT/docs/images/gui.png}
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-shot.XXXXXX")
SB=$(cd "$SB" && pwd -P)   # paths under HOME must match exactly to print as ~
H=$SB/home
pid=
cleanup() { [ -n "$pid" ] && kill "$pid" 2>/dev/null; wait 2>/dev/null; rm -rf "$SB"; }
trap cleanup EXIT

# Stub every agent CLI so the plan covers all tools without touching real ones;
# `bw` reports a locked vault.
mkdir -p "$SB/bin"
for cli in claude codex pi omp llm agy kimi; do
  printf '#!/bin/sh\nexit 0\n' > "$SB/bin/$cli"
done
printf '#!/bin/sh\n[ "$1" = status ] && echo %s\nexit 0\n' "'{\"status\":\"locked\"}'" > "$SB/bin/bw"
chmod +x "$SB"/bin/*

# Fixtures: a project repo, a local skill pack with two skills, a memory file.
mkdir -p "$H/.config" "$H/notes" "$H/projects/sample" \
  "$H/projects/skills-repo/skills/review" "$H/projects/skills-repo/skills/release-notes"
git -C "$H/projects/sample" init -q
git -C "$H/projects/skills-repo" init -q
printf -- '---\nname: review\ndescription: Review a diff.\n---\n' \
  > "$H/projects/skills-repo/skills/review/SKILL.md"
printf -- '---\nname: release-notes\ndescription: Draft release notes.\n---\n' \
  > "$H/projects/skills-repo/skills/release-notes/SKILL.md"
printf '# shared memory\n' > "$H/notes/AGENTS.md"
cat > "$H/.config/agents.edn" <<'EDN'
{:#def {effort "high"}

 :executors
 {:claude {:model "sonnet" :thinking $effort :on #{:auto-compact}}
  :codex  {:model "gpt-5.6-sol" :reasoning-effort $effort}}

 :mcps
 {:search "search-mcp --stdio"
  :notes  {:url "https://notes.example.com/mcp" :type :http :tools [:codex]}}

 :skill-packs {:skills-repo {:uri "file://$HOME/projects/skills-repo" :dir "skills"}}
 :skills      {:review {:from :skills-repo :tools [:claude :codex]}}
 :memory      {:shared {:from "~/notes/AGENTS.md" :mode :symlink}}

 :projects {:sample {:skills [:release-notes]}}}
EDN

cd "$SB"
env -i HOME="$H" AGENTCTL_HOME="$H" PATH="$SB/bin:$PATH" \
  AGENTCTL_SKILLS_CMD="$ROOT/tests/skills-stub" \
  "$ROOT/agentctl" gui --no-open > "$SB/gui.log" 2>&1 &
pid=$!
for _ in $(seq 60); do grep -q 'http://127' "$SB/gui.log" && break; sleep 1; done
url=$(grep -o 'http://127[^ ]*' "$SB/gui.log") || { cat "$SB/gui.log" >&2; exit 1; }

chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
command -v google-chrome >/dev/null && chrome=$(command -v google-chrome)
command -v chromium >/dev/null && chrome=$(command -v chromium)
timeout 90 "$chrome" --headless=new --hide-scrollbars --window-size=1280,800 \
  --force-device-scale-factor=2 --virtual-time-budget=6000 \
  --user-data-dir="$SB/chrome" --screenshot="$SB/raw.png" "$url" >/dev/null 2>&1

# 2x capture -> 1440 px wide, palette-quantized
magick "$SB/raw.png" -resize '1440x>' -strip -colors 256 \
  -define png:compression-level=9 "PNG8:$OUT"
echo "$OUT"
