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
- [ ] JFR event correlation, executor saturation and CPU workloads, and JSONL incident export
- [ ] Monitoring-overhead benchmark

## Phase 4 — AI reliability and UX
- [ ] Structured AI output schema and checks against recorded evidence
- [ ] Traceable incident timelines, incident history, and local UI
- [ ] Model comparison and evaluation on labeled incident scenarios

## Resume-ready completion criteria
Produce reproducible tests, clear benchmark methodology, a 2-minute demo,
and documentation that distinguishes observations from AI hypotheses.
