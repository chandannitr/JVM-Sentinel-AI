# Development plan

## Phase 1 — Done in starter
- [x] Multi-module Maven project for Java 21
- [x] Local JMX snapshot collector with immutable records
- [x] Deterministic platform-thread deadlock detector
- [x] Heap-pressure warning with configurable threshold
- [x] Controlled reproducible deadlock scenario
- [x] Optional Ollama AI incident explanation via Java HttpClient
- [x] Unit tests (requires Maven to run) and a no-Maven smoke test

## Phase 2 — Core Java concurrency
- [x] Bounded BlockingQueue and ThreadPoolExecutor pipeline
- [x] Periodic scheduled sampling with graceful shutdown
- [x] Drop, retry, collection, processing, and failure metrics with concurrency tests
- [x] Synchronous and asynchronous CLI modes and a backpressure demo

## Phase 3 — JVM depth
- [x] Bounded platform-thread lock and stack evidence for deadlocks
- [x] On-demand local JFR recording, event-count summary, and readability tests
- [x] Reproducible executor saturation incident and bounded CPU workload
- [x] JFR recordings and event counts alongside observed workload metrics
- [ ] Automated JFR event correlation and JSONL incident export
- [ ] Monitoring-overhead benchmark

## Phase 4 — Reproducible workloads
- [x] Bounded executor saturation and CPU demonstrations integrated with JFR

## Phase 5 — Evidence-grounded AI advisories
- [x] Allowlisted evidence IDs, structured local reports, and grounding checks
- [x] Deterministic fallback and offline labeled fixture evaluation
- [ ] Exploratory evaluation with local models

## Phase 6 — Timeline and local UX
- [x] File-backed incident history, measured timeline, and local read-only REST API/dashboard
- [x] Reproducible end-to-end workload and dashboard walkthrough

## Phase 7 — Historical diagnostics and packaging
- [ ] Anomaly correlation across sampling windows
- [ ] Measured operational overhead and distribution packaging
- [ ] Recruiter walkthrough with repeatable setup and actual captures

## Resume-ready completion criteria
Produce reproducible tests, clear benchmark methodology, a 2-minute demo,
and documentation that distinguishes observations from AI hypotheses.
