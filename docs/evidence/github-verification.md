# GitHub documentation verification

- Capture time (UTC): `20261118T201648Z`.
- Git base: `3c13d7527f9f8b7aa798e93a39ffcb9823bb4d07`. The working tree contained the publication-pack docs and script edits. The tracked application and test sources matched the Git base.
- Runtime: Java 21.0.11, Maven 3.10.1, macOS arm64.
- The temporary server used a fresh history directory and listened on `127.0.0.1:18086`. It was stopped after verification.

| Command or check | Observed result |
|---|---|
| `java -version`, `mvn -version`, `git diff --check` | Exit 0 each |
| `mvn -B -ntp -o test` | Exit 0. 61 tests, 0 failures, 0 errors, 0 skipped across six modules. |
| `bash scripts/run-local.sh --help` and `bash scripts/smoke-test.sh` | Exit 0 each |
| `bash scripts/run-local.sh --demo-deadlock` | Exit 0. `PLATFORM_THREAD_DEADLOCK` showed reciprocal lock owners for the two demo threads. |
| `bash scripts/run-local.sh --demo-executor-saturation --record-jfr=5 --jfr-summary` | Exit 0. `EXECUTOR_SATURATION` with the measured counters below. |
| `bash scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary` | Exit 0. Measured CPU workload and JFR counts below. |
| `mvn -B -ntp -o -pl sentinel-server -am package -DskipTests` | Exit 0 |
| `bash scripts/capture-github-proof.sh` with `PROOF_API_URL=http://127.0.0.1:18086` | Exit 0 |
| `curl` against `/api/v1/health`, `/api/v1/reports`, `/api/v1/summary`, JFR execution-sample timeline, and `/` | HTTP 200 each |
| Headless Chrome | Rendered two saved reports, one rule incident, two active workers and four queued tasks. |
| Structured Ollama advisory | Offline validation tests passed. Live inference skipped because `127.0.0.1:11434` was unavailable. |
| GitHub Actions | YAML and workflow steps reviewed locally. No hosted run was verified. |

## Executor saturation excerpt

```text
* Active workers: 2/2
* Queued tasks: 4/4
* Rejected tasks: 1
```

## CPU and JFR excerpt

```text
CPU workload: elapsed=2000019708 ns, operations=1470060544, thread CPU time=1999075000 ns
  jdk.ThreadSleep: 0
  jdk.JavaMonitorEnter: 0
  jdk.GarbageCollection: 0
  jdk.CPULoad: 3
  jdk.ExecutionSample: 78
```

These are measurements from a single controlled run. Short JFR windows can
contain zero events. These values do not measure monitoring overhead.

## Local API excerpts

Health response body, HTTP 200:

```json
{"state":"ready","version":"0.1.0-SNAPSHOT"}
```

Selected report fields from the reports response, HTTP 200. Report IDs and
timestamps are omitted here:

```json
{
  "reports": [
    {
      "scenario": "CPU",
      "type": "OBSERVATION",
      "incidentCount": 0
    },
    {
      "scenario": "EXECUTOR",
      "type": "INCIDENT",
      "incidentCount": 1
    }
  ],
  "total": 2
}
```

The reports were saved with `--save-report --history-dir=<isolated-directory>`.
The CPU save run also used `--record-jfr=5 --jfr-summary`; it recorded 82
execution samples. This was a separate run from the CLI excerpt above.
Only reviewed excerpts are included here. Raw logs and recordings stay local.
