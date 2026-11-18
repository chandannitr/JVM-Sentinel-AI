package dev.jvmsentinel.detector;

import dev.jvmsentinel.core.ExecutorSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorSaturationDetectorTest {
    @Test
    void requiresBothWorkerAndQueueCapacityEvenIfTasksWereRejected() {
        var detector = new ExecutorSaturationDetector();
        assertTrue(detector.detect(snapshot(1, 4, 1)).isEmpty());
        assertTrue(detector.detect(snapshot(2, 3, 1)).isEmpty());
        var result = detector.detect(snapshot(2, 4, 0)).orElseThrow();
        assertEquals("EXECUTOR_SATURATION", result.code());
        assertEquals(Severity.WARNING, result.severity());
        assertEquals(Instant.EPOCH, result.detectedAt());
        assertTrue(result.evidence().contains("Rejected tasks: 0"));
    }

    private static ExecutorSnapshot snapshot(int active, int queued, long rejected) {
        return new ExecutorSnapshot(Instant.EPOCH, 2, 2, active, queued, 4, 0, rejected, 50);
    }
}
