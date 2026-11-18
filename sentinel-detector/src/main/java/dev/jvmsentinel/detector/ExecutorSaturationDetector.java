package dev.jvmsentinel.detector;

import dev.jvmsentinel.core.ExecutorSnapshot;

import java.util.List;
import java.util.Optional;

public final class ExecutorSaturationDetector {
    public Optional<Incident> detect(ExecutorSnapshot snapshot) {
        if (snapshot.workerLimit() <= 0 || snapshot.queueCapacity() <= 0
                || snapshot.activeWorkers() < snapshot.workerLimit()
                || snapshot.queuedTasks() < snapshot.queueCapacity()) {
            return Optional.empty();
        }
        return Optional.of(new Incident("EXECUTOR_SATURATION", Severity.WARNING,
                "Executor workers and queue are at capacity",
                "All worker slots are occupied and no queue slots are available at capture time.",
                List.of("Rule: active workers >= worker limit AND queued tasks >= queue capacity",
                        "Pool size: " + snapshot.poolSize(),
                        "Active workers: " + snapshot.activeWorkers() + "/" + snapshot.workerLimit(),
                        "Queued tasks: " + snapshot.queuedTasks() + "/" + snapshot.queueCapacity(),
                        "Completed tasks: " + snapshot.completedTasks(),
                        "Rejected tasks: " + snapshot.rejectedTasks(),
                        "Elapsed at capture (ns): " + snapshot.elapsedNanos()),
                snapshot.capturedAt()));
    }
}
