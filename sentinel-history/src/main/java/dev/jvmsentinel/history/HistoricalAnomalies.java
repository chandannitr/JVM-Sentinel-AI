package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.EvidenceBundle.Metric;
import dev.jvmsentinel.ai.EvidenceBundle.Scenario;
import dev.jvmsentinel.detector.Incident;
import dev.jvmsentinel.detector.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static dev.jvmsentinel.ai.EvidenceBundle.Metric.*;

public final class HistoricalAnomalies {
    public static final int MAX_SAMPLES = 512;
    public static final int MAX_FINDINGS = 50;
    public static final Settings DEFAULT = new Settings(20, 60, Duration.ofHours(1), 3.5,
            Duration.ofMinutes(5), 2);
    private static final Comparator<Sample> CHRONOLOGICAL =
            Comparator.comparing(Sample::capturedAt).thenComparing(Sample::reportId);

    private HistoricalAnomalies() { }

    public enum Series {
        HEAP(HEAP_USED, Scenario.JVM, 1_048_576),
        QUEUE(QUEUE_DEPTH, Scenario.EXECUTOR, 1);

        private final Metric metric;
        private final Scenario defaultScenario;
        private final double minimumIncrease;

        Series(Metric metric, Scenario defaultScenario, double minimumIncrease) {
            this.metric = metric;
            this.defaultScenario = defaultScenario;
            this.minimumIncrease = minimumIncrease;
        }

        public Metric metric() { return metric; }
        public Scenario defaultScenario() { return defaultScenario; }

        public static Series of(String id) {
            for (var series : values()) {
                if (series.metric.id().equals(id)) return series;
            }
            throw new IllegalArgumentException("Unsupported anomaly metric.");
        }
    }

    public record Settings(int minimum, int windowSamples, Duration window, double sensitivity,
                           Duration cooldown, int consecutive) {
        public Settings {
            Objects.requireNonNull(window);
            Objects.requireNonNull(cooldown);
            if (minimum < 3 || windowSamples < minimum || windowSamples > 100
                    || window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(Duration.ofDays(7)) > 0
                    || !Double.isFinite(sensitivity) || sensitivity < 1 || sensitivity > 20
                    || cooldown.isNegative() || cooldown.compareTo(Duration.ofDays(7)) > 0
                    || consecutive < 2 || consecutive > 10) {
                throw new IllegalArgumentException("Invalid historical analysis settings.");
            }
        }
    }

    public record Request(Series series, Scenario scenario, Instant from, Instant to, int limit, Settings settings) {
        public Request {
            Objects.requireNonNull(series);
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(settings);
            if (limit < 1 || limit > MAX_FINDINGS || (from != null && to != null && from.isAfter(to))
                    || (series == Series.QUEUE && scenario != Scenario.EXECUTOR)
                    || (series == Series.HEAP && scenario != Scenario.JVM && scenario != Scenario.DEADLOCK)) {
                throw new IllegalArgumentException("Invalid anomaly range, scenario, or result limit.");
            }
        }
    }

    /** Configuration is the recorded heap maximum, or the recorded worker and queue limits. */
    public record Configuration(long capacity, long workers) { }
    public record Sample(UUID reportId, Instant capturedAt, Scenario scenario, Metric metric, String unit,
                         Double value, Configuration configuration) {
        public Sample {
            Objects.requireNonNull(reportId);
            Objects.requireNonNull(capturedAt);
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(metric);
            Objects.requireNonNull(unit);
        }
    }

    public enum State { INSUFFICIENT_DATA, READY }
    public enum Signal { NORMAL, CANDIDATE, ALERT, COOLDOWN }
    public record Reference(UUID reportId, String evidenceId, Instant capturedAt) { }
    public record Comparison(Reference observation, double observedValue, double baselineMedian, double mad,
                             double deviation, double threshold, int sampleCount, Instant baselineStart,
                             Instant baselineEnd, List<Reference> baselineReferences,
                             Configuration configuration, Signal signal, int consecutive) {
        public Comparison { baselineReferences = List.copyOf(baselineReferences); }
    }
    public record Result(State state, int usableSamples, int skippedSamples, int baselineSamples,
                         Comparison latest, List<Comparison> findings) {
        public Result { findings = List.copyOf(findings); }
    }

    /** Adapt an existing deterministic incident for a confirmed alert without persisting it. */
    public static Incident incident(Comparison finding) {
        if (finding.signal() != Signal.ALERT) throw new IllegalArgumentException("No confirmed alert.");
        return new Incident("HISTORICAL_METRIC_INCREASE", Severity.WARNING, "Sustained metric increase",
                "Repeated observations exceeded the rolling threshold. Investigate this historical indicator.",
                List.of("Evidence: " + finding.observation().evidenceId(),
                        "Observation report: " + finding.observation().reportId()),
                finding.observation().capturedAt());
    }

    public static Result fromReports(List<StoredReport> reports, Request request, Instant now) {
        if (reports.size() > MAX_SAMPLES) throw new IllegalArgumentException("Too many historical reports.");
        var samples = reports.stream().filter(report -> report.evidence().scenario() == request.scenario())
                .map(report -> sample(report, request.series())).toList();
        return analyze(samples, request, now);
    }

