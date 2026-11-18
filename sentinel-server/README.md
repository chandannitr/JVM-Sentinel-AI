# JVM Sentinel local API

Build from the repository root with Java 21. Spring Boot 4.1.2 is pinned in
the server module. Cache its dependencies before using offline Maven.

```bash
mvn -pl sentinel-server -am package -DskipTests
java -jar sentinel-server/target/sentinel-server-0.1.0-SNAPSHOT.jar
```

The server binds to `127.0.0.1:8080` by default. Configure `--server.port` and
`--sentinel.history-dir` on the JAR command. The history path defaults to
`.local-build/history` relative to the process working directory. Keep the
service local. Requests require a loopback peer and local Host header. The
dashboard uses same-origin fetch and the service allows GET and HEAD only.
It performs no AI inference, exposes no directory listing, and never serves
recording files. Use a history directory writable only by trusted local users.

## API v1

All timestamps use ISO-8601 UTC with a `Z` suffix. Report lists sort by
capture time descending and UUID as a stable tie-breaker. Incident lists use
incident timestamp descending, then report UUID and code. Time bounds are inclusive.

| GET route | Parameters and response |
|---|---|
| `/api/v1/health` | Returns `state` and application `version`. |
| `/api/v1/reports` | `page` 0–1535 and `limit` 1–100, default 0 and 25. Returns lightweight report headers. |
| `/api/v1/reports/{reportId}` | Canonical lowercase UUID. Returns report metadata, `parseStatus`, observed values and units, unavailable IDs, rule findings, and optional advisory metadata. |
| `/api/v1/incidents` | Same pagination. Optional exact `severity`, `code`, `from`, and `to`. Returns deterministic findings only. |
| `/api/v1/metrics/timeline` | `metric` evidence ID, default `jmx.heap_used_bytes`. Optional `limit` 1–200 (default 100), `from`, and `to`. Returns chronologically ordered points from the most recent matching reports. A null point is a gap. |
| `/api/v1/summary` | Totals across scanned reports, rule counts by code and severity, incidents in the last 24 hours, and latest capture time. |

List responses contain `items`, `page`, `limit`, `total`, and `diagnostics`.
Diagnostics count corrupt, oversized, and unsupported documents and flag a
truncated scan. Empty lists and timelines return empty arrays. The timeline
samples saved report snapshots. It does not interpolate data between them.
Errors return a safe `code` and `message`, including 400 for invalid requests,
404 for missing reports, and 500 if history is inaccessible.

## Historical anomalies

`GET /api/v1/anomalies` analyzes saved readings with these optional parameters:

| Parameter | Default and accepted values |
|---|---|
| `metric` | `jmx.heap_used_bytes` (default) or `executor.queue_depth` |
| `scenario` | `JVM` or `DEADLOCK` for heap, `EXECUTOR` for queue. Defaults are `JVM` and `EXECUTOR`. |
| `from`, `to` | Inclusive UTC bounds. Only readings inside the range can contribute to the baseline. |
| `limit` | 1–50 retained findings, default 20, in chronological order |
| `minimum`, `windowSamples` | Minimum is 3 up to the window size, default 20. Window size is at most 100, default 60. |
| `windowSeconds`, `cooldownSeconds` | Window 1–604800 (default 3600). Cooldown 0–604800 (default 300). |
| `sensitivity`, `consecutive` | Sensitivity 1–20 (default 3.5). Consecutive comparisons 2–10 (default 2). |

The result includes `state`, `metric`, `unit`, baseline sample counts,
`latest` comparison, `findings`, and scan diagnostics. With insufficient history
the state is `INSUFFICIENT_DATA` and `latest` is null. Comparisons include the
observed value, baseline window, median, MAD, deviation, threshold, sample
references and configuration. `CANDIDATE` is unconfirmed, `ALERT` is a
sustained increase, and `COOLDOWN` is continuing pressure without a new alert.
These findings do not change the archived incident list.

The CLI accepts the same parameter names as `--name=value`, with the metric
supplied as `--anomalies=METRIC` and an optional `--history-dir=DIR`.

## Storage and advisory limits

The writer retains the most recent 200 valid reports. A scan visits at most
512 directory entries and reads at most 16 KiB per report. A write refuses to
extend a directory when a full scan was not possible. Corrupt documents are
left for local inspection. Report filenames are generated UUIDs and symlinked
roots and report entries are refused. A flushed staging file is renamed
atomically when supported, with a same-directory move as the fallback.

An advisory, if requested, records validation status and any validated
classification, confidence, and evidence references. Free-form model prose
stays in CLI output. Observations, detections, and advisories are rendered
separately in the dashboard.
