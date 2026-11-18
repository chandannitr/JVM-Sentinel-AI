# JVM Sentinel AI — Java 21 starter

A **local-first Core Java performance diagnostics project** demonstrating JVM metrics,
platform-thread deadlock detection, deterministic incident evidence, and optional AI incident
explanations using Ollama on localhost. **No cloud account or API key is required to run the MVP.**

## Included
- Six Maven modules covering collection, rules, AI, CLI, local history, and the dashboard
- JMX monitoring (`MemoryMXBean`, `ThreadMXBean`) with immutable snapshots
- Bounded asynchronous sampling with backpressure and failure counters, plus synchronous mode
- Deadlock and heap occupancy rules with measured evidence, including bounded deadlock stacks
- On-demand Java Flight Recorder (JFR) recording of the local JVM
- Reproducible deadlock demonstration using daemon platform threads
- Optional Ollama `/api/generate` integration using the Java 21 HTTP client
- Opt-in incident history with a loopback REST API and offline dashboard
- JUnit 5 tests and a portable `javac`-based smoke test
- `AGENTS.md`: persistent project instructions for Codex inside IntelliJ IDEA

## Requirements
- **JDK 21** (`java -version` and `javac -version` should say 21)
- IntelliJ IDEA (any version compatible with JDK 21)
- Maven 3.9+ (optional for the quickstart; recommended for running unit tests)
- Ollama (optional; required only for `--ai`)

On macOS with Homebrew, a common setup is `brew install openjdk@21 maven`.
Make sure IntelliJ uses a **JDK 21**, not only a JRE. IntelliJ can also download an appropriate JDK.

## Import into IntelliJ IDEA
1. Extract this zip, then **File → Open** in IntelliJ and select the `JVM-Sentinel-AI-Starter` folder.
2. Trust the project if you recognize and trust the downloaded source.
3. Select a **Java 21 SDK** in **File → Project Structure → Project → SDK**.
4. Open the root `pom.xml` as a Maven project when prompted, or use the Maven tool window to reload.
5. If using Maven, run `mvn test` from IntelliJ's Terminal. Dependencies may be downloaded on first run.

## Run now (without Maven)
Open Terminal inside the project root:

```bash
chmod +x scripts/*.sh
./scripts/run-local.sh --help
./scripts/run-local.sh --async --iterations=3 --interval-ms=500
./scripts/run-local.sh --sync --iterations=2
./scripts/run-local.sh --demo-backpressure
./scripts/run-local.sh --demo-deadlock
./scripts/smoke-test.sh
```

Expected demo incident: `PLATFORM_THREAD_DEADLOCK`.
The daemon demo workers let the process exit normally. The report includes up to eight
deadlocked platform threads, their lock owners, and up to 12 stack frames per thread.

```bash
./scripts/run-local.sh --demo-deadlock --record-jfr=2 --jfr-summary --jfr-output=.local-build/jfr
"$JAVA_HOME/bin/jfr" summary /path/to/sentinel-recording.jfr
```

`--record-jfr` accepts 1–60 seconds. Each invocation writes a uniquely named recording and
prints its absolute path. Use that path in the summary command and set `JAVA_HOME` to your JDK 21. The default output directory is `.local-build/jfr`.
The recording enables thread sleeps, monitor contention of at least 10 ms, garbage
collection, and CPU load and execution sampling when supported by JFR. `--jfr-summary` prints observed counts for those events, including zero counts. A short run may contain few events. Keep recordings local if thread
names or stack traces contain sensitive information.

The backpressure demo offers collection work every 1 ms, allows one queued job, and
adds 50 ms to each reporting job. It runs at least three attempts and prints the
collection, processing, drop, and failure counters to stderr.

## Reproducible workload demos

```bash
./scripts/run-local.sh --demo-executor-saturation
./scripts/run-local.sh --demo-cpu-load
./scripts/run-local.sh --demo-executor-saturation --record-jfr=5 --jfr-summary
./scripts/run-local.sh --demo-cpu-load --cpu-seconds=2 --record-jfr=5 --jfr-summary
```

The executor demo holds two workers with a latch, fills four queue slots, then
attempts one more submission and counts its rejection. `EXECUTOR_SATURATION`
fires when active workers reach the worker limit and queued tasks reach queue
capacity. The report shows rejection separately from that rule. Queueing can
occur with zero JFR monitor events.

The CPU demo uses one interruptible arithmetic worker for two seconds by
default. `--cpu-seconds=1..5` changes the duration. Output includes measured elapsed
time, completed operations, and worker CPU time when the JVM exposes it. The
single workload measurement does not establish system-wide CPU pressure.

Use one demo flag per run. The new workload demos run independently of periodic
JMX sampling. Combined recordings start before the workload and require it to
finish within the recording window. For CPU runs, choose a recording duration
longer than the requested workload duration. Short recordings can legitimately
show zero execution samples or GC events. Counts describe the saved recording.

## Local incident history and dashboard

From the repository root, run the two demos and then start the dashboard.
Ollama is optional.

```bash
./scripts/run-local.sh --demo-executor-saturation --save-report
./scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary --save-report
mvn -pl sentinel-server -am package -DskipTests && java -jar sentinel-server/target/sentinel-server-0.1.0-SNAPSHOT.jar
```

