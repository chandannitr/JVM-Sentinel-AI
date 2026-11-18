package dev.jvmsentinel.cli;

import dev.jvmsentinel.core.ExecutorSnapshot;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ExecutorSaturationScenario {
    static final String WORKER_PREFIX = "sentinel-executor-demo-";
    private static final int WORKERS = 2;
    private static final int CAPACITY = 4;

    private ExecutorSaturationScenario() { }

    static ExecutorSnapshot run() throws InterruptedException {
        long started = System.nanoTime();
        var release = new CountDownLatch(1);
        var active = new CountDownLatch(WORKERS);
        var rejected = new AtomicLong();
        var executor = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CAPACITY),
                Thread.ofPlatform().daemon().name(WORKER_PREFIX, 0).factory(),
                (task, pool) -> {
                    rejected.incrementAndGet();
                    throw new RejectedExecutionException("Demo executor is at capacity");
                });
        try {
            for (int i = 0; i < WORKERS; i++) {
                executor.execute(() -> {
                    active.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            if (!active.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Executor demo workers did not start");
            }
            for (int i = 0; i < CAPACITY; i++) {
                executor.execute(() -> { });
            }
            try {
                executor.execute(() -> { });
                throw new IllegalStateException("Expected the full executor to reject one task");
            } catch (RejectedExecutionException expected) {
                return ExecutorSnapshot.capture(executor, rejected.get(), started);
            }
        } finally {
            release.countDown();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Executor demo workers did not terminate");
                    }
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                throw interrupted;
            }
        }
    }
}
