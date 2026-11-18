# JVM Sentinel AI

Local-first JVM diagnostics for Java 21. Reproduce deadlocks and executor saturation, inspect measured JMX and Java Flight Recorder (JFR) evidence, and investigate saved reports in a loopback dashboard.

Deterministic rules identify incidents from measurements. Optional Ollama advisories cite those measurements and keep hypotheses separate from observed facts.

## Quickstart

Use a **Java 21 JDK** for both `java` and `javac`. The CLI launcher works without Maven.

```bash
./scripts/run-local.sh --demo-deadlock
```

The demo intentionally deadlocks two daemon platform threads in its own JVM. Expect `PLATFORM_THREAD_DEADLOCK` with the blocked threads, their lock owners, and bounded stack traces.

### Run the dashboard demo

From the repository root with **Maven 3.9+** using Java 21:

```bash
./scripts/demo-phase7.sh
```

Open [http://127.0.0.1:8081/](http://127.0.0.1:8081/). The launcher creates isolated history, saves a baseline observation, an executor saturation incident, and a CPU workload observation with JFR counts. Select the executor incident to inspect the worker and queue evidence. The Historical anomalies panel reports insufficient data when the demo has too few comparable readings.

The launcher builds offline by default. Use `--online` to download dependencies on the first run, `--port=18087` if the port is occupied, or `--verify-only` for a run that checks the HTTP endpoints and then stops. Press **Ctrl+C** to stop an interactive run. Ollama is optional.

For persistent history outside the walkthrough, use `--save-report` on a CLI command and launch the server against the same history directory. See the [standalone dashboard instructions](docs/REPRODUCIBLE_DEMO.md#inspect-history-and-the-dashboard).

## What you can inspect

| Capability | Evidence |
|---|---|
| Bounded asynchronous sampling | JMX heap and thread readings, with backpressure, retry and failure counters |
| Deterministic incidents | Platform-thread deadlock, heap pressure, and executor saturation, with rule predicates and measured evidence |
| CPU workload and JFR | Completed arithmetic operations, elapsed time, available thread CPU time, and observed recording event counts |
| Local history and REST API | Versioned reports, incident filters, and timelines of saved measurements |
| Historical analysis | Rolling median and median absolute deviation for comparable heap and queue readings, with evidence references and cooldown |
| Optional AI advisory | Local Ollama output checked against a schema and the report's evidence IDs, with deterministic fallback |

```bash
./scripts/run-local.sh --demo-backpressure
./scripts/run-local.sh --demo-executor-saturation --save-report
./scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary --save-report
./scripts/run-local.sh --anomalies=executor.queue_depth
```

The CLI and standalone server default to `.local-build/history` relative to their working directories. For a custom location, set CLI `--history-dir=DIR` and server `--sentinel.history-dir=DIR` to the same path. The history writer retains up to 200 valid reports. Historical comparisons require 20 prior comparable samples by default.

## Verification and measured overhead

The [Phase 7 verification record](docs/evidence/phase7-verification.md) documents **68 passing tests**, a live loopback API check, browser rendering, and an isolated benchmark with **10 trials per mode**.

| Monitoring | Median throughput |
|---|---:|
| Off | 734.673 million operations/s |
| On | 733.960 million operations/s |

The measured median-based throughput overhead was **+0.097%** for this fixed arithmetic workload. The ranges overlapped. This is an exploratory result for one machine and configuration. See the [benchmark method, controls, and full aggregates](docs/BENCHMARKS.md).

```bash
mvn test
./scripts/smoke-test.sh
./scripts/benchmark-overhead.sh --self-check
./scripts/benchmark-overhead.sh --trials 10
```

Benchmarks require Python 3 and store raw results under `.local-build/benchmarks/`. The [Java 21 CI workflow](.github/workflows/ci.yml) runs Maven tests and the CLI smoke check. For local publication evidence, run `bash scripts/capture-github-proof.sh` and review the logs before sharing. [Proof of work](docs/PROOF_OF_WORK.md)

## Architecture

The CLI runs controlled workloads and prints observations and rule findings. An opt-in history writer saves bounded JSON reports. Spring Boot is confined to the read-only loopback API and static dashboard.

| Module | Responsibility |
|---|---|
| `sentinel-core` | JMX, JFR, immutable snapshots, and bounded sampling |
| `sentinel-detector` | Deterministic JVM and executor rules |
| `sentinel-ai` | Loopback Ollama transport and evidence validation |
| `sentinel-history` | Versioned report storage, retention, and historical comparisons |
| `sentinel-cli` | Workload demos, evidence presentation, and benchmark trials |
| `sentinel-server` | REST API and dashboard |

[Architecture and data flow](docs/ARCHITECTURE.md) · [API contract](sentinel-server/README.md) · [Capabilities and roadmap](docs/CAPABILITIES.md)

## Optional local Ollama advisory

With Ollama running locally and the model installed:

```bash
ollama pull llama3.2:3b
./scripts/run-local.sh --demo-executor-saturation --ai
```

Set `OLLAMA_MODEL` to use another local model. Requests go to `127.0.0.1:11434` after sampling finishes. If the model is unavailable or the response fails validation, the CLI keeps the deterministic report. The recorded verification used offline advisory fixtures because the local endpoint was unavailable. [AI module guide](sentinel-ai/README.md)

## Scope and limitations

The collector observes its own JVM. Deadlock detection covers platform threads. Short JFR captures may contain zero events, and a single saturation snapshot does not establish sustained pressure. The timeline displays saved values and preserves gaps. Keep raw recordings, stack traces, and private logs local. [Security and limitations](docs/SECURITY_AND_LIMITATIONS.md)
