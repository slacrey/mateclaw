#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/mateclaw-phase1-audit.$$"
RUN_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
QUICK=0

tests_pass=0; tests_fail=0; tests_skip=0
build_fail=0; artifacts_ok=0; artifacts_total=0; artifacts_skip=0
inv_pass=0; inv_fail=0; inv_eval=0; inv_na=0

mkdir -p "$TMP" 2>/dev/null || TMP="."
trap 'rm -rf "$TMP"' EXIT INT TERM

while [ "$#" -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1 ;;
    -h|--help) echo "Usage: $0 [--quick]"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

color() {
  [ -n "${NO_COLOR:-}" ] && return 0
  case "${1:-}" in
    green) printf '\033[32m' ;; red) printf '\033[31m' ;;
    yellow) printf '\033[33m' ;; bold) printf '\033[1m' ;;
    reset) printf '\033[0m' ;;
  esac
}

paint() {
  case "$1" in
    PASS|OK|GREEN) printf '%s%s%s' "$(color green)" "$1" "$(color reset)" ;;
    FAIL|RED) printf '%s%s%s' "$(color red)" "$1" "$(color reset)" ;;
    WARN|SKIP|YELLOW|N/A) printf '%s%s%s' "$(color yellow)" "$1" "$(color reset)" ;;
    *) printf '%s' "$1" ;;
  esac
}

row() {
  printf '  %-58s %s' "$1" "$(paint "$2")"
  [ -n "${3:-}" ] && printf '  %s' "$3"
  printf '\n'
}

have() { command -v "$1" >/dev/null 2>&1; }
now() { date +%s; }
elapsed() { printf '%ss' "$(($(now) - $1))"; }
lc() { wc -l <"$1" | tr -d '[:space:]'; }

tail_log() {
  [ -s "$1" ] || return 0
  printf '      last output:\n'
  tail -n 5 "$1" | while IFS= read -r line; do printf '        %s\n' "$line"; done
}

banner() {
  printf '+----------------------------------------------------------+\n'
  printf '| %-56s |\n' 'MateClaw Browser Agent Phase 1 - Audit'
  printf '| %-56s |\n' "Run at: $RUN_AT"
  printf '+----------------------------------------------------------+\n\n'
}

run_cmd() {
  label="$1"; dir="$2"; log="$3"; shift 3
  start="$(now)"
  if [ ! -d "$dir" ]; then row "$label" FAIL "(missing directory)"; return 1; fi
  (cd "$dir" && "$@") >"$log" 2>&1
  code="$?"
  if [ "$code" -eq 0 ]; then row "$label" PASS "($(elapsed "$start"))"; return 0; fi
  row "$label" FAIL "(exit $code, $(elapsed "$start"))"; tail_log "$log"; return "$code"
}

test_cmd() {
  run_cmd "$@"
  if [ "$?" -eq 0 ]; then tests_pass=$((tests_pass + 1)); else tests_fail=$((tests_fail + 1)); fi
}

skip_test() { row "$1" SKIP "$2"; tests_skip=$((tests_skip + 1)); }

build_cmd() {
  run_cmd "$@"
  [ "$?" -eq 0 ] && return 0
  build_fail=$((build_fail + 1)); return 1
}

artifact() {
  label="$1"; path="$2"; mode="${3:-check}"
  if [ "$mode" = skip ]; then row "$label" SKIP "(build not run)"; artifacts_skip=$((artifacts_skip + 1)); return 0; fi
  artifacts_total=$((artifacts_total + 1))
  if [ -f "$ROOT/$path" ]; then row "$label" OK; artifacts_ok=$((artifacts_ok + 1)); return 0; fi
  row "$label" FAIL "(missing)"; build_fail=$((build_fail + 1)); return 1
}

grep_run() {
  pattern="$1"; root="$2"; out="$3"; shift 3
  : >"$out"
  if [ ! -e "$root" ]; then echo "missing path: $root" >"$out"; return 2; fi
  grep -RInE "$@" --exclude-dir=node_modules --exclude-dir=target \
    --exclude-dir=dist --exclude-dir=.git "$pattern" "$root" >"$out" 2>/dev/null
  code="$?"
  [ "$code" -le 1 ] && return 0
  return "$code"
}

examples() {
  [ "$2" -gt 0 ] || return 0
  printf '      examples:\n'
  head -n 5 "$1" | while IFS= read -r line; do printf '        %s\n' "$line"; done
}

inv_ok() { inv_pass=$((inv_pass + 1)); inv_eval=$((inv_eval + 1)); }
inv_bad() { inv_fail=$((inv_fail + 1)); inv_eval=$((inv_eval + 1)); }

