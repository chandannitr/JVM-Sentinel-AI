# Development plan

## Phase 1 — Done in starter
- [x] Multi-module Maven project for Java 21
- [x] Local JMX snapshot collector with immutable records
- [x] Deterministic platform-thread deadlock detector
- [x] Heap-pressure warning with configurable threshold
- [x] Controlled reproducible deadlock scenario
- [x] Optional Ollama AI incident explanation via Java HttpClient
- [x] Unit tests (requires Maven to run) and a no-Maven smoke test

## Phase 2 — Core Java concurrency (Codex's first task)
- [ ] Bounded BlockingQueue and ThreadPoolExecutor pipeline
- [ ] Periodic scheduled sampling with graceful shutdown
- [ ] Drop/retry/backpressure metrics and concurrency unit tests

## Phase 3 — JVM depth
- [ ] JFR event streaming or analysis
- [ ] Thread dump evidence and monitoring-overhead benchmark
- [ ] Load simulator for queue saturation

## Phase 4 — AI reliability and UX
- [ ] Structured AI output schema and checks against recorded evidence
- [ ] Traceable incident timelines, incident history, and local UI
- [ ] Model comparison and evaluation on labeled incident scenarios

## Resume-ready completion criteria
Produce reproducible tests, clear benchmark methodology, a 2-minute demo,
and documentation that distinguishes observations from AI hypotheses.
