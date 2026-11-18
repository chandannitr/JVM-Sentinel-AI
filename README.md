# JVM Sentinel AI — Java 21 starter

A **local-first Core Java performance diagnostics project** demonstrating JVM metrics,
platform-thread deadlock detection, deterministic incident evidence, and optional AI incident
explanations using Ollama on localhost. **No cloud account or API key is required to run the MVP.**

## Included
- Four Maven modules: `sentinel-core`, `sentinel-detector`, `sentinel-ai`, `sentinel-cli`
- JMX monitoring (`MemoryMXBean`, `ThreadMXBean`) with immutable snapshots
- Deadlock and heap occupancy rules with measured evidence
- Reproducible deadlock demonstration using daemon platform threads
- Optional Ollama `/api/generate` integration using the Java 21 HTTP client
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
./scripts/run-local.sh --iterations=3 --interval-ms=500
./scripts/run-local.sh --demo-deadlock
./scripts/smoke-test.sh
```

Expected demo incident: `PLATFORM_THREAD_DEADLOCK`.
The controlled deadlock stays inside the demo process and exits safely because the threads are daemons.

## Run with Maven

```bash
mvn test
mvn package
java -jar sentinel-cli/target/sentinel-cli-0.1.0-SNAPSHOT.jar --demo-deadlock
```

## Optional: get a local AI explanation
Install Ollama from https://ollama.com and run:

```bash
ollama pull llama3.2:3b
# In a separate terminal, if Ollama isn't already running:
ollama serve
./scripts/run-local.sh --demo-deadlock --ai
```

`OLLAMA_MODEL` can override the default `llama3.2:3b`. Model availability depends on
what you have pulled locally. The application only contacts `127.0.0.1:11434`.
**Ollama does not run autonomously here**: AI provides text analysis, never changes files.

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
- ThreadMXBean deadlock checking is for platform-thread lock deadlocks, not virtual threads.
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
├── sentinel-cli/
└── scripts/
    ├── run-local.sh
    └── smoke-test.sh
```
