package dev.jvmsentinel.cli;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.EvidenceBundle.Item;
import dev.jvmsentinel.ai.EvidenceBundle.Metric;
import dev.jvmsentinel.core.ExecutorSnapshot;
import dev.jvmsentinel.core.JvmSnapshot;
import dev.jvmsentinel.detector.Incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.jvmsentinel.ai.EvidenceBundle.Metric.*;

final class CliEvidence {
    private static final Map<Metric, String> JFR_EVENTS = Map.of(
            THREAD_SLEEP, "jdk.ThreadSleep", MONITOR_ENTER, "jdk.JavaMonitorEnter",
            GARBAGE_COLLECTION, "jdk.GarbageCollection", CPU_LOAD, "jdk.CPULoad",
            EXECUTION_SAMPLE, "jdk.ExecutionSample");

    private CliEvidence() { }

    static EvidenceBundle executor(ExecutorSnapshot snapshot, List<Incident> incidents) {
        return bundle(EvidenceBundle.Scenario.EXECUTOR, snapshot.capturedAt(), List.of(
                new Item(POOL_SIZE, snapshot.poolSize()),
                new Item(ACTIVE_WORKERS, snapshot.activeWorkers()),
                new Item(MAX_WORKERS, snapshot.workerLimit()),
                new Item(QUEUE_DEPTH, snapshot.queuedTasks()),
                new Item(QUEUE_CAPACITY, snapshot.queueCapacity()),
                new Item(COMPLETED_TASKS, snapshot.completedTasks()),
                new Item(REJECTED_TASKS, snapshot.rejectedTasks()),
                new Item(EXECUTOR_ELAPSED, snapshot.elapsedNanos())), incidents, List.of());
    }

    static EvidenceBundle cpu(CpuLoadScenario.Result result) {
        var items = new ArrayList<>(List.of(new Item(CPU_ELAPSED, result.elapsedNanos()),
                new Item(CPU_OPERATIONS, result.operations())));
        result.threadCpuNanos().ifPresent(value -> items.add(new Item(CPU_TIME, value)));
        return bundle(EvidenceBundle.Scenario.CPU, Instant.now(), items, List.of(),
                result.threadCpuNanos().isEmpty() ? List.of(CPU_TIME) : List.of());
    }

    static EvidenceBundle jvm(JvmSnapshot snapshot, List<Incident> incidents) {
        var items = new ArrayList<>(List.of(new Item(HEAP_USED, snapshot.heapUsedBytes()),
                new Item(LIVE_THREADS, snapshot.liveThreadCount()),
                new Item(DEADLOCKED_THREADS, snapshot.deadlockedThreadIds().size())));
        if (snapshot.heapMaxBytes() > 0) {
            items.add(new Item(HEAP_MAX, snapshot.heapMaxBytes()));
        }
        var scenario = snapshot.hasDeadlock() ? EvidenceBundle.Scenario.DEADLOCK : EvidenceBundle.Scenario.JVM;
        return bundle(scenario, snapshot.capturedAt(), items, incidents,
                snapshot.heapMaxBytes() > 0 ? List.of() : List.of(HEAP_MAX));
    }

    static EvidenceBundle withJfr(EvidenceBundle base, Map<String, Long> counts) {
        if (base == null) {
            base = bundle(EvidenceBundle.Scenario.JVM, Instant.now(), List.of(), List.of(), List.of());
        }
        var items = new ArrayList<>(base.items());
        var missing = new ArrayList<>(base.notCollected());
        for (var metric : Metric.values()) {
            String event = JFR_EVENTS.get(metric);
            if (event == null) continue;
            Long count = counts.get(event);
            if (count == null) {
                missing.add(metric);
            } else {
                items.add(new Item(metric, count));
            }
        }
        return new EvidenceBundle(base.scenario(), base.capturedAt(), items, base.detections(), missing);
    }

    private static EvidenceBundle bundle(EvidenceBundle.Scenario scenario, Instant timestamp, List<Item> items,
                                         List<Incident> incidents, List<Metric> missing) {
        return new EvidenceBundle(scenario, timestamp, items,
                incidents.stream().map(EvidenceBundle.Detection::from).toList(), missing);
    }
}
