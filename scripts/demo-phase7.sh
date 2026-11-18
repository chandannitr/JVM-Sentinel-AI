#!/usr/bin/env bash
# Keep the default history and any already running dashboard separate.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if printenv JAVA_HOME >/dev/null; then export PATH="$JAVA_HOME/bin:$PATH"; fi

verify_only=false
ai=false
port=8081
online=false
for arg in "$@"; do
  case "$arg" in
    --verify-only) verify_only=true ;;
    --ai) ai=true ;;
    --online) online=true ;;
    --port=*) port="$(printf '%s' "$arg" | cut -d= -f2-)" ;;
    --help) echo 'Usage: scripts/demo-phase7.sh [--verify-only] [--port=8081] [--online] [--ai]'; exit 0 ;;
    *) echo 'Unknown demo option. Use --help.' >&2; exit 2 ;;
  esac
done
if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ ]] || (( port < 1024 || port > 65535 )); then
  echo 'Use a port from 1024 to 65535.' >&2
  exit 2
fi
for dependency in java javac mvn curl; do command -v "$dependency" >/dev/null || exit 2; done
version="$(java -version 2>&1)"
compiler="$(javac -version 2>&1)"
if [[ "$version" != *'version "21.'* || "$compiler" != 'javac 21.'* ]]; then
  echo 'Use a Java 21 JDK for Java and javac.' >&2
  exit 2
fi
maven_version="$(mvn -version 2>&1)"
if ! grep -q 'Java version: 21\.' <<< "$maven_version"; then
  echo 'Set JAVA_HOME so Maven runs on Java 21.' >&2
  exit 2
fi
base="http://127.0.0.1:$port"
if curl --noproxy '*' -sS -o /dev/null --max-time 1 "$base/" 2>/dev/null; then
  echo 'The requested port already answers HTTP. Choose another port.' >&2
  exit 2
fi

mkdir -p .local-build/phase7-demo
work="$(mktemp -d "$root/.local-build/phase7-demo/run.XXXXXX")"
history="$work/history"
if "$online"; then
  mvn -B -ntp -pl sentinel-server -am package -DskipTests
else
  mvn -B -ntp -o -pl sentinel-server -am package -DskipTests || {
    echo 'Offline build failed. Use --online if dependencies are missing.' >&2
    exit 1
  }
fi

echo '--- Local JVM baseline ---'
./scripts/run-local.sh --sync --save-report --history-dir="$history"
echo '--- Executor saturation ---'
if "$ai"; then
  ./scripts/run-local.sh --demo-executor-saturation --save-report --history-dir="$history" --ai
else
  ./scripts/run-local.sh --demo-executor-saturation --save-report --history-dir="$history"
fi
echo '--- CPU workload with JFR ---'
./scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary \
  --save-report --history-dir="$history" --jfr-output="$work/jfr"
echo '--- Historical analysis of measured queue snapshots ---'
./scripts/run-local.sh --anomalies=executor.queue_depth --history-dir="$history"

server_pid=
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
java -jar sentinel-server/target/sentinel-server-0.1.0-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port="$port" --sentinel.history-dir="$history" \
  > "$work/server.log" 2>&1 &
server_pid=$!
ready=false
for attempt in $(seq 1 40); do
  kill -0 "$server_pid" 2>/dev/null || break
  if curl --noproxy '*' -fsS --max-time 2 "$base/api/v1/health" > "$work/health.json" 2>/dev/null; then
    ready=true
    break
  fi
  sleep .25
done
if ! "$ready" || ! kill -0 "$server_pid" 2>/dev/null; then
  echo "Local server did not become ready. Inspect $work/server.log" >&2
  exit 1
fi

for endpoint in reports summary 'anomalies?metric=executor.queue_depth'; do
  echo "GET /api/v1/$endpoint"
  curl --noproxy '*' -fsS --max-time 5 --write-out '\nHTTP %{http_code}\n' "$base/api/v1/$endpoint"
done
curl --noproxy '*' -fsS --max-time 5 -o "$work/dashboard.html" --write-out 'Dashboard HTTP %{http_code}\n' "$base/"
grep -Fq 'Historical anomalies' "$work/dashboard.html"
echo "Dashboard: $base/"
echo "Local artifacts: $work"
echo 'Proof: docs/PROOF_OF_WORK.md and docs/BENCHMARKS.md'
if "$verify_only"; then exit 0; fi
echo 'Press Ctrl+C to stop the demo server.'
wait "$server_pid"
