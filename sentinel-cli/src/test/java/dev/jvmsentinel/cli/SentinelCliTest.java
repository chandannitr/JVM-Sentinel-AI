package dev.jvmsentinel.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.OllamaIncidentExplainer;
import dev.jvmsentinel.core.JfrDiagnostics;
import jdk.jfr.consumer.RecordingFile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SentinelCliTest {
    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--iterations=0"}));
    }

    @Test
    void rejectsInvalidJfrOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--jfr-summary"}));
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--record-jfr=0"}));
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--record-jfr=61"}));
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--record-jfr=1", "--jfr-output="}));
    }

    @Test
    @Timeout(15)
    void reportsActualDeadlockDetailsAndCreatesReadableJfr(@TempDir Path directory) throws Exception {
        Thread owner = Thread.currentThread();
        String report = runCli(bundle -> {
            assertSame(owner, Thread.currentThread());
            assertEquals(EvidenceBundle.Scenario.DEADLOCK, bundle.scenario());
            assertTrue(bundle.detections().stream().anyMatch(
                    detection -> detection.rule() == EvidenceBundle.Rule.PLATFORM_THREAD_DEADLOCK));
            assertTrue(bundle.items().stream().anyMatch(item ->
                    item.metric() == EvidenceBundle.Metric.DEADLOCKED_THREADS && item.value() >= 2));
            return new OllamaIncidentExplainer.Result(OllamaIncidentExplainer.Status.UNAVAILABLE, null);
        }, "--demo-deadlock", "--ai", "--record-jfr=1", "--jfr-summary", "--jfr-output=" + directory);
        assertTrue(report.contains("PLATFORM_THREAD_DEADLOCK"), report);
        assertTrue(report.contains("Recorded JFR events:"), report);
        assertTrue(report.contains("jdk.ThreadSleep:"), report);
        assertTrue(report.contains("jdk.JavaMonitorEnter:"), report);
        assertTrue(report.contains("jdk.GarbageCollection:"), report);
        assertTrue(report.contains("(deadlock-demo-a) BLOCKED"), report);
        assertTrue(report.contains("(deadlock-demo-b) BLOCKED"), report);
        assertTrue(report.contains("owned by deadlock-demo-b (thread "), report);
        assertTrue(report.contains("owned by deadlock-demo-a (thread "), report);
        assertTrue(report.lines().anyMatch(line -> line.startsWith("      at ")
                && line.contains("dev.jvmsentinel.cli.DeadlockScenario.")), report);

        try (var files = Files.list(directory)) {
            var recordings = files.toList();
            assertEquals(1, recordings.size());
            assertTrue(report.contains("JFR recording: " + recordings.getFirst()), report);
            try (var recording = new RecordingFile(recordings.getFirst())) {
                assertFalse(recording.readEventTypes().isEmpty());
            }
        }
    }

    @Test
    void rejectsIncompatibleWorkloadArguments() {
        assertThrows(IllegalArgumentException.class, () -> SentinelCli.main(
                new String[] {"--demo-executor-saturation", "--demo-cpu-load"}));
        assertThrows(IllegalArgumentException.class, () -> SentinelCli.main(
                new String[] {"--demo-deadlock", "--demo-executor-saturation"}));
        assertThrows(IllegalArgumentException.class, () -> SentinelCli.main(
                new String[] {"--cpu-seconds=1"}));
        assertThrows(IllegalArgumentException.class, () -> SentinelCli.main(
                new String[] {"--demo-cpu-load", "--cpu-seconds=6"}));
        assertThrows(IllegalArgumentException.class, () -> SentinelCli.main(
                new String[] {"--demo-cpu-load", "--cpu-seconds=1", "--record-jfr=1"}));
    }

    @Test
    @Timeout(15)
    void recordsExecutorWorkloadAndReportsObservedSaturation(@TempDir Path directory) throws Exception {
        String report = runCli("--demo-executor-saturation", "--record-jfr=2",
                "--jfr-summary", "--jfr-output=" + directory);
        assertTrue(report.contains("[WARNING] EXECUTOR_SATURATION"), report);
        assertTrue(report.contains("Active workers: 2/2"), report);
        assertTrue(report.contains("Queued tasks: 4/4"), report);
        assertTrue(report.contains("Rejected tasks: 1"), report);
        assertTrue(report.contains("Rule: active workers"), report);
        assertTrue(report.contains("Interpretation:"), report);
        assertRecording(report, directory);
    }

    @Test
    @Timeout(15)
    void recordsCpuWorkloadAndReportsMeasurements(@TempDir Path directory) throws Exception {
        String report = runCli(bundle -> {
            assertEquals(EvidenceBundle.Scenario.CPU, bundle.scenario());
            assertTrue(bundle.detections().isEmpty());
            assertTrue(bundle.items().stream().anyMatch(item -> item.metric() == EvidenceBundle.Metric.CPU_OPERATIONS));
            assertTrue(bundle.items().stream().anyMatch(item -> item.metric() == EvidenceBundle.Metric.THREAD_SLEEP));
            return new OllamaIncidentExplainer.Result(OllamaIncidentExplainer.Status.UNAVAILABLE, null);
        }, "--demo-cpu-load", "--cpu-seconds=1", "--ai", "--record-jfr=2",
                "--jfr-summary", "--jfr-output=" + directory);
        assertTrue(report.contains("CPU workload: elapsed="), report);
        assertTrue(report.contains("operations="), report);
        assertTrue(report.contains("thread CPU time="), report);
        assertFalse(report.contains("CPU_PRESSURE"), report);
        assertTrue(report.contains("AI advisory unavailable. Showing deterministic report."), report);
        assertRecording(report, directory);
    }

    @Test
    void analyzesExecutorEvidenceOnceOnCallingThreadAndPrintsSeparateSections() throws Exception {
        Thread owner = Thread.currentThread();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        String report = runCli(bundle -> {
            assertSame(owner, Thread.currentThread());
            calls.incrementAndGet();
            assertEquals(EvidenceBundle.Rule.EXECUTOR_SATURATION, bundle.detections().getFirst().rule());
            assertTrue(bundle.items().contains(new EvidenceBundle.Item(EvidenceBundle.Metric.REJECTED_TASKS, 1)));
            var advisory = new dev.jvmsentinel.ai.AdvisoryReport(EvidenceBundle.Rule.EXECUTOR_SATURATION,
                    "Capacity was reached.", "Tasks may have been slow to finish.",
                    dev.jvmsentinel.ai.AdvisoryReport.Confidence.low, java.util.List.of("executor.queue_depth"),
                    java.util.List.of(), java.util.List.of("Measure task duration."),
                    java.util.List.of("The observation window is limited."));
            return new OllamaIncidentExplainer.Result(OllamaIncidentExplainer.Status.VALID, advisory);
        }, "--demo-executor-saturation", "--ai");
        assertEquals(1, calls.get());
        for (String section : java.util.List.of("Observed facts", "Deterministic detection", "AI hypothesis",
                "Evidence references", "Suggested next diagnostic steps", "Uncertainty/limitations")) {
            assertTrue(report.contains(section), report);
        }
    }

    private static void assertRecording(String report, Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            var filesFound = files.toList();
            assertEquals(1, filesFound.size());
            Path file = filesFound.getFirst();
            assertTrue(report.contains("JFR recording: " + file), report);
            var counts = JfrDiagnostics.summarize(file);
            counts.forEach((event, count) -> assertTrue(report.contains(event + ": " + count), report));
        }
    }

    private static String runCli(String... arguments) throws Exception {
        return runCli(AdvisoryOutput::local, arguments);
    }

    private static String runCli(AdvisoryOutput.Analysis analysis, String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            SentinelCli.run(arguments, analysis);
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
