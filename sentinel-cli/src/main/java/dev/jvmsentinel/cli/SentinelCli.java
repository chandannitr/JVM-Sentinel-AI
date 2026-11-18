package dev.jvmsentinel.cli;

import dev.jvmsentinel.ai.OllamaIncidentExplainer;
import dev.jvmsentinel.core.JmxMetricsCollector;
import dev.jvmsentinel.core.JvmSnapshot;
import dev.jvmsentinel.detector.Incident;
import dev.jvmsentinel.detector.RuleBasedIncidentDetector;
import java.net.URI;
import java.util.List;

/** CLI entrypoint; samples only its own JVM in this starter. */
public final class SentinelCli {
    private SentinelCli() { }

    public static void main(String[] args) throws Exception {
        boolean demoDeadlock = false;
        boolean ai = false;
        int iterations = 1;
        long intervalMs = 500;
        for (String arg : args) {
            if ("--help".equals(arg)) {
                printHelp();
                return;
            } else if ("--demo-deadlock".equals(arg)) {
                demoDeadlock = true;
            } else if ("--ai".equals(arg)) {
                ai = true;
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
        if (demoDeadlock) DeadlockScenario.start();
        JmxMetricsCollector collector = new JmxMetricsCollector();
        RuleBasedIncidentDetector detector = new RuleBasedIncidentDetector();

        for (int index = 0; index < iterations; index++) {
            JvmSnapshot snap = collector.collect();
            List<Incident> incidents = detector.detect(snap);
            System.out.printf("[%s] Heap: %d/%d bytes (%.1f%%), Live threads: %d%n",
                    snap.capturedAt(), snap.heapUsedBytes(), snap.heapMaxBytes(),
                    snap.heapUsageRatio() * 100, snap.liveThreadCount());
            if (incidents.isEmpty()) System.out.println("  No incidents detected by current rules.");
            for (Incident incident : incidents) {
                System.out.printf("  [%s] %s - %s%n", incident.severity(),
                        incident.code(), incident.title());
                for (String evidence : incident.evidence()) System.out.println("    * " + evidence);
            }
            if (ai) {
                String model = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:3b");
                try {
                    var explainer = new OllamaIncidentExplainer(
                            URI.create("http://127.0.0.1:11434/api/generate"), model);
                    System.out.println("AI analysis:\n" + explainer.explain(incidents));
                } catch (Exception failure) {
                    System.err.println("Local AI unavailable: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage())
                            + " (start Ollama and pull the model, or omit --ai)");
                }
            }
            if (index + 1 < iterations) Thread.sleep(intervalMs);
        }
    }

    private static void printHelp() {
        System.out.println("""
                JVM Sentinel AI — local Java 21 monitoring starter
                Options:
                  --help                Show this help
                  --demo-deadlock       Trigger a controlled daemon-thread deadlock
                  --iterations=N        Sampling iterations, default 1
                  --interval-ms=N       Delay between samples, default 500
                  --ai                  Ask local Ollama to explain detected incidents
                Ollama model: OLLAMA_MODEL environment variable (default llama3.2:3b)
                """);
    }
}
