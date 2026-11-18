package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.AdvisoryReport;
import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.Json;
import dev.jvmsentinel.ai.OllamaIncidentExplainer;
import dev.jvmsentinel.detector.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReportJson {
    private ReportJson() { }

    static String write(StoredReport report) {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", report.schemaVersion());
        data.put("reportId", report.reportId().toString());
        data.put("createdAt", report.createdAt().toString());
        data.put("capturedAt", report.evidence().capturedAt().toString());
        data.put("scenario", report.evidence().scenario().name());
        data.put("origin", report.origin().name());
        data.put("type", report.type().name());
        data.put("evidence", report.evidence().items().stream().map(item ->
                Map.of("id", item.metric().id(), "value", item.value(), "unit", item.metric().unit())).toList());
        data.put("incidents", report.evidence().detections().stream().map(detection ->
                Map.of("code", detection.rule().name(), "severity", detection.severity().name(),
                        "timestamp", detection.timestamp().toString(), "rule", detection.rule().predicate(),
                        "evidenceIds", detection.rule().support().stream().map(EvidenceBundle.Metric::id).toList())).toList());
        data.put("notCollected", report.evidence().notCollected().stream().map(EvidenceBundle.Metric::id).toList());
        if (report.advisory() != null) {
            var advisory = report.advisory();
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("status", advisory.status().name());
            metadata.put("incident", advisory.incident() == null ? null : advisory.incident().name());
            metadata.put("confidence", advisory.confidence() == null ? null : advisory.confidence().name());
            metadata.put("evidenceIds", advisory.evidenceIds());
            data.put("advisory", metadata);
        }
        return Json.write(data);
    }

    static StoredReport read(String text) {
        var data = Json.object(text);
        if (number(data.get("schemaVersion")) != StoredReport.VERSION) throw new UnsupportedVersion();
        var bundle = new EvidenceBundle(EvidenceBundle.Scenario.valueOf(string(data.get("scenario"))),
                Instant.parse(string(data.get("capturedAt"))),
                list(data.get("evidence"), 24).stream().map(value -> {
                    var item = object(value);
                    var metric = metric(item.get("id"));
                    if (!metric.unit().equals(item.get("unit"))) throw invalid();
                    return new EvidenceBundle.Item(metric, number(item.get("value")));
                }).toList(),
                list(data.get("incidents"), EvidenceBundle.Rule.values().length).stream().map(value -> {
                    var incident = object(value);
                    var rule = EvidenceBundle.Rule.valueOf(string(incident.get("code")));
                    if (!rule.predicate().equals(incident.get("rule")) || !rule.support().stream()
                            .map(EvidenceBundle.Metric::id).toList().equals(incident.get("evidenceIds"))) {
                        throw invalid();
                    }
                    return new EvidenceBundle.Detection(rule, Severity.valueOf(string(incident.get("severity"))),
                            Instant.parse(string(incident.get("timestamp"))));
                }).toList(),
                list(data.get("notCollected"), EvidenceBundle.Metric.values().length).stream()
                        .map(ReportJson::metric).toList());
        var report = new StoredReport(UUID.fromString(string(data.get("reportId"))), StoredReport.VERSION,
                Instant.parse(string(data.get("createdAt"))),
                StoredReport.Origin.valueOf(string(data.get("origin"))), bundle, advisory(data.get("advisory")));
        if (!report.type().name().equals(string(data.get("type")))) throw invalid();
        return report;
    }

    private static StoredReport.Advisory advisory(Object value) {
        if (value == null) return null;
        var data = object(value);
        return new StoredReport.Advisory(OllamaIncidentExplainer.Status.valueOf(string(data.get("status"))),
                data.get("incident") == null ? null : EvidenceBundle.Rule.valueOf(string(data.get("incident"))),
                data.get("confidence") == null ? null : AdvisoryReport.Confidence.valueOf(string(data.get("confidence"))),
                list(data.get("evidenceIds"), 12).stream().map(ReportJson::string).toList());
    }

    private static EvidenceBundle.Metric metric(Object value) {
        String id = string(value);
        return Arrays.stream(EvidenceBundle.Metric.values()).filter(metric -> metric.id().equals(id))
                .findFirst().orElseThrow(ReportJson::invalid);
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private static List<?> list(Object value, int maximum) {
        if (value instanceof List<?> list && list.size() <= maximum) return list;
        throw invalid();
    }

    private static String string(Object value) {
        if (value instanceof String text && text.length() <= 128) return text;
        throw invalid();
    }

    private static long number(Object value) {
        if (value instanceof BigDecimal number) return number.longValueExact();
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid stored report");
    }

    static final class UnsupportedVersion extends IllegalArgumentException { }
}
