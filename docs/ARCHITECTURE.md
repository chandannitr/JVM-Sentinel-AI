# JVM Sentinel AI — Architecture

## Runtime architecture

```mermaid
flowchart TD
    CLI[CLI / Demo runner] --> SCHED[ScheduledExecutorService]
    CLI -->|Sync mode| COL[JMX Metrics Collector]
    CLI -->|Record on demand| JFR[Local JFR file]
    SCHED --> WORK[Single worker with bounded queue]
    WORK --> COL
    COL --> SNAP[Immutable JvmSnapshot]
    SNAP --> RULE[Deterministic Incident Detector]
    RULE --> OUT[CLI incident report]
    RULE --> EVIDENCE[Allowlisted evidence bundle]
    JFR --> EVIDENCE
    EVIDENCE --> AI[Optional local Ollama]
    AI --> VALIDATE[Schema and grounding checks]
    VALIDATE --> OUT
    EVIDENCE --> HISTORY[Opt-in versioned history]
    VALIDATE --> HISTORY
    HISTORY --> SERVER[Loopback read-only API]
    SERVER --> DASH[Static dashboard]
```

| Module | Responsibility |
|---|---|
| sentinel-core | Scheduled sampling, bounded work queue, JMX collection, and immutable snapshots |
| sentinel-detector | Deterministic deadlock, heap, and executor saturation rules and incident evidence |
| sentinel-ai | Typed advisory evidence, loopback Ollama transport, schema and grounding validation |
| sentinel-cli | Terminal output and controlled deadlock, executor, and CPU workloads |
| sentinel-history | Versioned local report snapshots, file storage, and retention |
| sentinel-server | Spring Boot HTTP DTOs and a static dashboard |

The CLI currently monitors its **own** JVM. A deadlock demonstration launches two daemon
platform threads that deadlock intentionally. They do not prevent process exit.

## Sampling and backpressure

In async mode, explicit start schedules an immediate collection job and then one job
per tick. The CLI allows four waiting jobs and one active job. The worker collects
a fresh snapshot and completes reporting before starting the next job. A zero
interval uses 1 ms in async mode. Synchronous mode collects and reports on the
calling thread.

A full queue drops the incoming tick. The next tick retries submission, so drops
do not consume the iteration budget. Accepted counts collection jobs. Collected
and processed count successful snapshots and handler calls, respectively. A
recoverable collector or handler exception is logged, counted separately, and
consumes the accepted attempt. Later attempts continue. Queue depth is a live gauge.

The pipeline starts once. Starting twice or after close throws
`IllegalStateException`. The first close stops submissions and gives accepted
jobs five seconds to drain. A timeout or interruption cancels the run, discards
queued jobs, and interrupts the active worker. Subsequent close calls return.

## Thread and JFR evidence

The collector keeps all IDs returned by the deadlock check and requests stack
details for at most eight of those platform threads, ordered by ID. Each detail
includes the thread state, lock and owner when present, and at most 12 stack
frames. A thread that disappears before lookup can have an ID without details.
The detector attaches the details to the deadlock incident. The CLI renders them
locally. The AI bundle includes only the deadlocked-thread count and rule metadata.

JFR records the current JVM only when enabled. It records for the selected
window, caps retained recording data at 16 MiB by JFR chunk, and saves the dump
to a unique file once the sampling workload and recording window finish. Enabled
events are `jdk.ThreadSleep`, `jdk.JavaMonitorEnter` (10 ms threshold), and
`jdk.GarbageCollection`. When supported, it also enables `jdk.CPULoad` at a
one-second period and `jdk.ExecutionSample` at a 20 ms period. The optional
summary streams the finished file, reports zero counts, and reports a parsing
error if the file exceeds 64 MiB or one million events. The recording closes
on success, interruption, or error.

## Workload evidence

The executor demo uses two named workers and a four-slot bounded queue. It uses
latches to hold both workers while the queue fills. A separate immutable snapshot
captures the executor counters before releasing the workers. The detector uses
worker and queue capacity independently of the rejection counter. Cleanup
releases the latch, drains accepted tasks, and bounds both graceful and forced waits.

The CPU demo uses one interruptible arithmetic worker for 1–5 seconds. Worker
CPU time comes from the JVM thread MXBean when enabled. The CPU demo emits no
pressure incident from this single measurement. For both new workloads, a JFR
recording starts before the demo and has a scheduled stop deadline. If the
workload misses that deadline, no purported full-workload artifact is saved.
JFR counts are observations for the recorded JVM and do not establish causation.

## Advisory processing

The CLI maps numeric JMX, executor, CPU, and JFR values into an immutable
\`EvidenceBundle\` with stable IDs. Rule adapters copy the code, severity, and
capture time into predefined predicates with explicit supporting metric IDs.
Unavailable measurements are marked \`not_collected\`. Raw incident text and
thread details are redacted at the request boundary.

The CLI retains the first incident-bearing JMX sample, or the measured workload
snapshot for the executor and CPU demos. It augments this evidence with available
JFR event counts and sends one request after collection and recording complete.
The sampler and its queue are released before model inference.

The AI module submits a schema with a bounded request to loopback Ollama. It
parses the HTTP response envelope and the report JSON separately. The validator
checks required fields, text and list sizes, the incident classification, evidence
references, and quantitative prose. On failure, the CLI displays the deterministic
report. Valid output remains advisory and is rendered separately from observed facts.

## Local history and HTTP boundary

The CLI writes history only with `--save-report`. The store serializes the
existing allowlisted evidence and rule data to versioned JSON. It records rule
predicates, stable metric IDs, timestamps, and an optional advisory status,
classification, confidence, and validated references. Free-form AI prose stays
in the terminal. History stores no stack traces, raw logs, or recording paths.

Writers serialize retention updates with a process monitor and an OS file
lock. A fresh UUID identifies an owner-only staging file, which is flushed and
renamed within the directory. Readers check directory identity, open report
files without following links, and skip invalid documents. Reads bound each
file and directory scan. Startup and reads leave a missing history directory
empty. The history path must be controlled by the local user.

The separate Spring Boot module binds to IPv4 loopback and exposes read-only
DTOs. It reads the history at request time, so new CLI reports appear on refresh
without restarting. The browser uses same-origin requests and text DOM nodes.
The timeline displays discrete observations and marks missing metrics as gaps.
Neither the server nor the dashboard performs inference or serves JFR files.

## Limits of the starter
- No remote JVM attachment or distributed agent.
- JMX ThreadMXBean deadlock APIs inspect platform threads; do not promise virtual-thread detection.
- High heap occupancy alone is not proof of a memory leak.
- AI output is advisory and limited to the evidence passed into the prompt.
- This is an educational MVP, not production observability infrastructure.


## Reproduce the architecture

The [implementation matrix](CAPABILITIES.md) maps each milestone to its source
and verification. Follow the [demo](REPRODUCIBLE_DEMO.md) to see measured facts
flow through the deterministic rules, local history, and dashboard.
