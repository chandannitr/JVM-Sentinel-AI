package dev.jvmsentinel.cli;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CpuLoadScenario {
    static final int DEFAULT_SECONDS = 2;
    static final int MAX_SECONDS = 5;
    private static final int BATCH_SIZE = 1024;

    private CpuLoadScenario() { }

    static Result run(Duration duration) throws Exception {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofSeconds(MAX_SECONDS)) > 0) {
            throw new IllegalArgumentException("CPU workload duration must be positive and at most 5 seconds");
        }
        var task = new FutureTask<>(() -> compute(duration, ManagementFactory.getThreadMXBean()));
        Thread worker = Thread.ofPlatform().daemon().name("sentinel-cpu-demo").start(task);
        try {
            return task.get(duration.toNanos() + TimeUnit.SECONDS.toNanos(1), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("CPU demo exceeded its execution budget", timeout);
        } finally {
            task.cancel(true);
            if (!worker.join(Duration.ofSeconds(1))) {
                throw new IllegalStateException("CPU demo worker did not terminate");
            }
        }
    }

    private static Result compute(Duration duration, ThreadMXBean threads) throws InterruptedException {
        long cpuBefore = currentCpuNanos(threads);
        long started = System.nanoTime();
        long deadline = started + duration.toNanos();
        long operations = 0;
        long state = 1;
        do {
            if (Thread.interrupted()) {
                throw new InterruptedException("CPU demo interrupted");
            }
            for (int index = 0; index < BATCH_SIZE; index++) {
                state = Long.rotateLeft(state ^ (state * 0x9e3779b97f4a7c15L), 17) + index;
            }
            operations += BATCH_SIZE;
        } while (System.nanoTime() - deadline < 0);
        long elapsed = System.nanoTime() - started;
        long cpuAfter = currentCpuNanos(threads);
        OptionalLong cpu = cpuBefore >= 0 && cpuAfter >= cpuBefore
                ? OptionalLong.of(cpuAfter - cpuBefore) : OptionalLong.empty();
        return new Result(elapsed, operations, cpu, state);
    }

    private static long currentCpuNanos(ThreadMXBean threads) {
        return threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                ? threads.getCurrentThreadCpuTime() : -1;
    }

    record Result(long elapsedNanos, long operations, OptionalLong threadCpuNanos, long checksum) { }
}
