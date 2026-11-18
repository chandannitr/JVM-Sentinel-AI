# Implemented and planned capabilities

The six Maven modules separate collection, rule evaluation, optional AI,
persistent history, CLI demos, and the local web surface.

| Phase | Capability | Status and verification |
|---|---|---|
| 2 | Bounded asynchronous JMX collection | Implemented in `sentinel-core`. Tests cover queue saturation, retry, failure isolation, and shutdown. |
| 3 | Platform-thread deadlock evidence and bounded JFR capture | Implemented. The CLI demo prints reciprocal lock owners. JFR tests check readable recordings, event counts, and cleanup. |
| 4 | Executor and CPU workloads | Implemented. The executor demo emits `EXECUTOR_SATURATION` after measuring a full worker pool and queue. The CPU demo reports workload observations. |
| 5 | Evidence-grounded Ollama advisories | Implemented. Offline fixtures validate schemas, references, and failure handling. Live model inference was skipped in the current capture because local Ollama was unavailable. |
| 6 | Versioned report history, loopback API, and dashboard | Implemented in `sentinel-history` and `sentinel-server`. Temporary-history tests and a local HTTP/browser run verify real saved reports. |
| 7 | Historical anomaly correlation, overhead benchmarks, and distribution packaging | Planned. The existing timeline displays saved observations. |

See the [architecture](ARCHITECTURE.md) for boundaries and the [proof register](PROOF_OF_WORK.md)
for dated run evidence. An observed JFR count applies to its recording window.
It does not establish a production root cause or characterize monitoring overhead.
