#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/run-local.sh" --help | grep -q 'JVM Sentinel AI'
"$ROOT/scripts/run-local.sh" --iterations=1 | grep -q 'Heap:'
"$ROOT/scripts/run-local.sh" --demo-deadlock | grep -q 'PLATFORM_THREAD_DEADLOCK'
echo 'PASS: CLI help, basic sampling, and platform-thread deadlock detection'
