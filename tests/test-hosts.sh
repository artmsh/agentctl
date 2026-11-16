#!/usr/bin/env bash
# End-to-end: a host declared under :hosts converges over a stubbed ssh, whose
# far side is the real POSIX script run against a scratch home.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=$(mktemp -d "${TMPDIR:-/tmp}/agentctl-hosts.XXXXXX")
trap 'rm -rf "$SB"' EXIT
SB=$(cd "$SB" && pwd -P)

export AGENTCTL_HOME="$SB"

# The harness exports DSH_HOME/DSH_AGENTS_HOME into every child process, which
# would point the DeepSeek Harness adapter at the developer's real home instead
# of the scratch one. Unset them; the dsh tests set what they need themselves.
unset DSH_HOME DSH_AGENTS_HOME
export AGENTCTL_SSH_CMD="$ROOT/tests/ssh-stub"
mkdir -p "$SB/bin"
# whether codex reads ~/.agents/skills goes by its version; set CODEX_VERSION
# to answer another one, or nothing
cat > "$SB/bin/codex" <<'STUB'
#!/bin/sh
[ "${1:-}" = --version ] && printf '%s\n' "${CODEX_VERSION-codex-cli 0.169.0}"
exit 0
STUB
chmod +x "$SB/bin/codex"
export PATH="$SB/bin:$PATH"
run() { "$ROOT/agentctl" "$@"; }
fail() { echo "FAIL: $1" >&2; exit 1; }
expect() { # expect CODE CMD... — output lands in $SB/out
  local want=$1; shift
  set +e; run "$@" > "$SB/out" 2>&1; local got=$?; set -e
  [ "$got" = "$want" ] || { cat "$SB/out" >&2; fail "exit $got, wanted $want: $*"; }
}

FAR="$SB/far"
SKILLS="$FAR/.codex/skills"
mkdir -p "$SB/pack/alpha/scripts" "$SB/pack/beta" "$FAR"
printf -- '---\nname: alpha\ndescription: A.\n---\n' > "$SB/pack/alpha/SKILL.md"
printf '#!/bin/sh\necho hi\n' > "$SB/pack/alpha/scripts/run it.sh"; chmod +x "$SB/pack/alpha/scripts/run it.sh"
printf '\000\001\377binary\r\n' > "$SB/pack/alpha/blob.bin"
printf 'hidden\n' > "$SB/pack/alpha/.hidden"
printf -- '---\nname: beta\ndescription: B.\n---\n' > "$SB/pack/beta/SKILL.md"

write_config() { cat > "$SB/agents.edn" <<EDN
{:hosts {:box {:ssh "${1:-box}" :os :linux :home "$FAR"
               :skill-packs {[:uri "file://$SB/pack"] {:acli [:codex]}}}}}
EDN
}
write_config
A="-f $SB/agents.edn --host box"

echo "1. a read-only plan reports drift and writes nothing"
expect 2 apply $A -t codex -k skills
grep -q 'skills/alpha' "$SB/out" || fail "alpha missing from plan"
grep -q "$SKILLS/alpha" "$SB/out" || fail "plan does not name the remote path"
[ -e "$SKILLS" ] && fail "dry apply must not write"
[ -e "$SB/.config/agentctl/hosts/box.edn" ] && fail "dry apply must not record state"

echo "2. apply! copies bytes faithfully and records ownership per host"
expect 0 apply! $A -y
cmp "$SB/pack/alpha/blob.bin" "$SKILLS/alpha/blob.bin" || fail "binary file altered"
[ -f "$SKILLS/alpha/.hidden" ] || fail "hidden file not shipped"
[ -x "$SKILLS/alpha/scripts/run it.sh" ] || fail "exec bit or spaced name lost"
[ -L "$SKILLS/alpha" ] && fail "a host gets copies, not links"
grep -q 'alpha' "$SB/.config/agentctl/hosts/box.edn" || fail "ownership not recorded"
[ -e "$SB/.config/agentctl/state.edn" ] && fail "a host run must not touch local state"
[ -e "$FAR/.config/agentctl/staging/"* ] 2>/dev/null && fail "staging left behind"

