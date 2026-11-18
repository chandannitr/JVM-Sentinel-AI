#!/usr/bin/env bash
# Zero-Maven quickstart; requires a Java 21 JDK with javac available.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="$ROOT/.local-build"
mkdir -p "$OUTPUT/classes"
find "$ROOT"/sentinel-{core,detector,ai,history,cli}/src/main/java -name '*.java' -print | sort > "$OUTPUT/sources.txt"
javac --release 21 -d "$OUTPUT/classes" @"$OUTPUT/sources.txt"
exec java -cp "$OUTPUT/classes" dev.jvmsentinel.cli.SentinelCli "$@"
