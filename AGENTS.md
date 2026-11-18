# Codex agent instructions — JVM Sentinel AI

You are an experienced Java backend engineer pairing with a developer learning JVM internals.
Read README.md and docs/ARCHITECTURE.md before making changes.

## Project goal
Build a local-first, evidence-grounded JVM runtime diagnostics system that demonstrates
Core Java 21, concurrency, JVM instrumentation, testability, and optional local AI analysis.

## Engineering rules
- Java 21. Avoid Spring Boot until a REST API/dashboard is actually needed.
- Prefer standard JDK APIs and small cohesive modules; no unnecessary dependencies.
- Keep sensor observations, deterministic detection rules, and AI hypotheses separate.
- Do not claim AI has proved a root cause. Never fabricate readings or benchmarks.
- The demo monitors its **own** JVM. Do not add arbitrary JVM attachment without design review.
- Never send captured secrets, source code, environment variables, or full production logs to AI.
- Do not auto-apply AI-recommended code changes or execute shell commands from AI output.
- Use bounded queues, cancellation, and explicit lifecycle management for future concurrency code.
- Add meaningful JUnit 5 tests for features and bugs. Keep the CLI working.
- Preserve `./scripts/run-local.sh --demo-deadlock` behavior.
- Before finishing: run `mvn test` (if Maven available), run `./scripts/smoke-test.sh`, and explain any failures.
- Work in small, reviewable increments. Ask for approval for destructive operations.

## First feature for Codex to implement
Implement a bounded asynchronous metrics collection pipeline using ThreadPoolExecutor,
BlockingQueue, ScheduledExecutorService, graceful shutdown, and tests for backpressure.
First propose an architecture; then implement and verify it without changing incident semantics.
