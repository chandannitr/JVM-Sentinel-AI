package dev.jvmsentinel.core;

import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class JfrDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void writesReadableRecordingsWithUniqueNamesAndClosesThem() throws Exception {
        var activeBefore = FlightRecorder.getFlightRecorder().getRecordings();
        Path first = recordShortSample();
        Path second = recordShortSample();

        assertNotEquals(first, second);
        for (Path file : new Path[] {first, second}) {
            assertEquals(directory, file.getParent());
            assertTrue(file.getFileName().toString().endsWith(".jfr"));
            try (var recording = new RecordingFile(file)) {
                assertTrue(recording.readEventTypes().stream()
                        .anyMatch(type -> type.getName().equals("jdk.ThreadSleep")));
            }
        }
        assertTrue(RecordingFile.readAllEvents(first).stream()
                .anyMatch(event -> event.getEventType().getName().equals("jdk.ThreadSleep")));
        assertEquals(activeBefore, FlightRecorder.getFlightRecorder().getRecordings());
    }

    @Test
    void summarizesConfiguredEventsInStableOrderAndRejectsInvalidFiles() throws Exception {
        Path file = recordShortSample();
        var counts = JfrDiagnostics.summarize(file);
        var eventNames = java.util.List.of("jdk.ThreadSleep", "jdk.JavaMonitorEnter", "jdk.GarbageCollection");
        assertIterableEquals(eventNames, counts.sequencedKeySet());
        var events = RecordingFile.readAllEvents(file);
        for (String eventName : eventNames) {
            long expected = events.stream().filter(event -> event.getEventType().getName().equals(eventName)).count();
            assertEquals(expected, counts.get(eventName));
        }
        assertTrue(counts.get("jdk.ThreadSleep") > 0);
        assertThrows(UnsupportedOperationException.class, counts::clear);
        Path invalid = Files.writeString(directory.resolve("invalid.jfr"), "invalid");
        assertThrows(IOException.class, () -> JfrDiagnostics.summarize(invalid));
    }

    @Test
    void rejectsInvalidDurationsAndFileUsedAsDirectory() throws IOException {
        var ran = new AtomicBoolean();
        var workload = (JfrDiagnostics.Workload) () -> ran.set(true);
        assertThrows(IllegalArgumentException.class,
                () -> JfrDiagnostics.record(Duration.ZERO, directory, workload));
        assertThrows(IllegalArgumentException.class,
                () -> JfrDiagnostics.record(Duration.ofSeconds(-1), directory, workload));
        assertThrows(IllegalArgumentException.class,
                () -> JfrDiagnostics.record(Duration.ofSeconds(61), directory, workload));

        Path file = Files.createFile(directory.resolve("not-a-directory"));
        assertThrows(IOException.class,
                () -> JfrDiagnostics.record(Duration.ofMillis(50), file, workload));
        assertFalse(ran.get());
    }

    @Test
    void closesRecordingAndLeavesNoOutputWhenWorkloadIsInterrupted() throws IOException {
        var activeBefore = FlightRecorder.getFlightRecorder().getRecordings();
        var interrupted = new InterruptedException("cancelled");
        assertSame(interrupted, assertThrows(InterruptedException.class, () ->
                JfrDiagnostics.record(Duration.ofSeconds(1), directory, () -> { throw interrupted; })));
        assertEquals(activeBefore, FlightRecorder.getFlightRecorder().getRecordings());
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }

    private Path recordShortSample() throws Exception {
        return JfrDiagnostics.record(Duration.ofMillis(100), directory, () -> Thread.sleep(1));
    }
}
