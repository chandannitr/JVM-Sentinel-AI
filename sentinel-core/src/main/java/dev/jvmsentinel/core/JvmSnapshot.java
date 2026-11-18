package dev.jvmsentinel.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An immutable snapshot of measured JVM state, not an AI inference. */
public record JvmSnapshot(
        Instant capturedAt,
        long heapUsedBytes,
        long heapMaxBytes,
        int liveThreadCount,
        List<Long> deadlockedThreadIds,
        List<ThreadEvidence> deadlockedThreadDetails) {

    public JvmSnapshot(Instant capturedAt, long heapUsedBytes, long heapMaxBytes,
                       int liveThreadCount, List<Long> deadlockedThreadIds) {
        this(capturedAt, heapUsedBytes, heapMaxBytes, liveThreadCount, deadlockedThreadIds, List.of());
    }

    public JvmSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(deadlockedThreadIds, "deadlockedThreadIds");
        if (heapUsedBytes < 0 || liveThreadCount < 0) {
            throw new IllegalArgumentException("Metrics cannot be negative");
        }
        deadlockedThreadIds = List.copyOf(deadlockedThreadIds);
        deadlockedThreadDetails = List.copyOf(deadlockedThreadDetails);
    }

    /** Unknown or unspecified max heap returns 0.0 rather than a fabricated ratio. */
    public double heapUsageRatio() {
        return heapMaxBytes > 0 ? (double) heapUsedBytes / heapMaxBytes : 0.0;
    }

    public boolean hasDeadlock() {
        return !deadlockedThreadIds.isEmpty();
    }
}
