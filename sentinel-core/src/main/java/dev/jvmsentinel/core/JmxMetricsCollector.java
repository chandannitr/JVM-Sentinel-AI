package dev.jvmsentinel.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Samples this JVM via JMX. No agent injection or external JVM attachment. */
public final class JmxMetricsCollector implements JvmMetricsCollector {
    static final int MAX_THREAD_DETAILS = 8;
    static final int MAX_STACK_DEPTH = 12;

    private final MemoryMXBean memory;
    private final ThreadMXBean threads;

    public JmxMetricsCollector() {
        this(ManagementFactory.getMemoryMXBean(), ManagementFactory.getThreadMXBean());
    }

    public JmxMetricsCollector(MemoryMXBean memory, ThreadMXBean threads) {
        this.memory = Objects.requireNonNull(memory);
        this.threads = Objects.requireNonNull(threads);
    }

    @Override
    public JvmSnapshot collect() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long[] ids;
        try {
            ids = threads.findDeadlockedThreads();
        } catch (UnsupportedOperationException exception) {
            ids = null; // The JVM does not support this deadlock inspection operation.
        }
        List<Long> deadlocked = ids == null ? List.of() : Arrays.stream(ids).boxed().toList();
        return new JvmSnapshot(
                Instant.now(), heap.getUsed(), heap.getMax(),
                threads.getThreadCount(), deadlocked, captureThreadDetails(ids));
    }

    private List<ThreadEvidence> captureThreadDetails(long[] ids) {
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        long[] selected = Arrays.stream(ids).sorted().limit(MAX_THREAD_DETAILS).toArray();
        return Arrays.stream(threads.getThreadInfo(selected, MAX_STACK_DEPTH))
                .filter(Objects::nonNull)
                .map(JmxMetricsCollector::threadEvidence)
                .toList();
    }

    private static ThreadEvidence threadEvidence(ThreadInfo thread) {
        return new ThreadEvidence(thread.getThreadId(), thread.getThreadName(), thread.getThreadState(),
                thread.getLockName(), thread.getLockOwnerId(), thread.getLockOwnerName(),
                List.of(thread.getStackTrace()));
    }
}
