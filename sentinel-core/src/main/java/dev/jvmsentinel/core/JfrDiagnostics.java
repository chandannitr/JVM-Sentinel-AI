package dev.jvmsentinel.core;

import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JfrDiagnostics {
    private static final Duration MAX_DURATION = Duration.ofSeconds(60);
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final String THREAD_SLEEP = "jdk.ThreadSleep";
    private static final String MONITOR_ENTER = "jdk.JavaMonitorEnter";
    private static final String GARBAGE_COLLECTION = "jdk.GarbageCollection";
    private static final String CPU_LOAD = "jdk.CPULoad";
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final long MAX_SUMMARY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SUMMARY_EVENTS = 1_000_000;

    private JfrDiagnostics() { }

    /** Records the current JVM while the workload runs and waits out any remaining window. */
    public static Path record(Duration duration, Path directory, Workload workload) throws Exception {
        return record(duration, directory, workload, false);
    }

    /** Saves a recording only when the complete workload fits within the recording window. */
    public static Path recordCovering(Duration duration, Path directory, Workload workload) throws Exception {
        return record(duration, directory, workload, true);
    }

    private static Path record(Duration duration, Path directory, Workload workload,
                               boolean requireCompleteWorkload) throws Exception {
        Objects.requireNonNull(duration);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(workload);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("JFR duration must be positive and at most 60 seconds");
        }
        if (!FlightRecorder.isAvailable()) {
            throw new IllegalStateException("Java Flight Recorder is unavailable in this JVM");
        }

        Path outputDirectory = Files.createDirectories(directory.toAbsolutePath().normalize());
        try (var recording = new Recording()) {
            recording.setName("JVM Sentinel diagnostics");
            recording.setMaxSize(MAX_BYTES);
            recording.enable(THREAD_SLEEP).withThreshold(Duration.ZERO);
            recording.enable(MONITOR_ENTER).withThreshold(Duration.ofMillis(10));
            recording.enable(GARBAGE_COLLECTION);
            for (String event : supportedCpuEvents()) {
                Duration period = event.equals(CPU_LOAD) ? Duration.ofSeconds(1) : Duration.ofMillis(20);
                recording.enable(event).withPeriod(period);
            }

            var timer = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("sentinel-jfr-stop").factory());
            try {
                recording.start();
                long deadline = System.nanoTime() + duration.toNanos();
                var stopped = timer.schedule(() -> stopRecording(recording),
                        duration.toNanos(), TimeUnit.NANOSECONDS);
                workload.run();
                if (requireCompleteWorkload && System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("Workload exceeded the JFR window. Increase --record-jfr.");
                }
                stopped.get();
                return dump(recording, outputDirectory);
            } finally {
                timer.shutdownNow();
                // Serialize cancellation with a timer task that has already started.
                stopRecording(recording);
            }
        }
    }

    /** Counts the configured events in a completed file, including zero counts. */
    public static SequencedMap<String, Long> summarize(Path file) throws IOException {
        if (Files.size(file) > MAX_SUMMARY_BYTES) {
            throw new IOException("JFR summary input exceeds 64 MiB");
        }
        var counts = new LinkedHashMap<String, Long>();
        counts.put(THREAD_SLEEP, 0L);
        counts.put(MONITOR_ENTER, 0L);
        counts.put(GARBAGE_COLLECTION, 0L);
        supportedCpuEvents().forEach(event -> counts.put(event, 0L));
        try (var events = new RecordingFile(file)) {
            int read = 0;
            while (events.hasMoreEvents()) {
                if (read++ == MAX_SUMMARY_EVENTS) {
                    throw new IOException("JFR summary exceeds 1,000,000 events");
                }
                String event = events.readEvent().getEventType().getName();
                counts.computeIfPresent(event, (name, count) -> count + 1);
            }
        }
        return Collections.unmodifiableSequencedMap(counts);
    }

    private static List<String> supportedCpuEvents() {
        var supported = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .map(EventType::getName).collect(Collectors.toSet());
        return List.of(CPU_LOAD, EXECUTION_SAMPLE).stream().filter(supported::contains).toList();
    }

    private static void stopRecording(Recording recording) {
        synchronized (recording) {
            if (recording.getState() == RecordingState.RUNNING) {
                recording.stop();
            }
        }
    }

    private static Path dump(Recording recording, Path directory) throws IOException {
        Path file = Files.createTempFile(directory, "sentinel-", ".jfr");
        try {
            recording.dump(file);
            return file;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    public interface Workload {
        void run() throws Exception;
    }
}
