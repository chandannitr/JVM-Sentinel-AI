# Phase 7 local verification

- Capture date (UTC): 2026-11-18.
- Git base: `2d569ad11a4a4bcd38084b46d9e5b5fc6538a38b`. Phase 7 code was present as uncommitted source changes at capture.
- Benchmark source SHA-256: `8296c47b130df17e96f320baee72fb5043f213a29fb3eb642e2836e9577c02d4`. The digest covers module sources and POMs and the relevant launchers, including the untracked Phase 7 source files.
- Runtime: Java 21.0.11 on Darwin 25.6.0 arm64, 10 logical CPUs, 16 GiB RAM.

| Check | Observed outcome |
|---|---|
| Phase 6 baseline `mvn -B -ntp -o test` | 61 tests passed, including the real loopback history/API test. |
| Phase 7 `mvn -B -ntp test` and final `mvn -B -ntp -o test` | Exit 0, 68 tests, 0 failures, 0 errors, 0 skipped. |
| `./scripts/smoke-test.sh`, CLI help, and deadlock demo | Exit 0. The smoke test confirmed reciprocal platform-thread lock evidence. |
| Executor and CPU demos with `--record-jfr=5 --jfr-summary` | Exit 0 each. |
| `./scripts/demo-phase7.sh --verify-only --port=18087` | Exit 0. Three reports persisted, including one rule incident. The launcher stopped its server. |
| `/api/v1/anomalies?metric=executor.queue_depth`, report list, summary and dashboard | HTTP 200. The historical CLI and API agreed that one comparable queue sample was insufficient. |
| Headless Chrome | Rendered all three reports, executor queue depth, and the insufficient-history panel in the Phase 7 browser verification run. |
| `bash scripts/capture-github-proof.sh`, dashboard JavaScript syntax check, and `git diff --check` | Exit 0 each. |
| Local Ollama | Endpoint unavailable. Live inference skipped. Offline schema and grounding tests still passed. |

The live executor demo observed two active workers out of two, four queued
tasks out of four, and one rejection. The CPU demo history report recorded
79 JFR execution samples. The following is an excerpt of its companion
executor anomaly API response, keeping only aggregate fields:

```json
{
  "metric": "executor.queue_depth",
  "unit": "tasks",
  "scenario": "EXECUTOR",
  "state": "INSUFFICIENT_DATA",
  "usableSamples": 1,
  "baselineSamples": 1,
  "minimum": 20,
  "findings": []
}
```

The sustained-increase result is covered separately by fixed generated test
data. The targeted `HistoricalAnomaliesTest#sustainedSpikeUsesZeroMadFallbackAndCooldownKeepsEvidence`
run passed. No synthetic points were added to the measured demo history.

## Benchmark

```bash
./scripts/benchmark-overhead.sh --self-check
./scripts/benchmark-overhead.sh --trials 1 --warmup 5000000 --operations 20000000 --interval-ms 5
./scripts/benchmark-overhead.sh --trials 10 --warmup 40000000 --operations 250000000 --interval-ms 10
```

All commands exited 0. The single sanity pair measured
2.482% relative throughput overhead
and is excluded from the exploratory comparison. In the ten-pair run there
were ten successful trials per mode and no failures. Median throughput was
734.673 million operations/s OFF and
733.960 million operations/s ON.
The median-based difference was +0.097%.

See the [method and aggregates](../BENCHMARKS.md). The raw CSV, environment
record, summaries, JFR files, and server logs remain local and ignored. The
report reflects a controlled local run, with no hosted CI or production
performance claim.
