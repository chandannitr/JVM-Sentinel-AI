package dev.jvmsentinel.detector;

import dev.jvmsentinel.core.JvmSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Deterministic initial rules. Heap pressure is a warning, not proof of a leak. */
public final class RuleBasedIncidentDetector {
    private final double heapPressureThreshold;

    public RuleBasedIncidentDetector() {
        this(0.85);
    }

    public RuleBasedIncidentDetector(double heapPressureThreshold) {
        if (!Double.isFinite(heapPressureThreshold)
                || heapPressureThreshold <= 0.0 || heapPressureThreshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be within (0.0, 1.0]");
        }
        this.heapPressureThreshold = heapPressureThreshold;
    }

    public List<Incident> detect(JvmSnapshot snapshot) {
        List<Incident> incidents = new ArrayList<>();
        if (snapshot.hasDeadlock()) {
            incidents.add(new Incident(
                    "PLATFORM_THREAD_DEADLOCK", Severity.CRITICAL,
                    "Platform-thread deadlock detected",
                    "ThreadMXBean identified a circular lock dependency; inspect thread dumps.",
                    List.of("Deadlocked thread IDs: " + snapshot.deadlockedThreadIds()),
                    snapshot.capturedAt(), snapshot.deadlockedThreadDetails()));
        }
        if (snapshot.heapMaxBytes() > 0 && snapshot.heapUsageRatio() >= heapPressureThreshold) {
            incidents.add(new Incident(
                    "HEAP_PRESSURE", Severity.WARNING,
                    "High Java heap occupancy",
                    "Heap occupancy exceeded the configured threshold; this alone does not prove a leak.",
                    List.of("Heap used bytes: " + snapshot.heapUsedBytes(),
                            "Heap max bytes: " + snapshot.heapMaxBytes(),
                            "Heap occupancy: " + Math.round(snapshot.heapUsageRatio() * 100) + "%"),
                    snapshot.capturedAt()));
        }
        return List.copyOf(incidents);
    }
}
