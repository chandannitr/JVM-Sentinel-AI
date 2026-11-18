package dev.jvmsentinel.cli;

import dev.jvmsentinel.ai.OllamaIncidentExplainer;
import dev.jvmsentinel.core.AsyncMetricsPipeline;
import dev.jvmsentinel.core.JfrDiagnostics;
import dev.jvmsentinel.core.JmxMetricsCollector;
import dev.jvmsentinel.core.JvmSnapshot;
import dev.jvmsentinel.detector.ExecutorSaturationDetector;
import dev.jvmsentinel.detector.Incident;
import dev.jvmsentinel.detector.RuleBasedIncidentDetector;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** CLI entrypoint; samples only its own JVM in this starter. */
public final class SentinelCli {
    private SentinelCli() { }

    public static void main(String[] args) throws Exception {
        boolean demoDeadlock = false;
        boolean demoExecutor = false;
        boolean demoCpu = false;
        Integer cpuSeconds = null;
        boolean ai = false;
        boolean synchronous = false;
        boolean demoBackpressure = false;
        int iterations = 1;
        long intervalMs = 500;
        Integer jfrSeconds = null;
        boolean jfrSummary = false;
        Path jfrOutput = Path.of(".local-build", "jfr");
        for (String arg : args) {
            if ("--help".equals(arg)) {
                printHelp();
                return;
            } else if ("--demo-deadlock".equals(arg)) {
                demoDeadlock = true;
            } else if ("--demo-executor-saturation".equals(arg)) {
                demoExecutor = true;
            } else if ("--demo-cpu-load".equals(arg)) {
                demoCpu = true;
            } else if (arg.startsWith("--cpu-seconds=")) {
                cpuSeconds = Integer.parseInt(arg.substring("--cpu-seconds=".length()));
                if (cpuSeconds < 1 || cpuSeconds > CpuLoadScenario.MAX_SECONDS) {
                    throw new IllegalArgumentException("--cpu-seconds must be 1..5");
                }
            } else if ("--sync".equals(arg)) {
                synchronous = true;
            } else if ("--async".equals(arg)) {
                synchronous = false;
            } else if ("--demo-backpressure".equals(arg)) {
                demoBackpressure = true;
            } else if ("--ai".equals(arg)) {
                ai = true;
            } else if ("--jfr-summary".equals(arg)) {
                jfrSummary = true;
            } else if (arg.startsWith("--record-jfr=")) {
                jfrSeconds = Integer.parseInt(arg.substring("--record-jfr=".length()));
                if (jfrSeconds < 1 || jfrSeconds > 60) {
                    throw new IllegalArgumentException("--record-jfr must be 1..60 seconds");
                }
            } else if (arg.startsWith("--jfr-output=")) {
                String directory = arg.substring("--jfr-output=".length());
                if (directory.isBlank()) {
                    throw new IllegalArgumentException("--jfr-output requires a directory");
                }
                jfrOutput = Path.of(directory);
            } else if (arg.startsWith("--iterations=")) {
                iterations = Integer.parseInt(arg.substring("--iterations=".length()));
            } else if (arg.startsWith("--interval-ms=")) {
                intervalMs = Long.parseLong(arg.substring("--interval-ms=".length()));
            } else {
                throw new IllegalArgumentException("Unknown option: " + arg + " (try --help)");
            }
        }
        if (iterations < 1 || iterations > 1000 || intervalMs < 0 || intervalMs > 60000) {
            throw new IllegalArgumentException("iterations must be 1..1000; interval-ms must be 0..60000");
        }
        if (synchronous && demoBackpressure) {
            throw new IllegalArgumentException("--demo-backpressure requires async sampling");
        }
        if (jfrSummary && jfrSeconds == null) {
            throw new IllegalArgumentException("--jfr-summary requires --record-jfr");
        }
        int demoCount = (demoDeadlock ? 1 : 0) + (demoBackpressure ? 1 : 0)
                + (demoExecutor ? 1 : 0) + (demoCpu ? 1 : 0);
        if (demoCount > 1) {
            throw new IllegalArgumentException("Choose only one demo flag");
        }
        if (cpuSeconds != null && !demoCpu) {
            throw new IllegalArgumentException("--cpu-seconds requires --demo-cpu-load");
        }
        int cpuDuration = cpuSeconds == null ? CpuLoadScenario.DEFAULT_SECONDS : cpuSeconds;
        if (demoCpu && jfrSeconds != null && jfrSeconds <= cpuDuration) {
            throw new IllegalArgumentException("--record-jfr must exceed the CPU workload duration");
        }

        var options = new SamplingOptions(demoDeadlock, ai, synchronous, demoBackpressure, iterations, intervalMs);
        boolean workloadDemo = demoExecutor || demoCpu;
        boolean executorDemo = demoExecutor;
        var incidents = new ArrayList<Incident>();
        JfrDiagnostics.Workload workload = workloadDemo
                ? () -> runWorkload(executorDemo, cpuDuration, incidents)
                : () -> sample(options);
        if (jfrSeconds == null) {
            workload.run();
        } else {
            Duration window = Duration.ofSeconds(jfrSeconds);
            Path recording = workloadDemo
                    ? JfrDiagnostics.recordCovering(window, jfrOutput, workload)
                    : JfrDiagnostics.record(window, jfrOutput, workload);
            System.out.println("JFR recording: " + recording);
            if (jfrSummary) {
                System.out.println("Recorded JFR events:");
                JfrDiagnostics.summarize(recording).forEach(
                        (event, count) -> System.out.printf("  %s: %d%n", event, count));
            }
        }
        if (workloadDemo && ai) {
            explain(incidents);
        }
    }

