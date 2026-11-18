#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/run-local.sh" --help | grep -q 'JVM Sentinel AI'
"$ROOT/scripts/run-local.sh" --iterations=1 | grep -q 'Heap:'
deadlock_report="$("$ROOT/scripts/run-local.sh" --demo-deadlock)"
grep -q 'PLATFORM_THREAD_DEADLOCK' <<< "$deadlock_report"
grep -q '(deadlock-demo-a) BLOCKED' <<< "$deadlock_report"
grep -q 'owned by deadlock-demo-b' <<< "$deadlock_report"
echo 'PASS: CLI help, basic sampling, and platform-thread deadlock evidence'
