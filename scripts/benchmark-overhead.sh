#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if printenv JAVA_HOME >/dev/null; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v python3 >/dev/null || { echo 'Python 3 is required for benchmark orchestration.' >&2; exit 2; }
exec python3 scripts/benchmark-overhead.py "$@"
