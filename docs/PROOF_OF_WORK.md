# Proof of work

See [Phase 7 verification](evidence/phase7-verification.md) for the anomaly demo and benchmark provenance.

See the [local verification record](evidence/github-verification.md) for a
dated capture with the Git base, working-tree status, command outcomes, and
reviewed excerpts. The [capability matrix](CAPABILITIES.md) separates features
verified locally from planned work.

## Repeat the capture

From the repository root with Java 21:

```bash
bash scripts/capture-github-proof.sh
```

The script writes draft logs under `.local-build/`. Maven defaults to offline
mode. After reviewing dependency downloads, use `PROOF_MAVEN_OFFLINE=0` if the
local cache is incomplete. To check health and report listing for a running
local server, pass its loopback URL:

```bash
PROOF_API_URL=http://127.0.0.1:8080 bash scripts/capture-github-proof.sh
```

Set `PROOF_RUN_AI=1` only after starting Ollama with a locally installed model.
That check requires the CLI's validated hypothesis section. An exit-zero
deterministic fallback alone does not count as successful inference.

Review evidence before publishing. Keep raw logs, JFR files and personal paths
out of Git. The measurements apply to the captured workloads. For multi-trial
experiments, see [Benchmark methodology](BENCHMARKS.md).
