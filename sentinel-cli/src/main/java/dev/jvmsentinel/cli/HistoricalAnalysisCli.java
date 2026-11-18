package dev.jvmsentinel.cli;

import dev.jvmsentinel.history.FileIncidentHistory;
import dev.jvmsentinel.history.HistoricalAnomalies;
import dev.jvmsentinel.history.HistoricalQuery;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;

final class HistoricalAnalysisCli {
    private HistoricalAnalysisCli() { }

    static void run(String[] args) throws IOException {
        var options = new HashMap<String, String>();
        Path directory = Path.of(FileIncidentHistory.DEFAULT_DIRECTORY);
        boolean directorySet = false;
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 3 || equals == arg.length() - 1) {
                throw new IllegalArgumentException("Historical analysis accepts only --name=value options.");
            }
            String key = arg.substring(2, equals);
            String value = arg.substring(equals + 1);
            if (key.equals("history-dir")) {
                if (directorySet || value.isBlank()) throw new IllegalArgumentException("Invalid history directory.");
                directory = Path.of(value);
                directorySet = true;
            } else if (options.putIfAbsent(key.equals("anomalies") ? "metric" : key, value) != null) {
                throw new IllegalArgumentException("Repeated historical analysis option.");
            }
        }
        var request = HistoricalQuery.parse(options);
        var stored = new FileIncidentHistory(directory).query();
        var result = HistoricalAnomalies.fromReports(stored.reports(), request, Instant.now());
        var metric = request.series().metric();
        System.out.printf("Historical analysis: %s (%s), %s%n", metric.id(), metric.unit(), request.scenario());
        System.out.printf("%s: %d comparable samples, %d baseline samples, %d skipped; minimum baseline %d%n",
                result.state(), result.usableSamples(), result.baselineSamples(), result.skippedSamples(),
                request.settings().minimum());
        if (stored.truncated()) System.out.println("History scan limit reached; results are partial.");
        if (result.latest() != null) print("Latest comparison", result.latest());
        for (var finding : result.findings()) {
            String label = finding.signal() == HistoricalAnomalies.Signal.ALERT
                    ? HistoricalAnomalies.incident(finding).code() : "Historical indicator";
            print(label, finding);
        }
        if (result.state() == HistoricalAnomalies.State.READY && result.findings().isEmpty()) {
            System.out.println("No historical increases in the selected observations.");
        }
    }

    private static void print(String label, HistoricalAnomalies.Comparison comparison) {
        System.out.printf(Locale.ROOT, "%s %s: value=%.3f median=%.3f MAD=%.3f deviation=%+.3f "
                        + "threshold=%.3f samples=%d window=%s..%s observation=%s report=%s%n",
                label, comparison.signal(), comparison.observedValue(), comparison.baselineMedian(), comparison.mad(),
                comparison.deviation(), comparison.threshold(), comparison.sampleCount(), comparison.baselineStart(),
                comparison.baselineEnd(), comparison.observation().capturedAt(), comparison.observation().reportId());
    }
}
