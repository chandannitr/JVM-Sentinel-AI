package dev.jvmsentinel.core;

import org.junit.jupiter.api.Test;
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
    void defensivelyCopiesDeadlockedIds() {
        var input = new java.util.ArrayList<>(java.util.List.of(2L));
        var sample = new JvmSnapshot(java.time.Instant.now(), 5, 10, 1, input);
        input.add(3L);
        assertEquals(java.util.List.of(2L), sample.deadlockedThreadIds());
        assertThrows(UnsupportedOperationException.class,
                () -> sample.deadlockedThreadIds().add(4L));
    }
}
