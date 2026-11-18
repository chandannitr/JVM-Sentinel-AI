package dev.jvmsentinel.core;

import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;

/** Executor observations captured at one point in the workload. */
public record ExecutorSnapshot(Instant capturedAt, int poolSize, int workerLimit, int activeWorkers,
                               int queuedTasks, int queueCapacity, long completedTasks,
                               long rejectedTasks, long elapsedNanos) {
    /** Capture while the workload holds the queue stable. */
    public static ExecutorSnapshot capture(ThreadPoolExecutor executor, long rejectedTasks, long startedNanos) {
        var queue = executor.getQueue();
        int queuedTasks = queue.size();
        int queueCapacity = queuedTasks + queue.remainingCapacity();
        return new ExecutorSnapshot(Instant.now(), executor.getPoolSize(), executor.getMaximumPoolSize(),
                executor.getActiveCount(), queuedTasks, queueCapacity,
                executor.getCompletedTaskCount(), rejectedTasks, System.nanoTime() - startedNanos);
    }
}
