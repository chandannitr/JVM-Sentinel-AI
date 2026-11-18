# Two-minute local demo

Use Java and javac 21. Maven is needed for tests and the dashboard build.
From the repository root, run:

```bash
java -version
mvn test
./scripts/smoke-test.sh
./scripts/run-local.sh --help
```

Maven can fetch dependencies on the first run. Once cached, use `mvn -o test`.
On macOS, set `JAVA_HOME` to a Java 21 JDK if `mvn -version` reports a different runtime.

## Generate measured evidence

```bash
./scripts/run-local.sh --demo-deadlock
./scripts/run-local.sh --demo-executor-saturation --save-report
./scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary --save-report
```

The deadlock demo shows both platform threads waiting on each other's locks.
The executor demo shows the full bounded queue, busy workers, and one rejected
submission. The CPU report shows the measured workload and observed JFR counts.
A short recording can contain zero events of some types.

## Inspect history and the dashboard

```bash
mvn -pl sentinel-server -am package -DskipTests
java -jar sentinel-server/target/sentinel-server-0.1.0-SNAPSHOT.jar
```

Open [the local dashboard](http://127.0.0.1:8080/) and select the executor incident
for worker and queue evidence. Open the CPU observation under Recent reports
for workload and JFR counts. The timeline shows saved values and leaves gaps
where a metric was unavailable.

```bash
curl -fsS http://127.0.0.1:8080/api/v1/health
curl -fsS 'http://127.0.0.1:8080/api/v1/reports?limit=10'
```

The CLI and server default to `.local-build/history` relative to the working
directory. To use a separate directory, set CLI `--history-dir=DIR` and server
`--sentinel.history-dir=DIR` to the same absolute path. Stop the server with
`Ctrl+C`. See the [API contract](../sentinel-server/README.md).

## Optional model segment

With Ollama running and `llama3.2:3b` installed locally, run:

```bash
./scripts/run-local.sh --demo-executor-saturation --ai
```

A successful advisory labels its hypothesis, references, and uncertainty.
When the model is unavailable or the response fails validation, use the
deterministic report in the demo. The [offline fixture checks](../sentinel-ai/README.md)
exercise structured response handling without Ollama.
