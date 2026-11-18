# Local AI advisories

The CLI sends one schema-constrained `/api/generate` request after sampling and
any JFR recording finish. The default model is `llama3.2:3b` and can be overridden
with `OLLAMA_MODEL`. Use a locally installed model. The transport pins plain HTTP
to `127.0.0.1:11434`, uses a direct connection, and does not follow redirects.
The request is capped at 16 KiB and the response at 32 KiB. The connection
budget is two seconds and the request budget is ten seconds including the body.

The evidence DTO carries only numeric observations and predefined scenario,
metric, and incident names. It records rule support and capture time, marks
missing observations as `not_collected`, and records the redacted categories.
Local thread and incident details can still appear in ordinary diagnostic output.

| Example ID | Source |
|---|---|
| `executor.active_workers` | Executor snapshot's `activeWorkers` |
| `executor.max_workers` | Executor snapshot's `workerLimit` |
| `executor.queue_depth` | Executor snapshot's `queuedTasks` |
| `executor.queue_capacity` | Executor snapshot's `queueCapacity` |
| `executor.rejected_tasks` | Executor rejection handler's counter |
| `jmx.deadlocked_threads` | Count of deadlocked thread IDs from the JVM snapshot |
| `cpu.elapsed_ns` | CPU workload's measured monotonic elapsed time |
| `cpu.thread_cpu_ns` | Difference between worker thread CPU-time readings, if available |
| `jfr.jdk.ExecutionSample.count` | Count observed by reading the saved JFR file |

The report references only known evidence IDs and classified incidents. A null
incident code denotes an observations-only report. The validator rejects
quantitative prose conservatively, including numerals and common number words.
Model text can still make unsupported qualitative inferences. Evidence
references and qualitative model confidence do not establish causality.
Reports have no execution or remediation hooks. Failed requests or validation
leave the deterministic report available.

The bounded JSON codec keeps the no-Maven launcher working with just Java 21.
The JUnit fixture evaluation exercises accepted advisories, ungrounded results,
invalid replies, and unavailable transports. Its outcomes measure validation
behavior against fixtures. It does not evaluate model accuracy or require Ollama.

```bash
mvn -o -pl sentinel-ai -am -Dtest=AdvisoryEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test
```
