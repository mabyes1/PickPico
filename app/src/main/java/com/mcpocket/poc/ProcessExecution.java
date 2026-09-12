package com.mcpocket.poc;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns stdin on one writer thread; the caller never closes a pipe during a write. */
final class ProcessExecution {
    interface Terminator { void terminate(boolean force); }

    private final Process process;
    private final Terminator terminator;
    private final CountDownLatch inputFinished = new CountDownLatch(1);
    private final CountDownLatch supervisionFinished = new CountDownLatch(1);
    private final AtomicBoolean terminationStarted = new AtomicBoolean();
    private boolean forceTerminationStarted;
    private volatile boolean cancelled;
    private volatile boolean inputCancelled;
    private volatile boolean timedOut;
    private volatile boolean inputTimedOut;
    private volatile long inputFinishedAtNanos;
    private volatile String stdinError = "";
    private volatile String supervisionError = "";

    ProcessExecution(Process process, byte[] input, long deadlineNanos,
                     boolean background, Terminator terminator) {
        this.process = process;
        this.terminator = terminator;
        Thread writer = new Thread(() -> writeInput(input, deadlineNanos), "pickpico-exec-stdin");
        Thread supervisor = new Thread(() -> supervise(deadlineNanos, background),
                "pickpico-exec-deadline");
        writer.setDaemon(true);
        supervisor.setDaemon(true);
        writer.start();
        supervisor.start();
    }

    private void writeInput(byte[] input, long deadlineNanos) {
        try (OutputStream stream = process.getOutputStream()) {
            if (!cancelled && remainingNanos(deadlineNanos) == 0L) {
                inputTimedOut = true;
                timedOut = true;
            } else if (!cancelled && input.length > 0) {
                stream.write(input);
                stream.flush();
            }
        } catch (Exception error) {
            stdinError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        } finally {
            inputFinishedAtNanos = System.nanoTime();
            inputFinished.countDown();
        }
    }

    private void supervise(long deadlineNanos, boolean background) {
        try {
            if (!inputFinished.await(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)
                    || inputFinishedAtNanos - deadlineNanos > 0L || inputTimedOut) {
                if (!cancelled) {
                    inputTimedOut = true;
                    timedOut = true;
                    terminateOnce(true);
                }
                return;
            }
            if (cancelled) return;
            if (!stdinError.isEmpty()) {
                terminateOnce(true);
                return;
            }
            // Background servers retain their lifetime semantics. Only startup
            // and stdin have a deadline; successful delivery disarms this wait.
            if (!background && !process.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                if (!cancelled) {
                    timedOut = true;
                    terminateOnce(true);
                }
            }
        } catch (InterruptedException error) {
            if (!inputFinished()) inputCancelled = true;
            cancelled = true;
            terminateOnce(true);
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            supervisionError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            terminateOnce(true);
        } finally {
            // Kill the group first: closing stdin here could wait indefinitely
            // for the writer's stream lock. Only the writer owns stream.close().
            if (terminationStarted.get()) awaitInputCleanup();
            supervisionFinished.countDown();
        }
    }

    void await() throws InterruptedException { supervisionFinished.await(); }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return supervisionFinished.await(timeout, unit);
    }

    void stop(boolean force) {
        if (!inputFinished()) inputCancelled = true;
        cancelled = true;
        terminateOnce(force);
        awaitInputCleanup();
        // The shell may exit on SIGTERM while a child still holds stdin open.
        // Escalate the group even when the parent Process is already dead.
        if (!inputFinished()) {
            terminateOnce(true);
            awaitInputCleanup();
        }
    }

    private synchronized void terminateOnce(boolean force) {
        if (terminationStarted.get() && (!force || forceTerminationStarted)) return;
        terminationStarted.set(true);
        if (force) forceTerminationStarted = true;
        terminator.terminate(force);
    }

    private void awaitInputCleanup() {
        try {
            inputFinished.await(250L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    boolean timedOut() { return timedOut; }
    boolean inputFinished() { return inputFinished.getCount() == 0L; }
    String stdinError() { return stdinError; }
    String error() { return supervisionError; }
    String stdinState() {
        if (inputTimedOut) return "timed_out";
        if (inputCancelled) return "cancelled";
        if (!stdinError.isEmpty()) return "failed";
        return inputFinished() ? "completed" : "writing";
    }
}
