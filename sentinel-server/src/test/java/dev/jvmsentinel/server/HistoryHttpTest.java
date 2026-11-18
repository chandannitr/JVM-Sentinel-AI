package dev.jvmsentinel.server;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.Json;
import dev.jvmsentinel.detector.Severity;
import dev.jvmsentinel.history.FileIncidentHistory;
import dev.jvmsentinel.history.StoredReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HistoryHttpTest {
    @TempDir Path directory;

    @Test
    void servesRealHistoryOnLoopbackWithBoundsFiltersAndSafeErrors() throws Exception {
        Path root = directory.resolve("history");
        var repo = new FileIncidentHistory(root);
        try (var app = new SpringApplicationBuilder(SentinelServer.class)
                    .run("--server.port=0", "--spring.main.banner-mode=off", "--sentinel.history-dir=" + root);
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var environment = app.getEnvironment();
            assertEquals("127.0.0.1", environment.getProperty("server.address"));
            int port = environment.getRequiredProperty("local.server.port", Integer.class);
            var api = new Requests(client, "http://127.0.0.1:" + port);
            assertEquals("ready", api.get("/api/v1/health", 200).get("state"));
            assertEquals(BigDecimal.ZERO, api.get("/api/v1/summary", 200).get("totalReports"));
            assertEquals(List.of(), api.get("/api/v1/metrics/timeline", 200).get("points"));
            assertEquals(List.of(), api.get("/api/v1/reports", 200).get("items"));

            var first = repo.save(bundle(Instant.EPOCH, false), StoredReport.Origin.CLI, null);
            var second = repo.save(bundle(Instant.EPOCH.plusSeconds(10), true), StoredReport.Origin.CLI, null);
            assertEquals(second.reportId().toString(), firstItem(api.get("/api/v1/reports?limit=1", 200)).get("reportId"));
            assertEquals(first.reportId().toString(), firstItem(api.get("/api/v1/reports?limit=1&page=1", 200)).get("reportId"));
            var details = api.get("/api/v1/reports/" + second.reportId(), 200);
            assertEquals("INCIDENT", details.get("type"));
            assertEquals("VALID", details.get("parseStatus"));
            assertEquals("CRITICAL", firstItem(api.get("/api/v1/incidents?severity=CRITICAL"
                    + "&code=PLATFORM_THREAD_DEADLOCK&from=1970-01-01T00:00:10Z", 200)).get("severity"));
            assertEquals(List.of(), api.get("/api/v1/incidents?severity=WARNING", 200).get("items"));
            var timeline = (List<?>) api.get("/api/v1/metrics/timeline?metric=jmx.heap_used_bytes", 200).get("points");
            assertEquals(new BigDecimal("123"), ((Map<?, ?>) timeline.getFirst()).get("value"));
            assertNull(((Map<?, ?>) timeline.getLast()).get("value"));
            assertEquals("1970-01-01T00:00:10Z", ((Map<?, ?>) timeline.getLast()).get("capturedAt"));
            assertEquals(new BigDecimal("2"), api.get("/api/v1/summary", 200).get("totalReports"));
            assertEquals(BigDecimal.ONE, api.get("/api/v1/summary", 200).get("totalIncidents"));

            assertEquals("not_found", api.get("/api/v1/reports/" + UUID.randomUUID(), 404).get("code"));
            for (String invalid : List.of("/reports/1-1-1-1-1", "/reports?limit=0", "/reports?limit=101",
                    "/reports?page=1536", "/reports?limit=1&limit=2", "/incidents?severity=bad",
                    "/incidents?from=bad", "/incidents?from=1970-01-02T00:00:00Z&to=1970-01-01T00:00:00Z",
                    "/metrics/timeline?metric=unknown")) {
                assertEquals("invalid_request", api.get("/api/v1" + invalid, 400).get("code"));
            }
            assertEquals(405, api.request("/api/v1/reports", "POST").statusCode());
            var dashboard = api.request("/", "GET");
            assertEquals(200, dashboard.statusCode());
            assertTrue(dashboard.body().contains("Incident history"));
            assertTrue(dashboard.headers().firstValue("Content-Security-Policy").orElseThrow().contains("connect-src 'self'"));
            var script = api.request("/dashboard.js", "GET");
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("/api/v1"));
            assertFalse(script.body().contains("innerHTML"));
            assertEquals(200, api.request("/dashboard.css", "GET").statusCode());

            assertEquals("INSUFFICIENT_DATA", api.get("/api/v1/anomalies", 200).get("state"));
            for (int index = 1; index <= 5; index++) {
                Instant capturedAt = Instant.EPOCH.plusSeconds(index);
                repo.save(new EvidenceBundle(EvidenceBundle.Scenario.EXECUTOR, capturedAt, List.of(
                        new EvidenceBundle.Item(EvidenceBundle.Metric.QUEUE_DEPTH, index < 4 ? 1 : 4),
                        new EvidenceBundle.Item(EvidenceBundle.Metric.QUEUE_CAPACITY, 4),
                        new EvidenceBundle.Item(EvidenceBundle.Metric.MAX_WORKERS, 2)),
                        List.of(), List.of()), StoredReport.Origin.CLI, null);
            }
            var anomalies = api.get("/api/v1/anomalies?metric=executor.queue_depth&minimum=3&limit=1", 200);
            assertEquals("READY", anomalies.get("state"));
            var finding = (Map<?, ?>) ((List<?>) anomalies.get("findings")).getFirst();
            assertEquals("ALERT", finding.get("signal"));
            assertEquals("HISTORICAL_METRIC_INCREASE", finding.get("incidentCode"));
            assertEquals(3, ((List<?>) finding.get("baselineReferences")).size());
            assertEquals(BigDecimal.ONE, api.get("/api/v1/incidents", 200).get("total"));
            for (String query : List.of("metric=executor.rejected_tasks", "minimum=2", "limit=51",
                    "metric=executor.queue_depth&scenario=JVM", "sensitivity=NaN", "from=invalid",
                    "from=2020-01-01T00:00:00Z&to=2019-01-01T00:00:00Z")) {
                assertEquals("invalid_request", api.get("/api/v1/anomalies?" + query, 400).get("code"));
            }

            Path moved = directory.resolve("existing");
            Files.move(root, moved);
            Files.createSymbolicLink(root, moved);
            var error = api.get("/api/v1/reports", 500);
            assertEquals("history_unavailable", error.get("code"));
            assertFalse(error.toString().contains(directory.toString()));
        }
    }

    private static EvidenceBundle bundle(Instant time, boolean deadlock) {
        return new EvidenceBundle(EvidenceBundle.Scenario.JVM, time,
                deadlock ? List.of(new EvidenceBundle.Item(EvidenceBundle.Metric.DEADLOCKED_THREADS, 2))
                        : List.of(new EvidenceBundle.Item(EvidenceBundle.Metric.HEAP_USED, 123)),
                deadlock ? List.of(new EvidenceBundle.Detection(EvidenceBundle.Rule.PLATFORM_THREAD_DEADLOCK,
                        Severity.CRITICAL, time)) : List.of(), List.of());
    }

    private static Map<?, ?> firstItem(Map<?, ?> body) {
        return (Map<?, ?>) ((List<?>) body.get("items")).getFirst();
    }

    private record Requests(HttpClient client, String url) {
        Map<?, ?> get(String path, int status) throws Exception {
            var reply = request(path, "GET");
            assertEquals(status, reply.statusCode(), reply.body());
            return Json.object(reply.body());
        }

        HttpResponse<String> request(String path, String method) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
