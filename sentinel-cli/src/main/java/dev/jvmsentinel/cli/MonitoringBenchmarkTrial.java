package dev.jvmsentinel.cli;

import dev.jvmsentinel.core.AsyncMetricsPipeline;
import dev.jvmsentinel.core.JmxMetricsCollector;
import dev.jvmsentinel.detector.RuleBasedIncidentDetector;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/** One fixed-work trial per JVM. Launched by benchmark-overhead.sh. */
public final class MonitoringBenchmarkTrial {
    private MonitoringBenchmarkTrial() { }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 4 || (!args[0].equals("off") && !args[0].equals("on"))) {
            throw new IllegalArgumentException("Expected off|on, warmup operations, trial operations, interval ms");
        }
        boolean monitored = args[0].equals("on");
        long warmup = bounded(args[1], 1, 1_000_000_000);
        long operations = bounded(args[2], 1, 2_000_000_000);
        long interval = bounded(args[3], 1, 1000);

        var threads = ManagementFactory.getThreadMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var os = ManagementFactory.getOperatingSystemMXBean();
        var process = os instanceof com.sun.management.OperatingSystemMXBean supported ? supported : null;
        boolean threadCpu = threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled();

        long seed = compute(warmup, 1);
        var findings = new AtomicLong();
        var detector = new RuleBasedIncidentDetector();
        long elapsed;
        long checksum;
        long threadBefore;
        long threadAfter;
        long processBefore;
        long processAfter;
        long heapBefore;
        long heapAfter;
        AsyncMetricsPipeline.PipelineStats stats;
        // Both modes construct the same objects. Only ON starts the sampler.
        var sampler = new AsyncMetricsPipeline(new JmxMetricsCollector(),
                snapshot -> findings.addAndGet(detector.detect(snapshot).size()), 100_000, interval, 4);
        try (sampler) {
            heapBefore = memory.getHeapMemoryUsage().getUsed();
            threadBefore = threadCpu ? threads.getCurrentThreadCpuTime() : -1;
            processBefore = process == null ? -1 : process.getProcessCpuTime();
            long started = System.nanoTime();
            if (monitored) sampler.start();
            checksum = compute(operations, seed);
            elapsed = System.nanoTime() - started;
            processAfter = process == null ? -1 : process.getProcessCpuTime();
            threadAfter = threadCpu ? threads.getCurrentThreadCpuTime() : -1;
            heapAfter = memory.getHeapMemoryUsage().getUsed();
        }
        stats = sampler.stats();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Trial interrupted");
        if (elapsed <= 0 || stats.collectionFailures() != 0 || stats.processingFailures() != 0
                || stats.accepted() != stats.processed() || (monitored && stats.processed() == 0)) {
            throw new IllegalStateException("Invalid timing or incomplete sampler trial");
        }
        System.out.printf("%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%016x%n", operations, elapsed,
                delta(threadBefore, threadAfter), delta(processBefore, processAfter), heapBefore, heapAfter,
                stats.accepted(), stats.processed(), stats.dropped(), stats.retryAttempts(), findings.get(), checksum);
    }

    private static String delta(long start, long end) {
        return start >= 0 && end >= start ? Long.toString(end - start) : "";
    }

    private static long bounded(String value, long minimum, long maximum) {
        long parsed = Long.parseLong(value);
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("Trial setting out of range");
        return parsed;
    }

    private static long compute(long operations, long state) throws InterruptedException {
        for (long index = 0; index < operations; index++) {
            if ((index & 4095) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Trial interrupted");
            }
            state = Long.rotateLeft(state ^ (state * 0x9e3779b97f4a7c15L), 17) + index;
        }
        return state;
    }
}
