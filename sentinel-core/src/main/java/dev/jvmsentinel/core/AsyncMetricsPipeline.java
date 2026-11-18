package dev.jvmsentinel.core;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Runs a finite sequence of ordered collection and delivery attempts. */
public final class AsyncMetricsPipeline implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final System.Logger LOGGER = System.getLogger(AsyncMetricsPipeline.class.getName());

    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor worker;
    private final JvmMetricsCollector collector;
    private final Consumer<JvmSnapshot> consumer;
    private final long intervalMillis;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicLong collected = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong collectionFailures = new AtomicLong();
    private final AtomicLong processingFailures = new AtomicLong();

    // Accessed under the pipeline monitor, which serializes scheduling and shutdown.
    private int remaining;
    private long accepted;
    private long droppedTicks;
    private long retryAttempts;
    private boolean retryPending;
    private boolean started;
    private boolean closed;

    public AsyncMetricsPipeline(JvmMetricsCollector collector, Consumer<JvmSnapshot> consumer,
                                int iterations, long intervalMillis, int queueCapacity) {
        this.collector = Objects.requireNonNull(collector, "collector");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        if (iterations < 1 || intervalMillis < 0 || queueCapacity < 1) {
            throw new IllegalArgumentException("iterations and queueCapacity must be positive and intervalMillis nonnegative");
        }
        remaining = iterations;
        this.intervalMillis = Math.max(1, intervalMillis);
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueCapacity);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue,
                Thread.ofPlatform().daemon().name("sentinel-metrics-worker").factory(),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("sentinel-metrics-scheduler").factory());
    }

    /** Starts sampling immediately. A second start or a start after close is rejected. */
    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("Pipeline can only start once and must be open");
        }
        started = true;
        scheduler.scheduleWithFixedDelay(this::submit, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void submit() {
        if (closed || completion.isDone()) {
            return;
        }
        boolean last = remaining == 1;
        if (retryPending) {
            retryAttempts++;
        }
        try {
            worker.execute(() -> runAttempt(last));
            accepted++;
            retryPending = false;
            if (--remaining == 0) {
                scheduler.shutdown();
            }
        } catch (RejectedExecutionException rejected) {
            droppedTicks++;
            retryPending = true;
        }
    }

    private void runAttempt(boolean last) {
        if (completion.isDone()) {
            return;
        }
        try {
            collectAndProcess();
            if (last) {
                completion.complete(null);
            }
        } catch (Error fatal) {
            completion.completeExceptionally(fatal);
            scheduler.shutdown();
            throw fatal;
        }
    }

    private void collectAndProcess() {
        JvmSnapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(collector.collect(), "Collector returned no snapshot");
            collected.incrementAndGet();
        } catch (RuntimeException failure) {
            collectionFailures.incrementAndGet();
            LOGGER.log(System.Logger.Level.WARNING, "JVM metrics collection failed", failure);
            return;
        }
        try {
            consumer.accept(snapshot);
            processed.incrementAndGet();
        } catch (RuntimeException failure) {
            processingFailures.incrementAndGet();
            LOGGER.log(System.Logger.Level.WARNING, "JVM metrics processing failed", failure);
        }
    }

    /** Waits for all scheduled attempts, including attempts that recorded a recoverable failure. */
    public void awaitCompletion() throws InterruptedException, ExecutionException {
        synchronized (this) {
            if (!started && !closed) {
                throw new IllegalStateException("Start the pipeline before waiting for completion");
            }
        }
        completion.get();
    }

    public synchronized PipelineStats stats() {
        // Read downstream counters first so the snapshot respects upstream totals.
        long processedCount = processed.get();
        long processingFailureCount = processingFailures.get();
        long collectedCount = collected.get();
        return new PipelineStats(collectedCount, accepted, processedCount, droppedTicks,
                collectionFailures.get(), processingFailureCount, retryAttempts, worker.getQueue().size());
    }

    /** Live counters and queue depth may change during a run. Accepted counts collection jobs. */
    public record PipelineStats(long collected, long accepted, long processed, long dropped,
                                long collectionFailures, long processingFailures,
                                long retryAttempts, int queueDepth) { }

    /** The first owner call stops scheduling and drains accepted jobs. Subsequent calls return. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            scheduler.shutdownNow();
            worker.shutdown();
        }
        try {
            if (!worker.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                completion.cancel(false);
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            completion.cancel(false);
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            completion.cancel(false);
        }
    }
}