echo "3. converged: exit 0 and no changes"
expect 0 apply $A
grep -q '0 to add, 0 to change, 0 to remove' "$SB/out" || fail "not converged: $(cat "$SB/out")"

echo "4. drift on the host is seen, backed up, repaired"
printf 'hand edit\n' >> "$SKILLS/beta/SKILL.md"
expect 2 apply $A
grep -q 'SKILL.md' "$SB/out" || fail "plan does not say which file drifted"
expect 0 apply! $A -y
cmp "$SB/pack/beta/SKILL.md" "$SKILLS/beta/SKILL.md" || fail "drift not repaired"
grep -rq 'hand edit' "$FAR/.config/agentctl/backups" || fail "edited copy not backed up"

echo "5. an identical hand-made copy is adopted without a write"
cp -R "$SB/pack/beta" "$SB/pack/gamma"
mkdir -p "$SKILLS"; cp -R "$SB/pack/gamma" "$SKILLS/gamma"
touch -t 200001010000 "$SKILLS/gamma/SKILL.md"
expect 0 apply $A
expect 0 apply! $A -y
[ "$(date -r "$SKILLS/gamma/SKILL.md" +%Y)" = 2000 ] || fail "adoption rewrote the copy"
grep -q 'gamma' "$SB/.config/agentctl/hosts/box.edn" || fail "copy not adopted"

echo "6. a hand-made skill is left alone; a dropped owned one is pruned into backups"
mkdir -p "$SKILLS/mine"; printf 'mine\n' > "$SKILLS/mine/SKILL.md"
rm -rf "$SB/pack/gamma"
expect 2 apply $A
grep -q 'skills/mine' "$SB/out" && fail "plan touches an unowned skill"
grep -q 'skills/gamma' "$SB/out" || fail "owned skill not pruned"
expect 0 apply! $A -y
[ -e "$SKILLS/gamma" ] && fail "prune did not remove"
[ -f "$SKILLS/mine/SKILL.md" ] || fail "unowned skill removed"
ls "$FAR/.config/agentctl/backups/"*/_.codex_skills_gamma >/dev/null || fail "pruned skill not backed up"
grep -q 'gamma' "$SB/.config/agentctl/hosts/box.edn" && fail "pruned skill still owned"

echo "7. a pack that vanished prunes nothing"
mv "$SB/pack" "$SB/pack.away"
expect 0 apply $A
[ -d "$SKILLS/alpha" ] || fail "missing pack removed skills"
grep -q 'nothing is pruned' "$SB/out" || fail "missing pack not reported"
mv "$SB/pack.away" "$SB/pack"

echo "8. a failed write leaves the old copy and records nothing"
printf 'new\n' >> "$SB/pack/alpha/SKILL.md"
chmod 500 "$SKILLS"
expect 1 apply! $A -y
chmod 700 "$SKILLS"
grep -q 'failed' "$SB/out" || fail "failure not reported"
[ -f "$SKILLS/alpha/SKILL.md" ] || fail "failed write lost the old copy"
grep -q 'new' "$SKILLS/alpha/SKILL.md" && fail "failed write reported yet applied"
expect 0 apply! $A -y

echo "9. an unreachable host is an error for apply and unknown for validate"
write_config unreachable
expect 1 apply $A
grep -q 'unreachable' "$SB/out" || fail "apply does not say unreachable"
expect 1 apply! $A -y
expect 0 validate $A
grep -q '^  ? box' "$SB/out" || fail "validate does not report unknown: $(cat "$SB/out")"
grep -q '1 unknown' "$SB/out" || fail "unknown not counted"
grep -q 'host matches' "$SB/out" && fail "unreachable host reported as clean"
expect 0 validate -f "$SB/agents.edn"
grep -q '1 unknown' "$SB/out" || fail "plain validate does not probe hosts"
write_config

echo "10. a truncated answer is not a clean one"
cat > "$SB/ssh-truncated" <<'STUB'
#!/bin/sh
cat > /dev/null; printf 'R\tZm9v\n'
STUB
chmod +x "$SB/ssh-truncated"
AGENTCTL_SSH_CMD="$SB/ssh-truncated" expect 1 apply $A
grep -q 'truncated' "$SB/out" || fail "truncated answer accepted"
AGENTCTL_SSH_CMD="$SB/ssh-truncated" expect 0 validate $A
grep -q '1 unknown' "$SB/out" || fail "truncated answer not unknown"

