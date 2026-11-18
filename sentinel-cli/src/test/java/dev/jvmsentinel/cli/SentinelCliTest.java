package dev.jvmsentinel.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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
        var output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            SentinelCli.main(new String[] {"--demo-deadlock", "--sync", "--record-jfr=1", "--jfr-summary",
                    "--jfr-output=" + directory});
        } finally {
            System.setOut(original);
        }

        String report = output.toString(StandardCharsets.UTF_8);
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
}
