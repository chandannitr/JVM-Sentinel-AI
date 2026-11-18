# Monitoring overhead benchmark

## Reproduce

Run from the repository root with Java 21, Git and Python 3:

```bash
./scripts/benchmark-overhead.sh --self-check
./scripts/benchmark-overhead.sh --trials 1 --warmup 5000000 --operations 20000000 --interval-ms 5
./scripts/benchmark-overhead.sh --trials 10 --warmup 40000000 --operations 250000000 --interval-ms 10
```

The first command checks calculations and CSV parsing using synthetic fixtures.
The second is a sanity pair. The third runs ten fresh JVMs per configuration.
Each run writes `environment.json`, `trials.csv`, and `summary.json` under an
ignored run directory in `.local-build/benchmarks/`. The environment records
the Git base, dirty status, source digest, JDK build, OS/architecture, logical
CPU count, physical RAM, parameters, and exact fork commands.

## Measurement method

Both configurations execute the same fixed number of integer operations with
the same JVM flags `-Xms128m -Xmx128m -XX:ActiveProcessorCount=2`. Each fresh
JVM warms up with the same kernel before measuring. The harness alternates
OFF/ON and ON/OFF order across pairs and checks matching workload checksums.

ON starts the real bounded JMX sampler, which collects snapshots and runs the
JVM incident rules. OFF constructs the same objects and leaves sampling off.
The wall clock includes starting the sampler in ON. The sampler drains after
timing stops, so task counters cover the drain. JFR, persistence, HTTP, and
Ollama are outside the measured configuration. CSV records elapsed time,
throughput, available thread/process CPU time, heap occupancy before and after,
completed sampling jobs, dropped/retried ticks, and rule findings. The workload
itself is a fixed loop, with no separate submission or rejection queue. Heap
occupancy is a pair of readings. It does not measure allocations or peak memory.

Use medians to compare throughput. The summary also gives the nearest-rank p95,
min/max, and sample count for each field. With ten trials, p95 is the maximum.
Failed trials stay in the CSV and make the overall comparison unavailable.
Relative throughput overhead is `(median_off - median_on) / median_off × 100%`.
A negative value means the monitored run measured higher throughput in that
experiment. It is not a guaranteed improvement.

Each fork has a 20-second timeout. Interrupting the harness stops the active
child and can leave a partial CSV. A partial run has no complete summary.
Temperature, battery or charging state, background load, OS scheduling, tiered
JIT, GC, and shared hardware can affect these numbers. Disk behavior is outside
the timed interval. Keep the machine and JVM settings consistent and repeat
the experiment before making broader claims.

## Observed exploratory comparison

Captured on Java 21.0.11, Darwin 25.6.0 arm64, 10 logical CPUs, 16 GiB RAM.
See [Phase 7 verification](evidence/phase7-verification.md) for provenance.
All 20 trials succeeded. Values below are rounded from the generated summary.

| Metric | Monitoring OFF | Monitoring ON |
|---|---:|---:|
| Throughput median (million operations/s) | 734.673 | 733.960 |
| Throughput p95 (million operations/s) | 737.638 | 735.515 |
| Throughput min–max (million operations/s) | 723.238–737.638 | 729.986–735.515 |
| Wall time median (ms) | 340.289 | 340.618 |
| Workload thread CPU median (ms) | 340.067 | 340.070 |
| Process CPU median (ms) | 341.358 | 344.879 |
| Completed samples median | 0 | 28 |
| Dropped sampling ticks, all trials | 0 | 0 |

Observed relative throughput overhead was **+0.097%** for this workload.
The median heap occupancy reading before and after was 2,141,368 bytes in both
modes. The throughput ranges overlap. This is a local exploratory estimate,
with no statistical significance claim or general production-overhead bound.