echo "11. a CLI absent from the host is skipped, not installed for"
cat > "$SB/bare-ssh" <<STUB
#!/bin/sh
exec env PATH=/usr/bin:/bin sh -c "\$2"
STUB
chmod +x "$SB/bare-ssh"
mkdir -p "$SB/bare"
sed "s|$FAR|$SB/bare|" "$SB/agents.edn" > "$SB/bare.edn"
AGENTCTL_SSH_CMD="$SB/bare-ssh" expect 0 apply -f "$SB/bare.edn" --host box
grep -q 'skipped (CLI not installed on host): codex' "$SB/out" || fail "absent CLI not reported"

echo "12. a local run ignores hosts entirely"
run apply -f "$SB/agents.edn" > "$SB/out" 2>&1 || true
grep -q 'alpha' "$SB/out" && fail "host declarations leaked into the local plan"

echo "13. flags that make no sense on a host are refused"
expect 1 apply $A -p projects/x
grep -q 'no projects' "$SB/out" || fail "-p accepted"
expect 1 import --host box -f "$SB/agents.edn"
expect 1 apply -f "$SB/agents.edn" --host nope
grep -q 'declared: box' "$SB/out" || fail "unknown host not explained"

echo "14. state --host lists what the host owns"
expect 0 state --host box
grep -q 'alpha' "$SB/out" || fail "state --host empty"

echo "15. a git pack is fetched here and its skills land in the same run, beside a local DSL config"
mkdir -p "$SB/gitpack/delta"
printf -- '---\nname: delta\ndescription: D.\n---\n' > "$SB/gitpack/delta/SKILL.md"
git -C "$SB/gitpack" init -q
git -C "$SB/gitpack" add .
git -C "$SB/gitpack" -c user.name=t -c user.email=t@example.invalid commit -qm init
cat > "$SB/dsl.edn" <<EDN
{:skill-packs {[:uri "file://$SB/pack"] {:in :none :acli [:claude]}}
 :hosts {:box {:ssh "box" :os :linux :home "$SB/far2"
               :skill-packs {[:uri "file://$SB/pack"] {:acli [:codex]}
                             [:uri "file://$SB/gitpack"] {:type :git :acli [:codex]}}}}}
EDN
mkdir -p "$SB/far2"
expect 2 apply -f "$SB/dsl.edn" --host box
grep -q 'nothing is pruned' "$SB/out" || fail "unfetched pack not reported"
expect 0 apply! -f "$SB/dsl.edn" --host box -y
[ -f "$SB/far2/.codex/skills/delta/SKILL.md" ] || fail "fetched pack not installed in the same run: $(cat "$SB/out")"
[ -f "$SB/far2/.codex/skills/alpha/SKILL.md" ] || fail "file pack not installed"
expect 0 apply -f "$SB/dsl.edn" --host box
grep -q '0 to add, 0 to change, 0 to remove' "$SB/out" || fail "not converged after fetch: $(cat "$SB/out")"
run apply -f "$SB/dsl.edn" > "$SB/out" 2>&1 || true
grep -q 'delta' "$SB/out" && fail "host pack leaked into the local plan of a DSL config"

echo "16. -k skills keeps the fetch of a pack its skills come from"
rm -rf "$SB/.agents/skill-packs/gitpack"
sed "s|$SB/far2|$SB/far3|" "$SB/dsl.edn" > "$SB/k.edn"
mkdir -p "$SB/far3"
expect 2 apply -f "$SB/k.edn" --host box -k skills
grep -q 'clone' "$SB/out" || fail "-k skills dropped the pack fetch: $(cat "$SB/out")"
expect 0 apply! -f "$SB/k.edn" --host box -k skills -y
[ -f "$SB/far3/.codex/skills/delta/SKILL.md" ] || fail "-k skills did not place the fetched pack's skill: $(cat "$SB/out")"
expect 0 apply -f "$SB/k.edn" --host box -k skills
grep -q '0 to add, 0 to change, 0 to remove' "$SB/out" || fail "-k skills not converged: $(cat "$SB/out")"

