package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.EvidenceBundle.Scenario;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

public final class HistoricalQuery {
    public static final Set<String> PARAMETERS = Set.of("metric", "scenario", "from", "to", "limit",
            "minimum", "windowSamples", "windowSeconds", "sensitivity", "cooldownSeconds", "consecutive");

    private HistoricalQuery() { }

    public static HistoricalAnomalies.Request parse(Map<String, String> options) {
        if (!PARAMETERS.containsAll(options.keySet())
                || options.values().stream().anyMatch(value -> value.isBlank() || value.length() > 80)) {
            throw new IllegalArgumentException("Invalid historical analysis parameter.");
        }
        var defaults = HistoricalAnomalies.DEFAULT;
        var series = HistoricalAnomalies.Series.of(options.getOrDefault("metric", "jmx.heap_used_bytes"));
        Scenario scenario;
        try {
            scenario = Scenario.valueOf(options.getOrDefault("scenario", series.defaultScenario().name()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown anomaly scenario.");
        }
        try {
            var settings = new HistoricalAnomalies.Settings(
                    integer(options, "minimum", defaults.minimum()),
                    integer(options, "windowSamples", defaults.windowSamples()),
                    Duration.ofSeconds(integer(options, "windowSeconds", defaults.window().toSeconds())),
                    Double.parseDouble(options.getOrDefault("sensitivity", Double.toString(defaults.sensitivity()))),
                    Duration.ofSeconds(integer(options, "cooldownSeconds", defaults.cooldown().toSeconds())),
                    integer(options, "consecutive", defaults.consecutive()));
            return new HistoricalAnomalies.Request(series, scenario, instant(options, "from"), instant(options, "to"),
                    integer(options, "limit", 20), settings);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid numeric anomaly parameter.");
        }
    }

    private static int integer(Map<String, String> options, String name, long fallback) {
        String value = options.getOrDefault(name, Long.toString(fallback));
        if (!value.matches("[0-9]{1,7}")) throw new NumberFormatException();
        return Integer.parseInt(value);
    }

    private static Instant instant(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) return null;
        try {
            if (value.endsWith("Z")) return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            // Only the safe public error below is returned.
        }
        throw new IllegalArgumentException(name + " must be an ISO-8601 UTC instant.");
    }
}
