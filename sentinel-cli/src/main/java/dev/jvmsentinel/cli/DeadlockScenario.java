package dev.jvmsentinel.cli;

import java.util.concurrent.CountDownLatch;

/** Deliberately deadlocks daemon platform threads for a safe, local-only demo. */
final class DeadlockScenario {
    private DeadlockScenario() { }

    static void start() throws InterruptedException {
        Object first = new Object();
        Object second = new Object();
        CountDownLatch bothOwnFirstLock = new CountDownLatch(2);
        Thread a = makeWorker("deadlock-demo-a", first, second, bothOwnFirstLock);
        Thread b = makeWorker("deadlock-demo-b", second, first, bothOwnFirstLock);
        a.start();
        b.start();
        if (!bothOwnFirstLock.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Demo workers did not start");
        }
        Thread.sleep(200); // Let ThreadMXBean observe both workers waiting.
    }

    private static Thread makeWorker(String name, Object own, Object other, CountDownLatch ready) {
        Thread worker = new Thread(() -> {
            synchronized (own) {
                ready.countDown();
                try {
                    ready.await();
                    synchronized (other) { /* unreachable by design */ }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, name);
        worker.setDaemon(true); // Deliberate deadlock must not keep the demo process alive.
        return worker;
    }
}