# awl reads ~/.agents/skills; codex reads it too from 0.95.0 on, and lists a
# skill copied into both roots twice. A host of its own, so earlier ownership
# plans nothing here.
F="$SB/far5"; P="$SB/pack5"; AG="$F/.agents/skills"; CX="$F/.codex/skills"
HS="$SB/.config/agentctl/hosts/box2.edn"
mkdir -p "$F" "$P/alpha" "$P/beta"
printf -- '---\nname: alpha\ndescription: A.\n---\n' > "$P/alpha/SKILL.md"
printf -- '---\nname: beta\ndescription: B.\n---\n' > "$P/beta/SKILL.md"
awl_config() { cat > "$SB/awl.edn" <<EDN
{:hosts {:box2 {:ssh "box" :os :linux :home "$F"
                :skill-packs {[:uri "file://$P"] {:acli $1}}}}}
EDN
}
W="-f $SB/awl.edn --host box2"
converged() { expect 0 apply $W "$@"; grep -q '0 to add, 0 to change, 0 to remove' "$SB/out" || fail "not converged: $(cat "$SB/out")"; }

echo "17. codex and awl, where codex reads ~/.agents/skills: one copy, there, replacing hand-made links"
awl_config '[:codex]'
expect 0 apply! $W -y
[ -d "$CX/alpha" ] || fail "fixture: no codex copy"
mkdir -p "$AG"; chmod 500 "$AG"
awl_config '[:codex :awl]'
expect 1 apply! $W -y
chmod 700 "$AG"
grep -q 'was not written' "$SB/out" || fail "a move whose write failed still pruned: $(cat "$SB/out")"
[ -d "$CX/alpha" ] || fail "codex copy pruned though its replacement failed"
grep -q '\[:codex :skills "alpha"\]' "$HS" || fail "codex copy no longer owned after a failed move"
for s in alpha beta; do ln -s "$CX/$s" "$AG/$s"; done
awl_config '[:codex :awl]'
expect 2 apply $W
grep -q '2 to add, 0 to change, 2 to remove' "$SB/out" && fail "links counted as absent"
grep -q '0 to add, 2 to change, 2 to remove' "$SB/out" || fail "move not planned: $(cat "$SB/out")"
grep -q "not owned by agentctl" "$SB/out" || fail "hand-made link not called out"
grep -q "placed in $AG/alpha instead" "$SB/out" || fail "codex copy not said to move"
grep -q "codex reads it there too" "$SB/out" || fail "plan does not say codex reads the shared copy"
expect 0 apply! $W -y
for s in alpha beta; do
  [ -d "$AG/$s" ] && [ ! -L "$AG/$s" ] || fail "$s not copied into ~/.agents/skills"
  [ -e "$CX/$s" ] && fail "$s left in ~/.codex/skills, where codex lists it a second time"
done
ls "$F/.config/agentctl/backups/"*/_.agents_skills_alpha >/dev/null || fail "replaced link not backed up"
ls "$F/.config/agentctl/backups/"*/_.codex_skills_alpha >/dev/null || fail "moved codex copy not backed up"
grep -q '\[:awl :skills "alpha"\]' "$HS" || fail "~/.agents/skills ownership not recorded: $(cat "$HS")"
grep -q '\[:codex :skills "alpha"\]' "$HS" && fail "codex still owns the copy that moved"
converged
expect 0 validate $W
grep -q 'host matches' "$SB/out" || fail "validate after converging: $(cat "$SB/out")"
expect 0 state --host box2
grep -q 'awl .*skills .*alpha' "$SB/out" || fail "state --host does not list awl's: $(cat "$SB/out")"

echo "18. a skill added to the pack is placed on the next apply"
mkdir -p "$P/gamma"; printf -- '---\nname: gamma\ndescription: G.\n---\n' > "$P/gamma/SKILL.md"
expect 2 apply $W
grep -q '1 to add, 0 to change, 0 to remove' "$SB/out" || fail "new pack skill not planned: $(cat "$SB/out")"
expect 0 apply! $W -y
[ -f "$AG/gamma/SKILL.md" ] || fail "new pack skill not placed"
converged

