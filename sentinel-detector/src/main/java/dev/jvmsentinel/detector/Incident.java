package dev.jvmsentinel.detector;

import dev.jvmsentinel.core.ThreadEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Evidence and a rule outcome; the AI layer must not invent sensor readings. */
public record Incident(
        String code,
        Severity severity,
        String title,
        String explanation,
        List<String> evidence,
        Instant detectedAt,
        List<ThreadEvidence> threadDetails) {
    public Incident(String code, Severity severity, String title, String explanation,
                    List<String> evidence, Instant detectedAt) {
        this(code, severity, title, explanation, evidence, detectedAt, List.of());
    }

    public Incident {
        Objects.requireNonNull(code);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(title);
        Objects.requireNonNull(explanation);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(detectedAt);
        evidence = List.copyOf(evidence);
        threadDetails = List.copyOf(threadDetails);
    }
}
