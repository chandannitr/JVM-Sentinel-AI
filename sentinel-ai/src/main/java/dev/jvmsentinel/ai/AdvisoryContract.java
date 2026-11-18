package dev.jvmsentinel.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class AdvisoryContract {
    private static final int MAX_TEXT = 320;
    private static final int MAX_STEPS = 4;
    private static final int MAX_REFERENCES = 12;
    private static final Pattern QUANTITY = Pattern.compile(
            "\\p{N}|[%‰]|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|"
                    + "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|"
                    + "thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|billion|"
                    + "trillion|percent|half|quarter|twice|double|triple)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Object> TEXT =
            Map.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT);
    private static final List<String> FIELDS = List.of("incidentCode", "summary", "likelyCause", "confidence",
            "evidenceIds", "alternativeHypotheses", "recommendations", "limitations");

    private AdvisoryContract() { }

    static Map<String, Object> schema(EvidenceBundle bundle) {
        var properties = new LinkedHashMap<String, Object>();
        if (bundle.detections().isEmpty()) {
            properties.put("incidentCode", Map.of("type", "null"));
        } else {
            properties.put("incidentCode", Map.of("type", "string", "enum",
                    bundle.detections().stream().map(d -> d.rule().name()).toList()));
        }
        properties.put("summary", TEXT);
        properties.put("likelyCause", TEXT);
        properties.put("confidence", Map.of("type", "string", "enum", List.of("low", "medium", "high", "unknown")));
        properties.put("evidenceIds", Map.of("type", "array", "minItems", 1, "maxItems", MAX_REFERENCES,
                "uniqueItems", true, "items", Map.of("type", "string", "enum", ids(bundle))));
        properties.put("alternativeHypotheses", listSchema(0));
        properties.put("recommendations", listSchema(1));
        properties.put("limitations", listSchema(1));
        return Map.of("type", "object", "additionalProperties", false, "required", FIELDS, "properties", properties);
    }

    private static Map<String, Object> listSchema(int min) {
        return Map.of("type", "array", "minItems", min, "maxItems", MAX_STEPS, "items", TEXT);
    }

    static AdvisoryReport parse(String json, EvidenceBundle bundle) {
        var object = Json.object(json);
        if (object.size() != FIELDS.size() || !object.keySet().containsAll(FIELDS)) throw invalid();

        EvidenceBundle.Rule incident = null;
        Object code = object.get("incidentCode");
        if (code != null) {
            incident = bundle.detections().stream().map(EvidenceBundle.Detection::rule)
                    .filter(rule -> rule.name().equals(code)).findFirst().orElseThrow(AdvisoryContract::ungrounded);
        } else if (!bundle.detections().isEmpty()) {
            throw ungrounded();
        }

        var references = strings(object.get("evidenceIds"), 1, MAX_REFERENCES);
        if (!ids(bundle).containsAll(references) || new HashSet<>(references).size() != references.size()
                || (incident != null && incident.support().stream().map(EvidenceBundle.Metric::id)
                        .noneMatch(references::contains))) {
            throw ungrounded();
        }
        var confidence = AdvisoryReport.Confidence.valueOf(text(object.get("confidence")));
        return new AdvisoryReport(incident, advisory(object.get("summary")), advisory(object.get("likelyCause")),
                confidence, references,
                advisories(object.get("alternativeHypotheses"), 0),
                advisories(object.get("recommendations"), 1), advisories(object.get("limitations"), 1));
    }

    private static List<String> ids(EvidenceBundle bundle) {
        return bundle.items().stream().map(item -> item.metric().id()).toList();
    }

    private static List<String> advisories(Object value, int min) {
        return strings(value, min, MAX_STEPS).stream().map(AdvisoryContract::advisory).toList();
    }

    private static List<String> strings(Object value, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) throw invalid();
        var result = new ArrayList<String>(list.size());
        for (Object item : list) result.add(text(item));
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_TEXT
                || text.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                        || Character.getType(cp) == Character.FORMAT
                        || Character.getType(cp) == Character.SURROGATE
                        || Character.getType(cp) == Character.LINE_SEPARATOR
                        || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR)) {
            throw invalid();
        }
        return text;
    }

    private static String advisory(Object value) {
        String text = text(value);
        if (QUANTITY.matcher(text).find()) throw ungrounded();
        return text;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid advisory schema");
    }

    private static Ungrounded ungrounded() { return new Ungrounded(); }

    static final class Ungrounded extends IllegalArgumentException { }
}