echo "19. a codex older than 0.95 does not read ~/.agents/skills: it gets a copy of its own"
CODEX_VERSION='codex-cli 0.94.0' expect 2 apply $W
grep -q '3 to add, 0 to change, 0 to remove' "$SB/out" || fail "old codex not given copies: $(cat "$SB/out")"
CODEX_VERSION='codex-cli 0.94.0' expect 0 apply! $W -y
for s in alpha beta gamma; do [ -d "$CX/$s" ] && [ -d "$AG/$s" ] || fail "$s not in both roots"; done
CODEX_VERSION='codex-cli 0.94.0' converged
echo "    and loses it once codex reads the shared root"
expect 2 apply $W
grep -q '0 to add, 0 to change, 3 to remove' "$SB/out" || fail "codex copies not dropped: $(cat "$SB/out")"
expect 0 apply! $W -y
[ -e "$CX/alpha" ] && fail "codex copy kept beside the shared one"
converged

echo "20. a codex that gives no version gets a copy of its own, and a warning"
CODEX_VERSION='' expect 2 apply $W
grep -q 'gave no version' "$SB/out" || fail "unknown codex version not reported: $(cat "$SB/out")"
grep -q '3 to add, 0 to change, 0 to remove' "$SB/out" || fail "unknown codex not given copies: $(cat "$SB/out")"

echo "21. a hand-made copy codex reads beside agentctl's is reported, never touched"
mkdir -p "$CX/beta"; printf 'mine\n' > "$CX/beta/SKILL.md"
expect 0 apply $W
grep -q 'lists beta twice' "$SB/out" || fail "duplicate in codex not reported: $(cat "$SB/out")"
expect 0 apply! $W -y
grep -q mine "$CX/beta/SKILL.md" || fail "hand-made copy touched"
rm -rf "$CX/beta"

echo "22. -t awl is a host's to select, -t codex selects what codex reads"
expect 0 apply $W -t awl
expect 0 apply $W -t codex
expect 1 apply -f "$SB/awl.edn" -t awl
grep -q 'reads only :skills' "$SB/out" || fail "-t awl accepted for this machine"

echo "23. awl alone keeps the copy; dropping the declaration prunes it into backups"
awl_config '[:awl]'
converged
cat > "$SB/awl.edn" <<EDN
{:hosts {:box2 {:ssh "box" :os :linux :home "$F"}}}
EDN
expect 2 apply $W
grep -q '0 to add, 0 to change, 3 to remove' "$SB/out" || fail "dropped declaration not pruned: $(cat "$SB/out")"
expect 0 apply! $W -y
for s in alpha beta gamma; do [ -e "$AG/$s" ] && fail "$s not pruned"; done
ls "$F/.config/agentctl/backups/"*/_.agents_skills_gamma >/dev/null || fail "pruned skill not backed up"
grep -q ':awl' "$HS" && fail "pruned skills still owned: $(cat "$HS")"
converged

echo "24. a git pack only awl reads is fetched here, and placed in the same run"
rm -rf "$SB/.agents/skill-packs/gitpack"
cat > "$SB/awl.edn" <<EDN
{:hosts {:box2 {:ssh "box" :os :linux :home "$F"
                :skill-packs {[:uri "file://$SB/gitpack"] {:type :git :acli [:awl]}}}}}
EDN
expect 2 apply $W -k skills
grep -q 'clone' "$SB/out" || fail "awl-only git pack not fetched: $(cat "$SB/out")"
expect 0 apply! $W -y
[ -f "$AG/delta/SKILL.md" ] || fail "awl-only git pack not placed: $(cat "$SB/out")"
converged
echo "    and so is one whose single skill awl reads"
cat > "$SB/awl.edn" <<EDN
{:hosts {:box2 {:ssh "box" :os :linux :home "$F"
                :skill-packs {[:uri "file://$SB/gitpack"] {:alias gp :type :git :in :none}}
                :skills {[gp "delta"] {:acli [:awl]}}}}}
EDN
expect 2 apply $W
grep -q 'clone' "$SB/out" || fail "pack of an awl-only skill not fetched: $(cat "$SB/out")"
expect 0 apply! $W -y
converged

echo "hosts e2e OK"
