# Security, privacy and limitations

JVM Sentinel AI is a local development and learning project. It is not a certified production observability or incident-response system.

## Data handling

- Do not commit `.jfr` recordings, heap dumps, private logs, credentials, user home paths or production stack traces.
- Some JVM artifacts can include thread names, class names, method names and application details. Treat them as potentially sensitive even if recorded on localhost.
- Use controlled demo workloads to create public evidence. Sanitize output before committing.
- Avoid collecting environment variables, application payloads, source-code fragments, customer data or secrets in AI requests.
- Ollama and the dashboard use loopback. The dashboard API is read-only and serves saved numeric evidence and typed advisory metadata. Free-form model prose, stack traces and JFR paths stay out of persisted reports.

## AI responsibility

- Model conclusions are explicitly **advisory hypotheses**, not independently observed facts.
- Model output must not trigger code edits, shell commands, external network requests, JVM changes or automated remediation.
- Structured reports must cite evidence IDs in the measured input. Invalid replies fall back to a deterministic report.
- The model can still be wrong even if its JSON is valid. A schema validator cannot prove natural-language causal claims.

## Interpretation limits

- Short JFR recordings are samples, not exhaustive traces; an event count of zero may simply mean none occurred in the recording interval.
- A full queue and busy executor at one moment justify a saturation warning but do not establish its duration or the ultimate source of latency.
- ThreadMXBean's platform-thread deadlock detection does not automatically cover virtual threads.
- High heap usage does not automatically imply a memory leak.
- Demo workloads are intentionally artificial. Do not extrapolate their throughput or behavior to real trading/payment systems.

## Publication rules

Publish only after reviewing **every** generated log/screenshot and after testing the same commit that the proof claims to validate. Never include employer names, systems or datasets in sample telemetry unless explicitly public and authorized.
