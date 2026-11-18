package dev.jvmsentinel.core;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class JfrDiagnostics {
    private static final Duration MAX_DURATION = Duration.ofSeconds(60);
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final String THREAD_SLEEP = "jdk.ThreadSleep";
    private static final String MONITOR_ENTER = "jdk.JavaMonitorEnter";
    private static final String GARBAGE_COLLECTION = "jdk.GarbageCollection";

    private JfrDiagnostics() { }

    /** Records the current JVM while the workload runs and waits out any remaining window. */
    public static Path record(Duration duration, Path directory, Workload workload) throws Exception {
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

            var timer = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("sentinel-jfr-stop").factory());
            try {
                recording.start();
                var stopped = timer.schedule(() -> stopRecording(recording),
                        duration.toNanos(), TimeUnit.NANOSECONDS);
                workload.run();
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
        var counts = new LinkedHashMap<String, Long>();
        counts.put(THREAD_SLEEP, 0L);
        counts.put(MONITOR_ENTER, 0L);
        counts.put(GARBAGE_COLLECTION, 0L);
        try (var events = new RecordingFile(file)) {
            while (events.hasMoreEvents()) {
                String event = events.readEvent().getEventType().getName();
                counts.computeIfPresent(event, (name, count) -> count + 1);
            }
        }
        return Collections.unmodifiableSequencedMap(counts);
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
