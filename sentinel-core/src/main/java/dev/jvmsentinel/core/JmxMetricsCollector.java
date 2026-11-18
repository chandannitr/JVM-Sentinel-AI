package dev.jvmsentinel.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Samples this JVM via JMX. No agent injection or external JVM attachment. */
public final class JmxMetricsCollector implements JvmMetricsCollector {
    private final MemoryMXBean memory;
    private final ThreadMXBean threads;

    public JmxMetricsCollector() {
        this(ManagementFactory.getMemoryMXBean(), ManagementFactory.getThreadMXBean());
    }

    public JmxMetricsCollector(MemoryMXBean memory, ThreadMXBean threads) {
        this.memory = java.util.Objects.requireNonNull(memory);
        this.threads = java.util.Objects.requireNonNull(threads);
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
                threads.getThreadCount(), deadlocked);
    }
}
