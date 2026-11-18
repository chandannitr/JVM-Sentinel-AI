package dev.jvmsentinel.ai;

import dev.jvmsentinel.detector.Incident;
import dev.jvmsentinel.detector.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EvidenceBundle(Scenario scenario, Instant capturedAt, List<Item> items,
                             List<Detection> detections, List<Metric> notCollected) {
    public EvidenceBundle {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(capturedAt);
        if (items.size() > 24 || detections.size() > Rule.values().length
                || notCollected.size() > Metric.values().length) {
            throw new IllegalArgumentException("Evidence exceeds its size limits");
        }
        items = List.copyOf(items);
        detections = List.copyOf(detections);
        notCollected = List.copyOf(notCollected);
        if (items.stream().map(Item::metric).distinct().count() != items.size()
                || detections.stream().map(Detection::rule).distinct().count() != detections.size()) {
            throw new IllegalArgumentException("Duplicate evidence or detection");
        }
        var observed = items.stream().map(Item::metric).toList();
        if (notCollected.stream().anyMatch(observed::contains)
                || notCollected.stream().distinct().count() != notCollected.size()
                || detections.stream().anyMatch(d -> !observed.containsAll(d.rule().support()))) {
            throw new IllegalArgumentException("Inconsistent evidence bundle");
        }
    }

    public enum Scenario { JVM, DEADLOCK, EXECUTOR, CPU }

    public record Item(Metric metric, long value) {
        public Item {
            Objects.requireNonNull(metric);
            if (value < 0) {
                throw new IllegalArgumentException("Negative metric");
            }
        }
    }

    public record Detection(Rule rule, Severity severity, Instant timestamp) {
        public Detection {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(timestamp);
        }

        public static Detection from(Incident incident) {
            return new Detection(Rule.valueOf(incident.code()), incident.severity(), incident.detectedAt());
        }
    }

    public enum Rule {
        EXECUTOR_SATURATION("Active workers reach the worker limit and queued tasks reach queue capacity",
                Metric.ACTIVE_WORKERS, Metric.MAX_WORKERS, Metric.QUEUE_DEPTH, Metric.QUEUE_CAPACITY),
        PLATFORM_THREAD_DEADLOCK("ThreadMXBean reports a platform-thread lock cycle", Metric.DEADLOCKED_THREADS),
        HEAP_PRESSURE("Heap used divided by known heap maximum meets the detector threshold",
                Metric.HEAP_USED, Metric.HEAP_MAX);

        private final String predicate;
        private final List<Metric> support;

        Rule(String predicate, Metric... support) {
            this.predicate = predicate;
            this.support = List.of(support);
        }

        public String predicate() { return predicate; }
        public List<Metric> support() { return support; }
    }

    public enum Metric {
        HEAP_USED("jmx.heap_used_bytes", "bytes"),
        HEAP_MAX("jmx.heap_max_bytes", "bytes"),
        LIVE_THREADS("jmx.live_threads", "threads"),
        DEADLOCKED_THREADS("jmx.deadlocked_threads", "threads"),
        POOL_SIZE("executor.pool_size", "workers"),
        ACTIVE_WORKERS("executor.active_workers", "workers"),
        MAX_WORKERS("executor.max_workers", "workers"),
        QUEUE_DEPTH("executor.queue_depth", "tasks"),
        QUEUE_CAPACITY("executor.queue_capacity", "tasks"),
        COMPLETED_TASKS("executor.completed_tasks", "tasks"),
        REJECTED_TASKS("executor.rejected_tasks", "tasks"),
        EXECUTOR_ELAPSED("executor.elapsed_ns", "ns"),
        CPU_ELAPSED("cpu.elapsed_ns", "ns"),
        CPU_OPERATIONS("cpu.operations", "operations"),
        CPU_TIME("cpu.thread_cpu_ns", "ns"),
        THREAD_SLEEP("jfr.jdk.ThreadSleep.count", "events"),
        MONITOR_ENTER("jfr.jdk.JavaMonitorEnter.count", "events"),
        GARBAGE_COLLECTION("jfr.jdk.GarbageCollection.count", "events"),
        CPU_LOAD("jfr.jdk.CPULoad.count", "events"),
        EXECUTION_SAMPLE("jfr.jdk.ExecutionSample.count", "events");

        private final String id;
        private final String unit;

        Metric(String id, String unit) {
            this.id = id;
            this.unit = unit;
        }

        public String id() { return id; }
        public String unit() { return unit; }
    }
}
