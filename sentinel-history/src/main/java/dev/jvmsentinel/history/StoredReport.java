package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.AdvisoryReport;
import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.OllamaIncidentExplainer;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StoredReport(UUID reportId, int schemaVersion, Instant createdAt, Origin origin,
                           EvidenceBundle evidence, Advisory advisory) {
    public static final int VERSION = 1;

    public StoredReport {
        Objects.requireNonNull(reportId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(evidence);
        if (schemaVersion != VERSION || evidence.items().isEmpty()) {
            throw new IllegalArgumentException("Unsupported or empty report");
        }
        if (advisory != null && advisory.status() == OllamaIncidentExplainer.Status.VALID) {
            var ids = evidence.items().stream().map(item -> item.metric().id()).toList();
            boolean detected = evidence.detections().stream().anyMatch(d -> d.rule() == advisory.incident());
            if (!ids.containsAll(advisory.evidenceIds())
                    || (advisory.incident() == null ? !evidence.detections().isEmpty() : !detected)
                    || (advisory.incident() != null && advisory.incident().support().stream()
                        .map(EvidenceBundle.Metric::id).noneMatch(advisory.evidenceIds()::contains))) {
                throw new IllegalArgumentException("Invalid advisory references");
            }
        }
    }

    public Type type() {
        return evidence.detections().isEmpty() ? Type.OBSERVATION : Type.INCIDENT;
    }

    public enum Origin { CLI, SERVER }
    public enum Type { INCIDENT, OBSERVATION }

    /** Persists structured advisory metadata. Free-form model prose stays in the CLI. */
    public record Advisory(OllamaIncidentExplainer.Status status, EvidenceBundle.Rule incident,
                           AdvisoryReport.Confidence confidence, List<String> evidenceIds) {
        public Advisory {
            Objects.requireNonNull(status);
            evidenceIds = List.copyOf(evidenceIds);
            if (status == OllamaIncidentExplainer.Status.VALID) {
                Objects.requireNonNull(confidence);
                if (evidenceIds.isEmpty() || evidenceIds.size() > 12
                        || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
                    throw new IllegalArgumentException("Invalid advisory references");
                }
            } else if (incident != null || confidence != null || !evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("Failed advisory has no generated metadata");
            }
        }

        public static Advisory from(OllamaIncidentExplainer.Result result) {
            if (result == null) return null;
            var report = result.report();
            return report == null ? new Advisory(result.status(), null, null, List.of())
                    : new Advisory(result.status(), report.incident(), report.confidence(), report.evidenceIds());
        }
    }
}
