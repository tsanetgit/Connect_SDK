package com.tsanet.api.connectapi.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;

/**
 * Interrupts a thread once it is inside {@link Thread#sleep}, for tests of an interrupt that lands
 * during a real retry wait. Waiting for the sleep, rather than a fixed delay, keeps the interrupt
 * inside the wait even when the thread is slow to reach it. A timed wait that isn't a sleep, such
 * as a timed future, doesn't count: the target must be {@code TIMED_WAITING} with
 * {@code Thread.sleep} on its stack.
 */
final class SleepInterrupter {

    private static final Duration DEADLINE = Duration.ofSeconds(1);

    private final CompletableFuture<Boolean> interruptedInsideSleep = new CompletableFuture<>();
    private final Thread helper;

    private SleepInterrupter(Thread target) {
        helper = Thread.ofPlatform().start(() -> {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (!insideSleep(target)) {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                    interruptedInsideSleep.complete(false);
                    return;
                }
                LockSupport.parkNanos(1_000_000);
            }
            target.interrupt();
            interruptedInsideSleep.complete(true);
        });
    }

    /** Starts watching {@code target}, which is interrupted once it is inside {@link Thread#sleep}. */
    static SleepInterrupter watch(Thread target) {
        return new SleepInterrupter(target);
    }

    /**
     * Stops the helper and says whether it interrupted the target inside a sleep. Call it with the
     * caller's interrupt flag cleared: {@link Thread#join} throws on an interrupted thread.
     */
    boolean stopAndReport() throws InterruptedException {
        helper.interrupt();
        helper.join();
        return interruptedInsideSleep.getNow(false);
    }

    private static boolean insideSleep(Thread target) {
        if (target.getState() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : target.getStackTrace()) {
            if (frame.getClassName().equals("java.lang.Thread") && frame.getMethodName().startsWith("sleep")) {
                return true;
            }
        }
        return false;
    }
}