expect_zero() {
  label="$1"; out="$2"; count="$3"; status="$4"
  if [ "$status" -ne 0 ]; then row "$label" FAIL "(grep failed)"; examples "$out" "$(lc "$out")"; inv_bad
  elif [ "$count" -eq 0 ]; then row "$label" PASS "(0 matches)"; inv_ok
  else row "$label" FAIL "($count matches)"; examples "$out" "$count"; inv_bad
  fi
}

first_loc() {
  line="$(head -n 1 "$1")"; file="${line%%:*}"; rest="${line#*:}"
  printf '%s:%s' "${file##*/}" "${rest%%:*}"
}

section_done() {
  if [ "$1" -eq 0 ]; then row 'section result' PASS; else row 'section result' FAIL; fi
  printf '\n'
}

banner
cd "$ROOT" || exit 2

echo '=== 1. Test Suite Status ==='
if [ "$QUICK" -eq 1 ]; then
  skip_test 'java-browser-tests' '(--quick)'
elif have mvn; then
  test_cmd 'java-browser-tests' "$ROOT/mateclaw-server" "$TMP/java.log" mvn -q test -Dtest='vip.mate.browser.**'
else
  skip_test 'java-browser-tests' '(WARN: mvn not found in PATH)'
fi
if have pnpm; then
  test_cmd 'ts-native-host' "$ROOT/mateclaw-browser-bridge" "$TMP/bridge-test.log" pnpm test
  test_cmd 'ts-extension' "$ROOT/mateclaw-extension" "$TMP/ext-test.log" pnpm test
else
  skip_test 'ts-native-host' '(WARN: pnpm not found in PATH)'
  skip_test 'ts-extension' '(WARN: pnpm not found in PATH)'
fi
section_done "$tests_fail"

echo '=== 2. Build Sanity ==='
if have pnpm; then
  if build_cmd 'mateclaw-browser-bridge build' "$ROOT/mateclaw-browser-bridge" "$TMP/bridge-build.log" pnpm build; then
    artifact 'mateclaw-browser-bridge/dist/cmd/bridge.js' 'mateclaw-browser-bridge/dist/cmd/bridge.js'
  else
    artifact 'mateclaw-browser-bridge/dist/cmd/bridge.js' 'mateclaw-browser-bridge/dist/cmd/bridge.js' skip
  fi
  if build_cmd 'mateclaw-extension build' "$ROOT/mateclaw-extension" "$TMP/ext-build.log" pnpm build; then
    artifact 'mateclaw-extension/dist/manifest.json' 'mateclaw-extension/dist/manifest.json'
    artifact 'mateclaw-extension/dist/sidepanel.html' 'mateclaw-extension/dist/sidepanel.html'
    artifact 'mateclaw-extension/dist/service-worker.js' 'mateclaw-extension/dist/service-worker.js'
  else
    artifact 'mateclaw-extension/dist/manifest.json' 'mateclaw-extension/dist/manifest.json' skip
    artifact 'mateclaw-extension/dist/sidepanel.html' 'mateclaw-extension/dist/sidepanel.html' skip
    artifact 'mateclaw-extension/dist/service-worker.js' 'mateclaw-extension/dist/service-worker.js' skip
  fi
else
  row 'mateclaw-browser-bridge build' SKIP '(WARN: pnpm not found in PATH)'
  artifact 'mateclaw-browser-bridge/dist/cmd/bridge.js' 'mateclaw-browser-bridge/dist/cmd/bridge.js' skip
  row 'mateclaw-extension build' SKIP '(WARN: pnpm not found in PATH)'
  artifact 'mateclaw-extension/dist/manifest.json' 'mateclaw-extension/dist/manifest.json' skip
  artifact 'mateclaw-extension/dist/sidepanel.html' 'mateclaw-extension/dist/sidepanel.html' skip
  artifact 'mateclaw-extension/dist/service-worker.js' 'mateclaw-extension/dist/service-worker.js' skip
fi
section_done "$build_fail"

echo '=== 3. Codex Invariants ==='
out="$TMP/i1.log"; grep_run '\.click[[:space:]]*\(|dispatchEvent[[:space:]]*\([[:space:]]*new[[:space:]]+(Mouse|Keyboard)Event' 'mateclaw-extension/src' "$out" --include='*.ts' --include='*.vue'; status="$?"; expect_zero '[1] untrusted-event ban (.click() / dispatchEvent)' "$out" "$(lc "$out")" "$status"
out="$TMP/i2.log"; grep_run 'session_id:[[:space:]]*['"'"'"](sess|user|alice|bob)' 'mateclaw-extension/src' "$out" --include='*.ts' --include='*.vue'; status="$?"; expect_zero '[2] session_id ownership' "$out" "$(lc "$out")" "$status"
out="$TMP/i3.log"; grep_run 'subjectToSession\.put[[:space:]]*\(' 'mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSessionRegistry.java' "$out" --include='*.java'; status="$?"; expect_zero '[3] register CAS atomic compute' "$out" "$(lc "$out")" "$status"
out="$TMP/i4.log"; grep_run 'JwtService\.parseUserId|validateAndGetUserId' 'mateclaw-server/src/main/java' "$out" --include='*.java'; status="$?"; expect_zero '[4] real auth APIs (no JwtService.parseUserId)' "$out" "$(lc "$out")" "$status"

