# Benchmark methodology (no invented results)

The project has demonstrated **a CPU workload and JFR sampling**, not yet a peer-reviewed or statistically robust performance benchmark. A defensible benchmark should measure both *incident detection behavior* and the *monitor's own overhead*.

## Experiments

| Experiment | Measurement | Baseline and controls | Result |
|---|---|---|---|
| Executor saturation | Time to detect, dropped tasks, queue occupancy, worker utilization | Fixed pool size, bounded queue, deterministic blocking workload | Not benchmarked yet |
| CPU workload | Operations/sec, wall time, thread CPU time, JFR sample count | Same warmup/runtime, same JDK, machine idle, multiple runs | Single example in [proof](PROOF_OF_WORK.md); no aggregate |
| Monitoring overhead | Change in throughput, CPU time, memory allocation with/without collector | Same application, fixed workload, monitor disabled vs enabled | Not benchmarked yet |
| AI RCA latency | Local LLM response latency distribution, schema rejection rate | Fixed local model/version, identical evidence bundle, warm model | Not benchmarked yet |
| Incident correctness | True positives/negatives on labeled, reproducible scenarios | Predefined labels independent of model output | Not benchmarked yet |

## Procedure for real measurements

1. Record OS, CPU architecture, RAM, JDK build, Maven version, project commit and model version (where applicable). Do **not** include usernames or precise home directories.
2. Prewarm the JVM and local LLM where relevant; record the warmup strategy.
3. Use repeatable scenario parameters, identical load and a documented observation window.
4. Run at least 10 repeated trials for exploratory numbers; use 30 or more for stronger distribution summaries, and report median and p95 along with min/max where meaningful.
5. Compare a baseline without collection to the same workload with collection; do not compare unrelated workloads or use a single illustrative run to claim overhead reduction.
6. Report sample counts, any failures or timeouts, and environmental caveats. Use actual measured values only.
7. Link sanitized raw datasets and exact collection scripts for each published figure.

## Results template

```text
Commit: <git commit>
JDK: <java -version>
Machine: <non-identifying CPU / RAM summary>
Scenario: <exact flags and workload parameters>
Trials: <N>
Baseline median throughput: <measured>
Monitored median throughput: <measured>
Relative overhead: <calculation with units>
Median detection delay: <measured>
p95 detection delay: <measured>
Failures / dropped samples: <measured>
Known limitations: <notes>
```

**Avoid claims such as “10x faster,” “zero overhead,” or “95% accurate” until the benchmark explicitly supports them.**