    private static Sample sample(StoredReport report, Series series) {
        var evidence = report.evidence();
        Long value = measured(evidence, series.metric);
        Long capacity = measured(evidence, series == Series.HEAP ? HEAP_MAX : QUEUE_CAPACITY);
        Long workers;
        if (series == Series.HEAP) {
            workers = 0L;
        } else {
            workers = measured(evidence, MAX_WORKERS);
        }
        var config = capacity == null || workers == null ? null : new Configuration(capacity, workers);
        return new Sample(report.reportId(), evidence.capturedAt(), evidence.scenario(), series.metric,
                series.metric.unit(), value == null ? null : value.doubleValue(), config);
    }

    private static Long measured(dev.jvmsentinel.ai.EvidenceBundle evidence, Metric metric) {
        return evidence.items().stream().filter(item -> item.metric() == metric)
                .findFirst().map(dev.jvmsentinel.ai.EvidenceBundle.Item::value).orElse(null);
    }

    public static Result analyze(List<Sample> input, Request request, Instant now) {
        Objects.requireNonNull(now);
        if (input.size() > MAX_SAMPLES) throw new IllegalArgumentException("Too many historical samples.");
        var matching = input.stream()
                .filter(sample -> sample.metric() == request.series().metric && sample.scenario() == request.scenario())
                .filter(sample -> (request.from() == null || !sample.capturedAt().isBefore(request.from()))
                        && (request.to() == null || !sample.capturedAt().isAfter(request.to())))
                .sorted(CHRONOLOGICAL).toList();
        var identityCounts = new HashMap<UUID, Integer>();
        matching.forEach(sample -> identityCounts.merge(sample.reportId(), 1, Integer::sum));
        var analysis = new Analysis(request);
        for (var sample : matching) {
            if (sample.capturedAt().isAfter(now) || identityCounts.get(sample.reportId()) > 1) {
                analysis.skipped++;
            } else {
                analysis.accept(sample);
            }
        }
        return analysis.result();
    }

    private static final class Analysis {
        private final Request request;
        private final Settings settings;
        private final ArrayDeque<Sample> baseline = new ArrayDeque<>();
        private final ArrayDeque<Comparison> findings = new ArrayDeque<>();
        private Configuration configuration;
        private Comparison latest;
        private Instant previous;
        private Instant lastAlert;
        private int streak;
        private int usable;
        private int skipped;

        Analysis(Request request) {
            this.request = request;
            this.settings = request.settings();
        }

        void accept(Sample sample) {
            var config = sample.configuration();
            if (!Objects.equals(configuration, config)) {
                baseline.clear();
                latest = null;
                streak = 0;
                lastAlert = null;
                configuration = config;
            }
            if (previous != null && elapsed(previous, sample.capturedAt()).compareTo(settings.window()) > 0) {
                streak = 0;
            }
            previous = sample.capturedAt();
            while (!baseline.isEmpty()
                    && elapsed(baseline.getFirst().capturedAt(), previous).compareTo(settings.window()) > 0) {
                baseline.removeFirst();
            }
            if (!valid(sample)) {
                skipped++;
                streak = 0;
                latest = null;
                return;
            }
            usable++;
            if (baseline.size() < settings.minimum()) {
                latest = null;
                addBaseline(sample);
                return;
            }
            compare(sample);
        }

        private boolean valid(Sample sample) {
            Double value = sample.value();
            return sample.unit().equals(request.series().metric.unit())
                    && configuration != null && configuration.capacity() > 0
                    && (request.series() == Series.HEAP ? configuration.workers() == 0 : configuration.workers() > 0)
                    && value != null && Double.isFinite(value) && value >= 0 && value <= configuration.capacity();
        }

        private void compare(Sample sample) {
            double median = median(baseline.stream().mapToDouble(Sample::value).sorted().toArray());
            double mad = median(baseline.stream().mapToDouble(point -> Math.abs(point.value() - median)).sorted().toArray());
            double threshold = Math.max(request.series().minimumIncrease, settings.sensitivity() * 1.4826 * mad);
            double deviation = sample.value() - median;
            Signal signal = Signal.NORMAL;
            if (deviation >= threshold) {
                streak++;
                if (streak < settings.consecutive()) {
                    signal = Signal.CANDIDATE;
                } else if (lastAlert == null || elapsed(lastAlert, previous).compareTo(settings.cooldown()) >= 0) {
                    signal = Signal.ALERT;
                    lastAlert = previous;
                } else {
                    signal = Signal.COOLDOWN;
                }
            } else {
                streak = 0;
            }
            latest = new Comparison(reference(sample), sample.value(), median, mad, deviation, threshold,
                    baseline.size(), baseline.getFirst().capturedAt(), baseline.getLast().capturedAt(),
                    baseline.stream().map(HistoricalAnomalies::reference).toList(), configuration, signal, streak);
            if (signal == Signal.NORMAL) {
                addBaseline(sample);
            } else {
                if (findings.size() == request.limit()) findings.removeFirst();
                findings.addLast(latest);
            }
        }

        private void addBaseline(Sample sample) {
            if (baseline.size() == settings.windowSamples()) baseline.removeFirst();
            baseline.addLast(sample);
        }

        Result result() {
            return new Result(latest == null ? State.INSUFFICIENT_DATA : State.READY, usable, skipped,
                    baseline.size(), latest, List.copyOf(findings));
        }
    }

    private static Duration elapsed(Instant from, Instant to) { return Duration.between(from, to); }

    private static Reference reference(Sample sample) {
        return new Reference(sample.reportId(), sample.metric().id(), sample.capturedAt());
    }

    private static double median(double[] sorted) {
        int center = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[center] : sorted[center - 1] / 2 + sorted[center] / 2;
    }
}
