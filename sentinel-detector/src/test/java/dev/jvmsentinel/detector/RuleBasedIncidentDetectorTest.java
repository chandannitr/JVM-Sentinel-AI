package dev.jvmsentinel.detector;

import dev.jvmsentinel.core.JvmSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleBasedIncidentDetectorTest {
    private final RuleBasedIncidentDetector detector = new RuleBasedIncidentDetector();

    @Test
    void detectsPlatformThreadDeadlock() {
        var snapshot = new JvmSnapshot(Instant.now(), 10, 100, 6, List.of(12L, 13L));
        var incidents = detector.detect(snapshot);
        assertEquals(1, incidents.size());
        assertEquals("PLATFORM_THREAD_DEADLOCK", incidents.getFirst().code());
        assertEquals(Severity.CRITICAL, incidents.getFirst().severity());
    }

    @Test
    void warnsOnHighHeapUsageButNotOnLowHeapUsage() {
        assertEquals("HEAP_PRESSURE", detector.detect(
                new JvmSnapshot(Instant.now(), 90, 100, 6, List.of())).getFirst().code());
        assertTrue(detector.detect(
                new JvmSnapshot(Instant.now(), 10, 100, 6, List.of())).isEmpty());
    }

    @Test
    void unknownHeapMaximumDoesNotTriggerFalseAlarm() {
        assertTrue(detector.detect(
                new JvmSnapshot(Instant.now(), 90, -1, 6, List.of())).isEmpty());
    }

    @Test
    void invalidThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RuleBasedIncidentDetector(1.5));
    }
}
