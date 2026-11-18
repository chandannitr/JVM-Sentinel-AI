package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.EvidenceBundle.Metric;
import dev.jvmsentinel.ai.EvidenceBundle.Scenario;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static dev.jvmsentinel.history.HistoricalAnomalies.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalAnomaliesTest {
    private static final Instant ZERO = Instant.parse("2026-01-01T00:00:00Z");
    private static final Configuration QUEUE = new Configuration(16, 2);
    private static final Settings SETTINGS = new Settings(3, 4, Duration.ofSeconds(30), 3.5,
            Duration.ofSeconds(5), 2);
    private static final Request REQUEST = new Request(Series.QUEUE, Scenario.EXECUTOR, null, null, 20, SETTINGS);

    @Test
    void learnsNormalValuesAndMeasuresMedianMadAndThreshold() {
        var result = run(queue(1, 2), queue(2, 3), queue(3, 4), queue(4, 3), queue(5, 4));
        assertEquals(State.READY, result.state());
        assertTrue(result.findings().isEmpty());
        assertEquals(3, result.latest().baselineMedian());
        assertEquals(.5, result.latest().mad());
        assertEquals(3.5 * 1.4826 * .5, result.latest().threshold(), 1e-12);
        assertEquals(4, result.latest().sampleCount());
        assertEquals(ZERO.plusSeconds(1), result.latest().baselineStart());
        assertEquals(ZERO.plusSeconds(4), result.latest().baselineEnd());
    }

    @Test
    void sustainedSpikeUsesZeroMadFallbackAndCooldownKeepsEvidence() {
        var result = run(queue(1, 2), queue(2, 2), queue(3, 2), queue(4, 4), queue(5, 4),
                queue(6, 4), queue(10, 4));
        assertEquals(List.of(Signal.CANDIDATE, Signal.ALERT, Signal.COOLDOWN, Signal.ALERT),
                result.findings().stream().map(Comparison::signal).toList());
        var alert = result.findings().get(1);
        assertEquals(0, alert.mad());
        assertEquals(1, alert.threshold());
        assertEquals(2, alert.deviation());
        assertEquals(queue(5, 4).reportId(), alert.observation().reportId());
        assertEquals(Metric.QUEUE_DEPTH.id(), alert.observation().evidenceId());
        assertEquals(List.of(queue(1, 2).reportId(), queue(2, 2).reportId(), queue(3, 2).reportId()),
                alert.baselineReferences().stream().map(Reference::reportId).toList());
        assertEquals("HISTORICAL_METRIC_INCREASE", incident(alert).code());
    }

    @Test
    void transientAndMissingSamplesDoNotProduceAnAlert() {
        var gap = sample(5, null, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks");
        var result = run(queue(1, 2), queue(2, 2), queue(3, 2), queue(4, 4), gap, queue(6, 4), queue(7, 2));
        assertEquals(List.of(Signal.CANDIDATE, Signal.CANDIDATE),
                result.findings().stream().map(Comparison::signal).toList());
        assertEquals(1, result.skippedSamples());
        assertEquals(Signal.NORMAL, result.latest().signal());
    }

    @Test
    void rejectsWrongUnitsInvalidValuesAndClockSkewAndKeepsSeriesSeparate() {
        var result = run(queue(1, 2),
                sample(2, 2.0, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "bytes"),
                sample(3, Double.NaN, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks"),
                sample(4, Double.POSITIVE_INFINITY, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks"),
                sample(5, -1.0, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks"),
                sample(6, 17.0, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks"),
                sample(7, 2.0, QUEUE, Metric.QUEUE_DEPTH, Scenario.CPU, "tasks"),
                sample(8, 2.0, QUEUE, Metric.COMPLETED_TASKS, Scenario.EXECUTOR, "tasks"),
                queue(300, 2));
        assertEquals(State.INSUFFICIENT_DATA, result.state());
        assertEquals(1, result.usableSamples());
        assertEquals(6, result.skippedSamples());
        assertNull(result.latest());
    }

    @Test
    void changedCapacityOrExpiredWindowRequiresNewBaseline() {
        var baseline = List.of(queue(1, 2), queue(2, 2), queue(3, 2), queue(4, 4));
        var newPool = sample(5, 4.0, new Configuration(16, 3), Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks");
        var changed = new ArrayList<>(baseline);
        changed.add(newPool);
        assertEquals(State.INSUFFICIENT_DATA, analyze(changed, REQUEST, ZERO.plusSeconds(80)).state());
        changed.set(4, queue(80, 4));
        var expired = analyze(changed, REQUEST, ZERO.plusSeconds(80));
        assertEquals(State.INSUFFICIENT_DATA, expired.state());
        assertEquals(1, expired.baselineSamples());
    }

    @Test
    void sortingDuplicateRejectionAndLimitsAreDeterministic() {
        var data = new ArrayList<>(List.of(queue(1, 2), queue(2, 2), queue(3, 2), queue(4, 2),
                queue(4, 2), queue(5, 4), queue(6, 4), queue(7, 4)));
        var bounded = new Request(Series.QUEUE, Scenario.EXECUTOR, ZERO.plusSeconds(1), ZERO.plusSeconds(7), 1, SETTINGS);
        var ordered = analyze(data, bounded, ZERO.plusSeconds(80));
        Collections.reverse(data);
        assertEquals(ordered, analyze(data, bounded, ZERO.plusSeconds(80)));
        assertEquals(2, ordered.skippedSamples());
        assertEquals(1, ordered.findings().size());
        assertEquals(Signal.COOLDOWN, ordered.findings().getFirst().signal());
        assertThrows(IllegalArgumentException.class, () ->
                analyze(Collections.nCopies(MAX_SAMPLES + 1, queue(1, 2)), bounded, ZERO.plusSeconds(80)));
    }

    @Test
    void heapUsesByteThresholdAndRecordedLimit() {
        var request = new Request(Series.HEAP, Scenario.JVM, null, null, 10, SETTINGS);
        var points = new ArrayList<Sample>();
        var config = new Configuration(200_000_000, 0);
        for (int i = 1; i <= 5; i++) {
            points.add(sample(i, i < 4 ? 2_000_000.0 : 4_000_000.0, config,
                    Metric.HEAP_USED, Scenario.JVM, "bytes"));
        }
        var result = analyze(points, request, ZERO.plusSeconds(80));
        assertEquals(Signal.ALERT, result.latest().signal());
        assertEquals(1_048_576, result.latest().threshold());
        assertThrows(IllegalArgumentException.class, () -> HistoricalQuery.parse(java.util.Map.of("sensitivity", "NaN")));
    }

    private static Result run(Sample... input) { return analyze(List.of(input), REQUEST, ZERO.plusSeconds(80)); }

    private static Sample queue(long second, double value) {
        return sample(second, value, QUEUE, Metric.QUEUE_DEPTH, Scenario.EXECUTOR, "tasks");
    }

    private static Sample sample(long second, Double value, Configuration config, Metric metric,
                                 Scenario scenario, String unit) {
        return new Sample(new UUID(0, second), ZERO.plusSeconds(second), scenario, metric, unit, value, config);
    }
}
