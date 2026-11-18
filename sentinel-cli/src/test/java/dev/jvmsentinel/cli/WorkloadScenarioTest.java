package dev.jvmsentinel.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class WorkloadScenarioTest {
    @Test
    void capturesFullExecutorAndExactlyOneRejectionThenReleasesWorkers() throws Exception {
        var snapshot = ExecutorSaturationScenario.run();
        assertEquals(2, snapshot.poolSize());
        assertEquals(2, snapshot.activeWorkers());
        assertEquals(4, snapshot.queuedTasks());
        assertEquals(4, snapshot.queueCapacity());
        assertEquals(0, snapshot.completedTasks());
        assertEquals(1, snapshot.rejectedTasks());
        assertTrue(snapshot.elapsedNanos() >= 0);
        for (Thread worker : Thread.getAllStackTraces().keySet()) {
            if (worker.getName().startsWith(ExecutorSaturationScenario.WORKER_PREFIX)) {
                assertTrue(worker.join(Duration.ofSeconds(1)));
            }
        }
    }

    @Test
    void computesForRequestedDurationAndRejectsInvalidBounds() throws Exception {
        Duration duration = Duration.ofMillis(30);
        var result = CpuLoadScenario.run(duration);
        assertTrue(result.elapsedNanos() >= duration.toNanos());
        assertTrue(result.operations() > 0);
        result.threadCpuNanos().ifPresent(cpu -> assertTrue(cpu >= 0));
        assertThrows(IllegalArgumentException.class, () -> CpuLoadScenario.run(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CpuLoadScenario.run(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> CpuLoadScenario.run(Duration.ofSeconds(5).plusNanos(1)));
    }

    @Test
    void interruptsCpuWorkAndJoinsItsWorker() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> CpuLoadScenario.run(Duration.ofSeconds(5)));
        } finally {
            Thread.interrupted();
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().equals("sentinel-cpu-demo")));
    }
}
