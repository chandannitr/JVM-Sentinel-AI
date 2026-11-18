package dev.jvmsentinel.server;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.history.IncidentHistoryRepository;
import dev.jvmsentinel.history.StoredReport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ApiModels {
    private ApiModels() { }

    record Health(String state, String version) { }
    record ApiError(String code, String message) { }
    record Diagnostics(int corrupt, int unsupported, int oversized, boolean scanTruncated) {
        static Diagnostics from(IncidentHistoryRepository.QueryResult result) {
            return new Diagnostics(result.corrupt(), result.unsupported(), result.oversized(), result.truncated());
        }
    }

    record Page<T>(List<T> items, int page, int limit, int total, Diagnostics diagnostics) {
        Page { items = List.copyOf(items); }
    }

    record ReportHeader(UUID reportId, Instant capturedAt, String scenario, String type, int incidentCount) {
        static ReportHeader from(StoredReport report) {
            return new ReportHeader(report.reportId(), report.evidence().capturedAt(),
                    report.evidence().scenario().name(), report.type().name(), report.evidence().detections().size());
        }
    }

    record Observation(String id, long value, String unit) { }

    record Incident(UUID reportId, String code, String severity, Instant timestamp, String scenario,
                    String rule, List<String> evidenceIds, String observationSummary) {
        Incident { evidenceIds = List.copyOf(evidenceIds); }

        static Incident from(StoredReport report, EvidenceBundle.Detection finding) {
            var ids = finding.rule().support().stream().map(EvidenceBundle.Metric::id).toList();
            var summary = report.evidence().items().stream()
                    .filter(item -> ids.contains(item.metric().id()))
                    .map(item -> item.metric().id() + " = " + item.value() + " " + item.metric().unit())
                    .collect(java.util.stream.Collectors.joining(" · "));
            return new Incident(report.reportId(), finding.rule().name(), finding.severity().name(),
                    finding.timestamp(), report.evidence().scenario().name(), finding.rule().predicate(),
                    ids, summary);
        }
    }

    record Advisory(String status, String incidentCode, String confidence, List<String> evidenceIds,
                    boolean narrativeRedacted) {
        Advisory { evidenceIds = List.copyOf(evidenceIds); }
    }

    record Report(UUID reportId, int schemaVersion, Instant capturedAt, Instant createdAt,
                  String scenario, String origin, String type, String parseStatus,
                  List<Observation> evidence, List<String> notCollected, List<Incident> incidents, Advisory advisory) {
        Report {
            evidence = List.copyOf(evidence);
            notCollected = List.copyOf(notCollected);
            incidents = List.copyOf(incidents);
        }

        static Report from(StoredReport report) {
            var bundle = report.evidence();
            var advisory = report.advisory();
            return new Report(report.reportId(), report.schemaVersion(), bundle.capturedAt(), report.createdAt(),
                    bundle.scenario().name(), report.origin().name(), report.type().name(), "VALID",
                    bundle.items().stream().map(item -> new Observation(item.metric().id(), item.value(),
                            item.metric().unit())).toList(),
                    bundle.notCollected().stream().map(EvidenceBundle.Metric::id).toList(),
                    bundle.detections().stream().map(finding -> Incident.from(report, finding)).toList(),
                    advisory == null ? null : new Advisory(advisory.status().name(),
                            advisory.incident() == null ? null : advisory.incident().name(),
                            advisory.confidence() == null ? null : advisory.confidence().name(),
                            advisory.evidenceIds(), advisory.confidence() != null));
        }
    }

    record Point(Instant capturedAt, UUID reportId, Long value) { }
    record Timeline(String metric, String unit, List<Point> points, Diagnostics diagnostics) {
        Timeline { points = List.copyOf(points); }
    }
    record Summary(int totalReports, int totalIncidents, long recentIncidents, Instant latestCapture,
                   Map<String, Long> byCode, Map<String, Long> bySeverity, Diagnostics diagnostics) {
        Summary {
            byCode = Map.copyOf(byCode);
            bySeverity = Map.copyOf(bySeverity);
        }
    }
}