Open [the dashboard](http://127.0.0.1:8080/). Select the executor incident to see
the measured queue and worker counts. Select the CPU observation under Recent
reports for workload metrics and JFR counts. The server reads saved documents on
refresh. The first Maven build may download the pinned server dependencies.
Once cached, you can add `-o` to the Maven build command.

```bash
curl -fsS 'http://127.0.0.1:8080/api/v1/reports?limit=10'
```

Excerpt from a local `/api/v1/summary` response after running the two demos:

```json
{
  "totalReports": 2,
  "totalIncidents": 1,
  "byCode": {"EXECUTOR_SATURATION": 1},
  "bySeverity": {"WARNING": 1}
}
```

The default history path is `.local-build/history` relative to the process
working directory. From different working directories, set both CLI
`--history-dir=/absolute/path` and server `--sentinel.history-dir=/absolute/path`.
The server port can be changed with `--server.port=8081`. To reproduce an empty
history, run the same JAR with `--server.port=8081 --sentinel.history-dir=.local-build/empty-history`
using an unused path.

Each `--save-report` invocation saves one measured report. It prefers the first
incident-bearing snapshot during periodic monitoring and otherwise saves the
first observation. The store retains the latest 200 valid reports by capture
time. History and the API retain measured facts, deterministic findings, and
optional advisory metadata. Free-form AI prose, stacks, and raw JFR files stay
out of the history API. Timeline points come from saved reports, with gaps
where a metric was unavailable. See the [API and history contract](sentinel-server/README.md).

## Run with Maven

```bash
mvn test
mvn package
java -jar sentinel-cli/target/sentinel-cli-0.1.0-SNAPSHOT.jar --demo-deadlock
```

## Optional local AI advisory

Install Ollama from https://ollama.com and pull the model before running the demos.

```bash
ollama pull llama3.2:3b
# In a separate terminal, if Ollama is not running
ollama serve

./scripts/run-local.sh --demo-deadlock --ai
./scripts/run-local.sh --demo-executor-saturation --record-jfr=5 --jfr-summary --ai
./scripts/run-local.sh --demo-cpu-load --record-jfr=5 --jfr-summary --ai
```

`OLLAMA_MODEL` overrides the default `llama3.2:3b`. The CLI sends one request to
`127.0.0.1:11434` after the workload, sampling, and any JFR recording finish.
It sends allowlisted metrics and rule metadata. Thread names, stacks, raw logs,
paths, and environment data stay out of the prompt. Connection and request
timeouts are two and ten seconds, respectively. If the model is missing or its
reply fails validation, the CLI keeps the deterministic report and displays the
reason for the fallback.

A validated executor advisory can look like this sanitized offline fixture:

```text
Observed facts
  executor.active_workers = 2 workers
  executor.max_workers = 2 workers
  executor.queue_depth = 4 tasks
  executor.queue_capacity = 4 tasks
Deterministic detection
  [WARNING] EXECUTOR_SATURATION
  Rule: Active workers reach the worker limit and queued tasks reach queue capacity
AI hypothesis
  Possible explanation: Tasks may be slow to finish.
Evidence references
  executor.active_workers
  executor.queue_depth
Suggested next diagnostic steps
  Measure task duration.
Uncertainty/limitations
  Model confidence: low (qualitative)
  A snapshot cannot establish whether the condition persisted.
```

The cited IDs come from the executor snapshot's active worker and queued task
counts. Model confidence is qualitative. CPU load remains observations-only,
and execution-sample counts describe sampling. Short recordings can contain zero
events. See the [AI module guide](sentinel-ai/README.md) for evidence mapping,
validation limits, and offline fixture evaluation.

## Enable Codex in IntelliJ IDEA
1. **Settings → Plugins → Marketplace**: install JetBrains **AI Assistant**.
2. Open **AI Chat** and select **Codex**.
3. Send an initial message; follow **Install and Continue** if offered.
4. Select **ChatGPT** as the login method, and complete account authorization.
5. Choose **Workspace access** mode; do not enable full filesystem access by default.
6. Ask Codex to read `AGENTS.md` and `docs/ROADMAP.md` before coding.

See: https://www.jetbrains.com/help/ai-assistant/codex-agent.html

## First Codex prompt

> Read AGENTS.md, README.md and docs/ARCHITECTURE.md. First explain how the existing modules
> work. Then implement Phase 2: bounded async metric sampling with a ScheduledExecutorService,
> BlockingQueue, ThreadPoolExecutor, graceful shutdown, deterministic concurrency tests,
> and backpressure metrics. Work in small increments. Run `mvn test` and
> `./scripts/smoke-test.sh`; fix failures. Do not remove existing JVM metrics or detection logic.

## Safety and accuracy
- Monitors **only its own JVM**, not every process on your Mac.
- A high heap ratio is a warning, not proof of a leak.
- ThreadMXBean deadlock checking covers platform-thread lock deadlocks. Coverage excludes virtual-thread deadlocks and other causes of hangs.
- AI suggestions are hypotheses based on provided sensor evidence.
- Do not upload confidential company logs, proprietary code, or secrets.

## Layout

```text
JVM-Sentinel-AI-Starter/
├── pom.xml
├── AGENTS.md
├── README.md
├── docs/
│   ├── ARCHITECTURE.md
│   └── ROADMAP.md
├── sentinel-core/
├── sentinel-detector/
├── sentinel-ai/
├── sentinel-history/
├── sentinel-server/
├── sentinel-cli/
└── scripts/
    ├── run-local.sh
    └── smoke-test.sh
```