    private static void runWorkload(boolean executorDemo, int cpuDuration, List<Incident> incidents) throws Exception {
        if (executorDemo) {
            var snapshot = ExecutorSaturationScenario.run();
            new ExecutorSaturationDetector().detect(snapshot).ifPresent(incidents::add);
            printIncidents(incidents);
            return;
        }
        var result = CpuLoadScenario.run(Duration.ofSeconds(cpuDuration));
        String cpuTime = result.threadCpuNanos().isPresent()
                ? result.threadCpuNanos().getAsLong() + " ns" : "unavailable";
        System.out.printf("CPU workload: elapsed=%d ns, operations=%d, thread CPU time=%s, checksum=%016x%n",
                result.elapsedNanos(), result.operations(), cpuTime, result.checksum());
    }

    private record SamplingOptions(boolean demoDeadlock, boolean ai, boolean synchronous,
                                   boolean demoBackpressure, int iterations, long intervalMs) { }

    private static void sample(SamplingOptions options) throws Exception {
        if (options.demoDeadlock()) {
            DeadlockScenario.start();
        }
        JmxMetricsCollector collector = new JmxMetricsCollector();
        RuleBasedIncidentDetector detector = new RuleBasedIncidentDetector();

        if (options.synchronous()) {
            for (int index = 0; index < options.iterations(); index++) {
                report(detector, collector.collect(), options.ai());
                if (index + 1 < options.iterations()) {
                    Thread.sleep(options.intervalMs());
                }
            }
            return;
        }

        boolean slowHandler = options.demoBackpressure();
        var pipeline = new AsyncMetricsPipeline(collector, snap -> {
            report(detector, snap, options.ai());
            if (slowHandler) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, slowHandler ? Math.max(options.iterations(), 3) : options.iterations(),
                slowHandler ? 1 : options.intervalMs(), slowHandler ? 1 : 4);
        try (pipeline) {
            pipeline.start();
            pipeline.awaitCompletion();
        }
        var stats = pipeline.stats();
        if (slowHandler || stats.dropped() > 0 || stats.collectionFailures() > 0 || stats.processingFailures() > 0) {
            System.err.printf("Sampling backpressure: collected=%d accepted=%d processed=%d dropped=%d "
                            + "collectionFailures=%d processingFailures=%d retries=%d queueDepth=%d%n",
                    stats.collected(), stats.accepted(), stats.processed(), stats.dropped(),
                    stats.collectionFailures(), stats.processingFailures(), stats.retryAttempts(), stats.queueDepth());
        }
    }

    private static void report(RuleBasedIncidentDetector detector, JvmSnapshot snap, boolean ai) {
        List<Incident> incidents = detector.detect(snap);
        System.out.printf("[%s] Heap: %d/%d bytes (%.1f%%), Live threads: %d%n",
                snap.capturedAt(), snap.heapUsedBytes(), snap.heapMaxBytes(),
                snap.heapUsageRatio() * 100, snap.liveThreadCount());
        printIncidents(incidents);
        if (ai) {
            explain(incidents);
        }
    }

    private static void printIncidents(List<Incident> incidents) {
        if (incidents.isEmpty()) System.out.println("  No incidents detected by current rules.");
        for (Incident incident : incidents) {
            System.out.printf("  [%s] %s - %s [%s]%n", incident.severity(),
                    incident.code(), incident.title(), incident.detectedAt());
            for (String evidence : incident.evidence()) System.out.println("    * " + evidence);
            System.out.println("    Interpretation: " + incident.explanation());
            for (var thread : incident.threadDetails()) {
                System.out.printf("    Thread %d (%s) %s%n", thread.threadId(), thread.name(), thread.state());
                if (thread.lockName() != null) {
                    System.out.printf("      waiting on %s%n", thread.lockName());
                }
                if (thread.lockOwnerId() >= 0) {
                    System.out.printf("      owned by %s (thread %d)%n", thread.lockOwnerName(), thread.lockOwnerId());
                }
                for (var frame : thread.stackTrace()) {
                    System.out.println("      at " + frame);
                }
            }
        }
    }

    private static void explain(List<Incident> incidents) {
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:3b");
        try {
            var explainer = new OllamaIncidentExplainer(
                    URI.create("http://127.0.0.1:11434/api/generate"), model);
            System.out.println("AI analysis:\n" + explainer.explain(incidents));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            System.err.println("Local AI unavailable: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage())
                    + " (start Ollama and pull the model, or omit --ai)");
        }
    }

    private static void printHelp() {
        System.out.println("""
                JVM Sentinel AI — local Java 21 monitoring starter
                Options:
                  --help                Show this help
                  --demo-deadlock       Trigger a controlled daemon-thread deadlock
                  --demo-executor-saturation  Fill a two-worker executor and its four-slot queue
                  --demo-cpu-load       Run a bounded computation on one worker
                  --cpu-seconds=N       CPU workload duration, 1..5 (default 2)
                  --iterations=N        Sampling iterations, default 1
                  --interval-ms=N       Scheduler delay in ms, default 500
                  --async               Use bounded async sampling (default)
                  --sync                Collect and report on the calling thread
                  --demo-backpressure   Slow async reporting to demonstrate queue saturation
                  --ai                  Ask local Ollama to explain detected incidents
                  --record-jfr=N        Record the current JVM for 1..60 seconds
                  --jfr-output=DIR      Recording directory (default .local-build/jfr)
                  --jfr-summary         Print event counts for the new recording
                Examples:
                  --demo-deadlock
                  --demo-executor-saturation --record-jfr=5 --jfr-summary
                  --demo-cpu-load --record-jfr=5 --jfr-summary
                Ollama model: OLLAMA_MODEL environment variable (default llama3.2:3b)
                """);
    }
}
