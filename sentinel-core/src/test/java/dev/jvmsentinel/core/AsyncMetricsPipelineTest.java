package dev.jvmsentinel.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncMetricsPipelineTest {
    @Test
    void dropsTicksWhenQueueIsFullAndRetriesUntilAllSamplesAreDelivered() throws Exception {
        var firstDelivery = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var captures = new AtomicInteger();
        var delivered = new ArrayList<Long>();

        try (var pipeline = new AsyncMetricsPipeline(
                () -> snapshot(captures.incrementAndGet()),
                snapshot -> {
                    if (snapshot.heapUsedBytes() == 1) {
                        firstDelivery.countDown();
                        await(releaseFirst);
                    }
                    delivered.add(snapshot.heapUsedBytes());
                }, 3, 0, 1)) {
            pipeline.start();
            try {
                assertTrue(firstDelivery.await(5, TimeUnit.SECONDS));
                awaitCondition(() -> pipeline.stats().retryAttempts() >= 1);
                var saturated = pipeline.stats();
                assertEquals(1, saturated.queueDepth());
                assertTrue(saturated.dropped() >= 1);
                assertEquals(1, captures.get());
            } finally {
                releaseFirst.countDown();
            }

            pipeline.awaitCompletion();
            assertEquals(List.of(1L, 2L, 3L), delivered);
            assertEquals(0, pipeline.stats().queueDepth());
        }
    }

    @Test
    void closeDrainsAcceptedWorkAndCancelsIncompleteRun() throws Exception {
        var releaseFirst = new CountDownLatch(1);
        var captures = new AtomicInteger();
        var secondDelivery = new CountDownLatch(1);
        try (var pipeline = new AsyncMetricsPipeline(
                () -> snapshot(captures.incrementAndGet()),
                snapshot -> {
                    if (snapshot.heapUsedBytes() == 1) {
                        await(releaseFirst);
                    } else {
                        secondDelivery.countDown();
                    }
                }, 3, 0, 1)) {
            pipeline.start();
            var closing = Thread.ofPlatform().daemon().unstarted(pipeline::close);
            try {
                awaitCondition(() -> pipeline.stats().dropped() > 0);
                closing.start();
                // The closing thread waits for the worker only after closing the submission queue.
                awaitCondition(() -> closing.getState() == Thread.State.TIMED_WAITING);
            } finally {
                releaseFirst.countDown();
            }

            assertTrue(closing.join(java.time.Duration.ofSeconds(5)));
            assertEquals(0, secondDelivery.getCount());
            assertEquals(2, captures.get());
            assertThrows(CancellationException.class, pipeline::awaitCompletion);
        }
    }

    @Test
    void interruptedCloseCancelsQueuedWorkAndInterruptsActiveWorker() throws Exception {
        var release = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var captures = new AtomicInteger();
        try (var pipeline = new AsyncMetricsPipeline(
                () -> snapshot(captures.incrementAndGet()),
                snapshot -> {
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        workerInterrupted.countDown();
                    }
                }, 3, 0, 1)) {
            pipeline.start();
            try {
                awaitCondition(() -> pipeline.stats().dropped() > 0);
                var closing = Thread.ofPlatform().daemon().start(() -> {
                    Thread.currentThread().interrupt();
                    pipeline.close();
                });
                assertTrue(closing.join(java.time.Duration.ofSeconds(5)));
                assertTrue(closing.isInterrupted());
                assertTrue(workerInterrupted.await(5, TimeUnit.SECONDS));
                assertEquals(1, captures.get());
                assertEquals(0, pipeline.stats().queueDepth());
                assertThrows(CancellationException.class, pipeline::awaitCompletion);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void isolatesCollectionAndProcessingFailuresAndKeepsSampling() throws Exception {
        var attempts = new AtomicInteger();
        var delivered = new ArrayList<Long>();
        try (var pipeline = new AsyncMetricsPipeline(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("collection failed");
            }
            return snapshot(attempt);
        }, snapshot -> {
            if (snapshot.heapUsedBytes() == 2) {
                throw new IllegalStateException("processing failed");
            }
            delivered.add(snapshot.heapUsedBytes());
        }, 3, 0, 1)) {
            pipeline.start();
            pipeline.awaitCompletion();
            var stats = pipeline.stats();
            assertEquals(List.of(3L), delivered);
            assertEquals(3, stats.accepted());
            assertEquals(2, stats.collected());
            assertEquals(1, stats.processed());
            assertEquals(1, stats.collectionFailures());
            assertEquals(1, stats.processingFailures());
        }
    }

    @Test
    void startsOnceAndCannotSampleAfterClose() throws Exception {
        var captures = new AtomicInteger();
        var pipeline = new AsyncMetricsPipeline(() -> snapshot(captures.incrementAndGet()),
                snapshot -> {}, 1, 0, 1);
        try (pipeline) {
            assertThrows(IllegalStateException.class, pipeline::awaitCompletion);
            assertEquals(0, captures.get());
            pipeline.start();
            assertThrows(IllegalStateException.class, pipeline::start);
            pipeline.awaitCompletion();
        }
        pipeline.close();
        assertThrows(IllegalStateException.class, pipeline::start);
        assertEquals(1, captures.get());
        assertEquals(1, pipeline.stats().processed());

        try (var unstarted = new AsyncMetricsPipeline(
                () -> snapshot(captures.incrementAndGet()), snapshot -> {}, 1, 0, 1)) {
            unstarted.close();
            assertThrows(IllegalStateException.class, unstarted::start);
            assertThrows(CancellationException.class, unstarted::awaitCompletion);
            assertEquals(1, captures.get());
        }
    }

    private static JvmSnapshot snapshot(long usedBytes) {
        return new JvmSnapshot(Instant.EPOCH, usedBytes, 100, 1, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Worker was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Timed out waiting for pipeline state");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
