package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.AdvisoryReport;
import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.OllamaIncidentExplainer;
import dev.jvmsentinel.detector.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.jvmsentinel.ai.EvidenceBundle.Metric.DEADLOCKED_THREADS;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FileIncidentHistoryTest {
    @TempDir Path directory;

    @Test
    void restartsWithTypedRoundTripAndOmitsPrivateNarrative() throws Exception {
        Path root = directory.resolve("history");
        var store = new FileIncidentHistory(root);
        assertTrue(store.query().reports().isEmpty());
        assertEquals(0, store.enforceRetention());
        var response = new AdvisoryReport(EvidenceBundle.Rule.PLATFORM_THREAD_DEADLOCK,
                "/Users/private/token", "model assertion", AdvisoryReport.Confidence.low,
                List.of(DEADLOCKED_THREADS.id()), List.of(), List.of("private log"), List.of("secret text"));
        var advisory = StoredReport.Advisory.from(new OllamaIncidentExplainer.Result(
                OllamaIncidentExplainer.Status.VALID, response));
        var saved = store.save(evidence(Instant.EPOCH), StoredReport.Origin.CLI, advisory);
        assertEquals(StoredReport.Type.INCIDENT, saved.type());
        var reopened = new FileIncidentHistory(root);
        assertEquals(saved, reopened.findById(saved.reportId()).orElseThrow());
        assertEquals(List.of(saved), reopened.query().reports());
        var json = Files.readString(root.resolve(saved.reportId() + FileIncidentHistory.SUFFIX));
        assertFalse(json.contains("/Users"));
        assertFalse(json.contains("private log"));
        assertFalse(json.contains("secret text"));
        assertTrue(json.contains(DEADLOCKED_THREADS.id()));
        var withoutAi = store.save(evidence(Instant.EPOCH.plusSeconds(1)), StoredReport.Origin.CLI, null);
        assertNull(reopened.findById(withoutAi.reportId()).orElseThrow().advisory());
        assertTrue(reopened.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void sortsConcurrentWritesAndEnforcesRetention() throws Exception {
        var ready = new CountDownLatch(1);
        var store = new FileIncidentHistory(directory);
        var ids = new HashSet<UUID>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var writes = new java.util.ArrayList<java.util.concurrent.Future<StoredReport>>();
            for (int index = 0; index < 8; index++) {
                writes.add(executor.submit(() -> {
                    if (!ready.await(5, TimeUnit.SECONDS)) throw new IOException("Start signal missing");
                    return new FileIncidentHistory(directory).save(evidence(Instant.EPOCH), StoredReport.Origin.CLI, null);
                }));
            }
            ready.countDown();
            while (!writes.stream().allMatch(java.util.concurrent.Future::isDone)) {
                assertEquals(0, store.query().corrupt());
                writes.getFirst().get(5, TimeUnit.SECONDS);
            }
            for (var write : writes) assertTrue(ids.add(write.get(5, TimeUnit.SECONDS).reportId()));
        }
        List<StoredReport> sorted = store.query().reports();
        assertEquals(ids.stream().sorted().toList(), sorted.stream().map(StoredReport::reportId).toList());
        var retaining = new FileIncidentHistory(directory, 3);
        assertEquals(5, retaining.enforceRetention());
        assertEquals(sorted.subList(0, 3), retaining.query().reports());
        var newest = retaining.save(evidence(Instant.EPOCH.plusSeconds(2)), StoredReport.Origin.CLI, null);
        assertEquals(newest, retaining.query().reports().getFirst());
        assertEquals(3, retaining.query().reports().size());
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void skipsMalformedOversizedUnknownVersionAndSymlinkReports() throws Exception {
        var store = new FileIncidentHistory(directory);
        var valid = store.save(evidence(Instant.EPOCH), StoredReport.Origin.CLI, null);
        write("{");
        write("x".repeat(FileIncidentHistory.MAX_BYTES + 1));
        write("{\"schemaVersion\":99}");
        Path external = directory.resolve("external.txt");
        Files.writeString(external, ReportJson.write(valid));
        var linkId = UUID.randomUUID();
        Files.createSymbolicLink(directory.resolve(linkId + FileIncidentHistory.SUFFIX), external);
        Files.writeString(directory.resolve(".unfinished.tmp"), "{");
        var result = store.query();
        assertEquals(2, result.corrupt());
        assertEquals(1, result.oversized());
        assertEquals(1, result.unsupported());
        assertFalse(result.truncated());
        assertEquals(List.of(valid), result.reports());
        assertTrue(store.findById(linkId).isEmpty());
        var rootLink = directory.resolve("history-link");
        Files.createSymbolicLink(rootLink, directory);
        assertThrows(IOException.class, () -> new FileIncidentHistory(rootLink).query());
    }

    @Test
    void capsDirectoryWorkAndRefusesWritesPastTheScanBudget() throws Exception {
        for (int index = 0; index <= FileIncidentHistory.SCAN_LIMIT; index++) {
            Files.createFile(directory.resolve("ignored-" + index));
        }
        var store = new FileIncidentHistory(directory);
        assertTrue(store.query().truncated());
        assertTrue(store.query().reports().isEmpty());
        assertThrows(IOException.class, () -> store.save(evidence(Instant.EPOCH), StoredReport.Origin.CLI, null));
    }

    private void write(String text) throws IOException {
        Files.writeString(directory.resolve(UUID.randomUUID() + FileIncidentHistory.SUFFIX), text);
    }

    private static EvidenceBundle evidence(Instant instant) {
        return new EvidenceBundle(EvidenceBundle.Scenario.DEADLOCK, instant,
                List.of(new EvidenceBundle.Item(DEADLOCKED_THREADS, 2)),
                List.of(new EvidenceBundle.Detection(EvidenceBundle.Rule.PLATFORM_THREAD_DEADLOCK,
                        Severity.CRITICAL, instant)), List.of());
    }
}
