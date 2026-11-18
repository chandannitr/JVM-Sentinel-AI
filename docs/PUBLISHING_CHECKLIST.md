# GitHub publication checklist

## Before pushing

- [x] Merge the docs against the actual checkout and verify local features.
- [x] Run Java 21 tests, smoke checks, workload demos, and the proof capture.
- [x] Verify persisted reports, HTTP responses, and browser rendering.
- [x] Review and record sanitized evidence with its Git base and working-tree state.
- [ ] Review the final commit and rerun the proof capture after code changes.
- [ ] Capture and review screenshots or a short recording for the published demo.
- [ ] Run a live model demo if advertising successful inference with a specific model.
- [ ] Push the reviewed commit and confirm the hosted workflow before adding a CI badge.

## Suggested GitHub repository details

**Description:** `Local-first Java 21 JVM diagnostics: JMX/JFR monitoring, concurrency bottleneck detection, and evidence-grounded Ollama analysis.`

**Topics:** `java`, `java21`, `jvm`, `jfr`, `jmx`, `concurrency`, `performance`, `observability`, `ollama`, `backend`

**Pinned demo:** A brief screen recording of the actual saturation → JFR → explanation workflow; dashboard detail view with the saved executor report.

## What to avoid

- Posting a passing-badge image that is not linked to a real green CI run.
- Reporting percent overhead, accuracy, or detection time without reproducible benchmarks.
- Calling an AI guess a confirmed root cause.
- Claiming the API/dashboard is implemented because it exists in a Codex prompt.
- Committing confidential code, logs, JFR recordings or raw thread dumps.