out="$TMP/i5.log"; grep_run '^[[:space:]]*[A-Za-z0-9_.$#]+\.on[[:space:]]*\([[:space:]]*['"'"'"]message['"'"'"]' 'mateclaw-browser-bridge/src' "$out" --include='*.ts' --exclude='*.test.ts'
status="$?"; count="$(lc "$out")"
if [ "$status" -ne 0 ]; then row "[5] single ws.on('message') reader" FAIL '(grep failed)'; examples "$out" "$(lc "$out")"; inv_bad
elif [ "$count" -eq 1 ]; then row "[5] single ws.on('message') reader" PASS "(1 match in $(first_loc "$out"))"; inv_ok
else row "[5] single ws.on('message') reader" FAIL "($count matches; expected 1)"; examples "$out" "$count"; inv_bad
fi

out="$TMP/i6.log"; grep_run 'export[[:space:]]+(class|const)[[:space:]]+HeartbeatTimeoutError|class[[:space:]]+HeartbeatTimeoutError[^{}]*extends[[:space:]]+Error' 'mateclaw-browser-bridge/src/internal/edge/client.ts' "$out" --include='*.ts'
status="$?"; count="$(lc "$out")"
if [ "$status" -ne 0 ]; then row '[6] HeartbeatTimeoutError exported' FAIL '(grep failed)'; examples "$out" "$(lc "$out")"; inv_bad
elif [ "$count" -ge 1 ]; then row '[6] HeartbeatTimeoutError exported' PASS "($count match)"; inv_ok
else row '[6] HeartbeatTimeoutError exported' FAIL '(0 matches)'; inv_bad
fi

out="$TMP/i7.log"; grep_run '@media[[:space:]]*\([[:space:]]*prefers-reduced-motion:[[:space:]]*reduce[[:space:]]*\)' 'mateclaw-extension/src' "$out" --include='*.ts' --include='*.vue'
status="$?"; count="$(lc "$out")"
if [ "$status" -ne 0 ]; then row '[7] prefers-reduced-motion in visual code' FAIL '(grep failed)'; examples "$out" "$(lc "$out")"; inv_bad
elif [ "$count" -eq 0 ]; then row '[7] prefers-reduced-motion in visual code' N/A '(no visual code in Phase 1)'; inv_na=$((inv_na + 1))
elif [ "$count" -ge 2 ]; then row '[7] prefers-reduced-motion in visual code' PASS "($count matches)"; inv_ok
else row '[7] prefers-reduced-motion in visual code' FAIL '(1 match; expected 0 or >=2)'; examples "$out" "$count"; inv_bad
fi
section_done "$inv_fail"

echo '=== 4. Summary ==='
test_status=PASS; [ "$tests_fail" -ne 0 ] && test_status=FAIL
build_status=PASS; [ "$build_fail" -ne 0 ] && build_status=FAIL
inv_status=PASS; [ "$inv_fail" -ne 0 ] && inv_status=FAIL
if [ "$tests_fail" -ne 0 ] || [ "$build_fail" -ne 0 ]; then verdict=RED; code=2
elif [ "$inv_fail" -ne 0 ]; then verdict=YELLOW; code=1
else verdict=GREEN; code=0
fi
ran_tests=$((tests_pass + tests_fail))
printf '  %-13s %s  (%s/%s run, %s skipped)\n' 'Tests:' "$(paint "$test_status")" "$tests_pass" "$ran_tests" "$tests_skip"
printf '  %-13s %s  (%s/%s artifacts checked, %s skipped)\n' 'Builds:' "$(paint "$build_status")" "$artifacts_ok" "$artifacts_total" "$artifacts_skip"
printf '  %-13s %s  (%s/%s evaluated, %s N/A)\n' 'Invariants:' "$(paint "$inv_status")" "$inv_pass" "$inv_eval" "$inv_na"
printf '  %-13s %s\n\n' 'Overall:' "$(paint "$verdict")"
printf 'verdict: %s\n' "$verdict"
exit "$code"
