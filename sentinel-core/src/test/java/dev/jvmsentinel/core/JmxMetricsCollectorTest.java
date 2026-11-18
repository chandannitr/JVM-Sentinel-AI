package dev.jvmsentinel.core;

import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class JmxMetricsCollectorTest {
    @Test
    void collectsCurrentJvmMetrics() {
        var sample = new JmxMetricsCollector().collect();
        assertNotNull(sample.capturedAt());
        assertTrue(sample.heapUsedBytes() >= 0);
        assertTrue(sample.liveThreadCount() > 0);
        assertNotNull(sample.deadlockedThreadIds());
    }

    @Test
    void boundsDeadlockDetailsAndStackDepthWhileKeepingAllReportedIds() {
        var realThreads = ManagementFactory.getThreadMXBean();
        long currentId = Thread.currentThread().threadId();
        long[] reportedIds = new long[JmxMetricsCollector.MAX_THREAD_DETAILS + 2];
        Arrays.fill(reportedIds, currentId);
        reportedIds[0] = Long.MAX_VALUE; // An unavailable ID sorts behind the bounded selection.
        var threads = (ThreadMXBean) Proxy.newProxyInstance(ThreadMXBean.class.getClassLoader(),
                new Class<?>[] {ThreadMXBean.class}, (object, method, args) -> switch (method.getName()) {
                    case "findDeadlockedThreads" -> reportedIds;
                    case "getThreadCount" -> realThreads.getThreadCount();
                    case "getThreadInfo" -> {
                        assertEquals(JmxMetricsCollector.MAX_THREAD_DETAILS, ((long[]) args[0]).length);
                        assertEquals(JmxMetricsCollector.MAX_STACK_DEPTH, args[1]);
                        yield realThreads.getThreadInfo((long[]) args[0], (int) args[1]);
                    }
                    default -> throw new AssertionError("Unexpected MXBean call: " + method);
                });

        var snapshot = new JmxMetricsCollector(ManagementFactory.getMemoryMXBean(), threads).collect();

        assertEquals(reportedIds.length, snapshot.deadlockedThreadIds().size());
        assertEquals(JmxMetricsCollector.MAX_THREAD_DETAILS, snapshot.deadlockedThreadDetails().size());
        for (var thread : snapshot.deadlockedThreadDetails()) {
            assertEquals(currentId, thread.threadId());
            assertFalse(thread.stackTrace().isEmpty());
            assertTrue(thread.stackTrace().size() <= JmxMetricsCollector.MAX_STACK_DEPTH);
            assertThrows(UnsupportedOperationException.class, () -> thread.stackTrace().clear());
        }
        assertThrows(UnsupportedOperationException.class, () -> snapshot.deadlockedThreadDetails().clear());
    }

    @Test
    void defensivelyCopiesDeadlockedIds() {
        var input = new java.util.ArrayList<>(java.util.List.of(2L));
        var sample = new JvmSnapshot(java.time.Instant.now(), 5, 10, 1, input);
        input.add(3L);
        assertEquals(java.util.List.of(2L), sample.deadlockedThreadIds());
        assertThrows(UnsupportedOperationException.class,
                () -> sample.deadlockedThreadIds().add(4L));
    }
}
