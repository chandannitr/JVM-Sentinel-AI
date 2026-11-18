#!/usr/bin/env bash
# Reproducible, local-only GitHub evidence capture for JVM Sentinel AI.
# Produces reviewable text under .local-build/. Does NOT publish, commit or upload it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${PROOF_OUTPUT_DIR:-$ROOT/.local-build/github-proof-$STAMP}"
mkdir -p "$OUT" || exit 1
SUMMARY="$OUT/SUMMARY.md"
FAILURES=0

sanitize() {
  # Best-effort redaction; human review is REQUIRED before publishing.
  sed -E \
    -e 's@/Users/[^/[:space:]]+@<HOME>@g' \
    -e 's@/home/[^/[:space:]]+@<HOME>@g' \
    -e 's@/private/var/folders/[^/[:space:]]+@<TEMP>@g' \
    -e 's@(Bearer[[:space:]]+)[A-Za-z0-9._~+/-]+@\1<REDACTED>@g'
}

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then DIRTY=yes; else DIRTY=no; fi
else
  GIT_SHA="not-a-git-repository"
  DIRTY=unknown
fi

cat > "$SUMMARY" <<EOM
# JVM Sentinel AI — local proof capture

- Collected at (UTC): $STAMP
- Git commit: \`$GIT_SHA\`
- Uncommitted changes at capture: **$DIRTY**
- Source: commands actually executed locally (see logs in this folder)
- Privacy: files are draft logs; review each one manually before committing

## Check results

| Check | Result | Exit code | Log |
|---|---|---:|---|
EOM

run_check() {
  local name="$1"; shift
  local raw="$OUT/$name.raw.tmp" log="$OUT/$name.log" rc=0 result
  ("$@") >"$raw" 2>&1 || rc=$?
  sanitize <"$raw" >"$log"
  rm -f "$raw"
  if [[ "$rc" -eq 0 ]]; then result=PASS; else result="FAIL (exit $rc)"; FAILURES=$((FAILURES + 1)); fi
  printf '| `%s` | %s | %s | `%s` |\n' "$name" "$result" "$rc" "$name.log" >>"$SUMMARY"
  printf '%-32s %s\n' "$name" "$result"
}

assert_contains() {
  local name="$1" fragment="$2" result rc=0
  if grep -Fq "$fragment" "$OUT/$name.log"; then result=PASS
  else result="FAIL (expected marker missing)"; rc=1; FAILURES=$((FAILURES + 1)); fi
  printf '| `%s` | %s | %s | `%s` |\n' "$name-marker" "$result" "$rc" "$name.log" >>"$SUMMARY"
  printf '%-32s %s\n' "$name-marker" "$result"
}

skip() {
  printf '| `%s` | SKIP (%s) | — | — |\n' "$1" "$2" >>"$SUMMARY"
  printf '%-32s SKIP (%s)\n' "$1" "$2"
}

run_check java-version java -version
run_check git-diff git diff --check
if command -v mvn >/dev/null 2>&1; then
  run_check maven-version mvn -version
  if [[ "${PROOF_MAVEN_OFFLINE:-1}" == 1 ]]; then
    run_check maven-test mvn -B -ntp -o test
  else
    run_check maven-test mvn -B -ntp test
  fi
else
  skip maven-version 'Maven not installed'
  skip maven-test 'Maven not installed; NOT VERIFIED'
  FAILURES=$((FAILURES + 1))
fi

if [[ -f scripts/run-local.sh && -f scripts/smoke-test.sh ]]; then
  run_check cli-help bash scripts/run-local.sh --help
  assert_contains cli-help 'JVM Sentinel AI'
  run_check smoke-test bash scripts/smoke-test.sh
  run_check deadlock bash scripts/run-local.sh --demo-deadlock
  assert_contains deadlock 'PLATFORM_THREAD_DEADLOCK'
  run_check executor-saturation bash scripts/run-local.sh --demo-executor-saturation --record-jfr=5 --jfr-summary
  assert_contains executor-saturation 'EXECUTOR_SATURATION'
  run_check cpu-jfr bash scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary
  assert_contains cpu-jfr 'Recorded JFR events:'
  assert_contains cpu-jfr 'CPU workload:'
else
  skip cli-demo 'scripts/run-local.sh or smoke-test.sh missing'
  FAILURES=$((FAILURES + 1))
fi

if [[ "${PROOF_RUN_AI:-0}" == 1 ]]; then
  run_check ai-advisory bash scripts/run-local.sh --demo-executor-saturation --ai
  assert_contains ai-advisory "AI hypothesis"
else
  skip ai-advisory 'opt in with PROOF_RUN_AI=1; not automatically verified'
fi

if [[ -n "${PROOF_API_URL:-}" ]]; then
  if [[ "$PROOF_API_URL" =~ ^http://(127\.0\.0\.1|localhost)(:[0-9]{1,5})?$ ]]; then
    if command -v curl >/dev/null 2>&1; then
      run_check api-health curl --noproxy '*' --silent --show-error --fail --max-time 5 --write-out '\nHTTP %{http_code}\n' "$PROOF_API_URL/api/v1/health"
      run_check api-reports curl --noproxy '*' --silent --show-error --fail --max-time 5 --write-out '\nHTTP %{http_code}\n' "$PROOF_API_URL/api/v1/reports"
    else
      skip api-check 'curl unavailable'
      FAILURES=$((FAILURES + 1))
    fi
  else
    echo 'ERROR: PROOF_API_URL must be loopback HTTP with an optional numeric port, without paths.' >&2
    FAILURES=$((FAILURES + 1))
  fi
else
  skip api-check 'opt in with PROOF_API_URL=http://127.0.0.1:8080 after launching your real server'
fi

cat >> "$SUMMARY" <<'EOM'

## Interpreting these results

- `PASS` means the command exited successfully **in this capture**, not that every product requirement is proven.
- Marker checks verify the expected incident/report indicator was present.
- `SKIP` means **not tested**. Do not call a skipped feature verified.
- `FAIL` may indicate missing dependencies, a changed CLI, or a real regression. Read its log.
- Report IDs, AI claims, API routes and thresholds require independent semantic checks.
- JFR event counts depend on workload, timing and recording settings; they may be zero.
- Binary `.jfr` recordings and temporary files are not part of this proof packet.

Before publication, manually review and redact each log. Copy only selected safe excerpts
into `docs/evidence/`, attach the exact Git commit, and rerun after code changes.
EOM

printf '\nProof draft created: %s\n' "$OUT"
if (( FAILURES > 0 )); then
  printf '%s check(s) failed or could not be verified; review SUMMARY.md and logs.\n' "$FAILURES" >&2
  exit 1
fi
printf 'All attempted required checks returned success. Review the evidence manually.\n'
