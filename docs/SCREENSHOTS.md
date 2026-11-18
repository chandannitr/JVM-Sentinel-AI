# Screenshot and demo-proof guide

Screenshots are convincing only when they show **real application output** from the published commit. The verification used the live dashboard in headless Chrome. Publish screenshots only after visually reviewing the captures.

## Recommended captures

1. **Terminal — executor saturation:** include the actual command, rule code, pool size, active workers, queue depth, and rejection count. Remove user/host names and absolute recording paths.
2. **Terminal — CPU + JFR:** include actual CPU timing and `jdk.ExecutionSample` count; explain zeros for other event types.
3. **Terminal — test run:** show `mvn test` result, number of tests and any failures for the exact commit.
4. **AI report (after a successful live model response):** show measured facts separate from model hypotheses and valid evidence references.
5. **Local dashboard:** show a real persisted incident and a real API response; no mock screenshots or invented metrics.
6. **GitHub Actions:** include the workflow link/badge only after the repository's own workflow completes successfully.

## File naming suggestion

```text
docs/assets/
  executor-saturation.png
  jfr-cpu-workload.png
  tests-passing.png
  ai-evidence-report.png        # only if verified
  dashboard-incident.png        # only if implemented and verified
```

Add each to the README with a relative image link once the actual file exists, e.g.:

```markdown
![Measured executor saturation](docs/assets/executor-saturation.png)
```

**Do not** add broken placeholder image links. Keep individual screenshots reasonably sized and inspect them for sensitive data before publishing.
