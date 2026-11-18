# Phase 7 live walkthrough

Allow 3–5 minutes. Start in the repository root with Java and Maven using
JDK 21. The launcher builds offline from cached Maven dependencies. Use
`--online` for the first build if the dependencies are missing.

```bash
./scripts/demo-phase7.sh
```

The launcher creates a fresh directory under `.local-build/phase7-demo/` and
starts a loopback server at [127.0.0.1:8081](http://127.0.0.1:8081/).
Use `--port=18087` if that port is in use. Press Ctrl+C to stop the owned server.
For automated replay that stops itself:

```bash
./scripts/demo-phase7.sh --verify-only --port=18087
```

## Talk track

1. **Baseline.** Show the measured heap and thread snapshot. A single saved
   snapshot gives context for the demo. Historical comparison needs more data.
2. **Executor.** Show worker and queue capacities alongside measured
   occupancy and rejection. Explain why the saturation rule fires.
3. **CPU and JFR.** Show operations, elapsed time, and recorded event counts.
   A JFR sample shows observation in the recording window.
4. **History.** Open the executor report and CPU observation in the dashboard.
   The launcher prints the report list, summary and historical endpoint
   responses. On fresh history, the queue analysis says `INSUFFICIENT_DATA`.
5. **Historical method.** Show evidence IDs, capacity grouping, and the rolling
   baseline logic. Use the generated test data below if you want to demonstrate
   a confirmed increase. Keep its results separate from the measured demo.
6. **Verification.** Show [proof](PROOF_OF_WORK.md) and the
   [OFF/ON benchmark](BENCHMARKS.md). The benchmark is a separate experiment.

Queries to repeat while the demo is running:

```bash
curl -fsS 'http://127.0.0.1:8081/api/v1/reports'
curl -fsS 'http://127.0.0.1:8081/api/v1/anomalies?metric=executor.queue_depth'
```

If Ollama and a local model are available, add `--ai` to the launcher. In the
terminal, show supporting evidence IDs and uncertainty when the advisory is
validated. The deterministic walkthrough works with no model.

## Generated test data only

This fixture compares a constant queue depth of 2 with repeated readings of 4
in the same configuration. It asserts candidate, alert, cooldown, and renewed
alert behavior. It never writes to the demo history.

```bash
mvn -o -pl sentinel-history -am -Dtest=HistoricalAnomaliesTest#sustainedSpikeUsesZeroMadFallbackAndCooldownKeepsEvidence -Dsurefire.failIfNoSpecifiedTests=false test
```
