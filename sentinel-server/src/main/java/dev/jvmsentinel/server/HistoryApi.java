package dev.jvmsentinel.server;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.detector.Severity;
import dev.jvmsentinel.history.IncidentHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.jvmsentinel.server.ApiModels.*;

@RestController
@RequestMapping(path = "/api/v1", produces = "application/json")
final class HistoryApi {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_PAGE = 1535;
    private final IncidentHistoryRepository history;

    HistoryApi(IncidentHistoryRepository history) { this.history = history; }

    @GetMapping("/health")
    Health health() {
        String version = SentinelServer.class.getPackage().getImplementationVersion();
        return new Health("ready", version == null ? "0.1.0-SNAPSHOT" : version);
    }

    @GetMapping("/reports")
    Page<ReportHeader> reports(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        var query = new Query(parameters, "page", "limit");
        int page = query.integer("page", 0, 0, MAX_PAGE);
        int limit = query.integer("limit", DEFAULT_LIMIT, 1, 100);
        var result = history.query();
        return page(result.reports().stream().map(ReportHeader::from).toList(), page, limit, result);
    }

    @GetMapping("/reports/{reportId}")
    ResponseEntity<?> report(@PathVariable("reportId") String id) throws IOException {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new BadQuery("reportId must be a canonical UUID.");
        }
        var report = history.findById(uuid);
        if (report.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", "Report not found."));
        return ResponseEntity.ok(Report.from(report.orElseThrow()));
    }

    @GetMapping("/incidents")
    Page<Incident> incidents(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        var query = new Query(parameters, "page", "limit", "severity", "code", "from", "to");
        int page = query.integer("page", 0, 0, MAX_PAGE);
        int limit = query.integer("limit", DEFAULT_LIMIT, 1, 100);
        String severity = query.enumName("severity", Severity.class);
        String code = query.enumName("code", EvidenceBundle.Rule.class);
        var range = query.range();
        var result = history.query();
        var matches = allIncidents(result).stream()
                .filter(incident -> (severity == null || severity.equals(incident.severity()))
                        && (code == null || code.equals(incident.code()))
                        && range.includes(incident.timestamp())).toList();
        return page(matches, page, limit, result);
    }

    @GetMapping("/metrics/timeline")
    Timeline timeline(@RequestParam MultiValueMap<String, String> parameters) throws IOException {
        var query = new Query(parameters, "metric", "limit", "from", "to");
        String id = query.value("metric");
        var metric = id == null ? EvidenceBundle.Metric.HEAP_USED
                : Arrays.stream(EvidenceBundle.Metric.values()).filter(item -> item.id().equals(id))
                    .findFirst().orElseThrow(() -> new BadQuery("Unknown metric."));
        int limit = query.integer("limit", 100, 1, 200);
        var range = query.range();
        var result = history.query();
        var points = new ArrayList<>(result.reports().stream()
                .filter(report -> range.includes(report.evidence().capturedAt())).limit(limit)
                .map(report -> new Point(report.evidence().capturedAt(), report.reportId(),
                        report.evidence().items().stream().filter(item -> item.metric() == metric)
                                .findFirst().map(EvidenceBundle.Item::value).orElse(null))).toList());
        points.sort(Comparator.comparing(Point::capturedAt).thenComparing(Point::reportId));
        return new Timeline(metric.id(), metric.unit(), points, Diagnostics.from(result));
    }

    @GetMapping("/summary")
    Summary summary() throws IOException {
        var result = history.query();
        var incidents = allIncidents(result);
        Instant now = Instant.now();
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        long recent = incidents.stream().filter(item -> !item.timestamp().isBefore(cutoff)
                && !item.timestamp().isAfter(now)).count();
        return new Summary(result.reports().size(), incidents.size(), recent,
                result.reports().isEmpty() ? null : result.reports().getFirst().evidence().capturedAt(),
                incidents.stream().collect(Collectors.groupingBy(Incident::code, Collectors.counting())),
                incidents.stream().collect(Collectors.groupingBy(Incident::severity, Collectors.counting())),
                Diagnostics.from(result));
    }

    private static List<Incident> allIncidents(IncidentHistoryRepository.QueryResult result) {
        return result.reports().stream().flatMap(report -> report.evidence().detections().stream()
                .map(finding -> Incident.from(report, finding)))
                .sorted(Comparator.comparing(Incident::timestamp).reversed()
                        .thenComparing(Incident::reportId).thenComparing(Incident::code)).toList();
    }

    private static <T> Page<T> page(List<T> items, int page, int limit,
                                    IncidentHistoryRepository.QueryResult result) {
        return new Page<>(items.stream().skip((long) page * limit).limit(limit).toList(), page, limit,
                items.size(), Diagnostics.from(result));
    }

    @ExceptionHandler(BadQuery.class)
    ResponseEntity<ApiError> badQuery(BadQuery failure) {
        return ResponseEntity.badRequest().body(new ApiError("invalid_request", failure.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ApiError> inaccessibleHistory() {
        return ResponseEntity.internalServerError().body(new ApiError("history_unavailable", "History is unavailable."));
    }

    private record Range(Instant from, Instant to) {
        boolean includes(Instant timestamp) {
            return (from == null || !timestamp.isBefore(from)) && (to == null || !timestamp.isAfter(to));
        }
    }

    private static final class Query {
        private final MultiValueMap<String, String> values;

        Query(MultiValueMap<String, String> values, String... allowed) {
            this.values = values;
            for (var entry : values.entrySet()) {
                if (!List.of(allowed).contains(entry.getKey()) || entry.getValue().size() != 1
                        || entry.getValue().getFirst().isBlank() || entry.getValue().getFirst().length() > 80) {
                    throw new BadQuery("Unknown, empty, or repeated parameter.");
                }
            }
        }

        String value(String key) { return values.getFirst(key); }

        int integer(String key, int fallback, int min, int max) {
            String value = value(key);
            if (value == null) return fallback;
            try {
                if (!value.matches("[0-9]{1,4}")) throw new NumberFormatException();
                int result = Integer.parseInt(value);
                if (result < min || result > max) throw new NumberFormatException();
                return result;
            } catch (NumberFormatException invalid) {
                throw new BadQuery(key + " must be an integer between " + min + " and " + max + ".");
            }
        }

        <T extends Enum<T>> String enumName(String key, Class<T> type) {
            String value = value(key);
            if (value == null) return null;
            try {
                return Enum.valueOf(type, value).name();
            } catch (IllegalArgumentException invalid) {
                throw new BadQuery("Invalid " + key + ".");
            }
        }

        Range range() {
            Instant from = instant("from");
            Instant to = instant("to");
            if (from != null && to != null && from.isAfter(to)) throw new BadQuery("from must precede or equal to.");
            return new Range(from, to);
        }

        private Instant instant(String key) {
            String value = value(key);
            if (value == null) return null;
            try {
                if (!value.endsWith("Z")) throw new DateTimeParseException("Expected UTC", value, 0);
                return Instant.parse(value);
            } catch (DateTimeParseException invalid) {
                throw new BadQuery(key + " must be an ISO-8601 UTC instant.");
            }
        }
    }

    private static final class BadQuery extends IllegalArgumentException {
        BadQuery(String message) { super(message); }
    }
}
