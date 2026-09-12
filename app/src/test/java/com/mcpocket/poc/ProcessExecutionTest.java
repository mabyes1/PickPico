package com.mcpocket.poc;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class ProcessExecutionTest {
    private static long deadline(long milliseconds) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }

    @Test(timeout = 5000) public void blockedForegroundInputTimesOutAndClosesAfterGroupTermination() throws Exception {
        BlockingPipe pipe = new BlockingPipe();
        FakeProcess process = new FakeProcess(pipe);
        AtomicInteger stops = new AtomicInteger();
        ProcessExecution run = new ProcessExecution(process, new byte[1024], deadline(100), false,
                force -> { stops.incrementAndGet(); process.destroy(); pipe.release.countDown(); });
        try {
            assertTrue(pipe.entered.await(1, TimeUnit.SECONDS));
            assertTrue(run.await(2, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertEquals("timed_out", run.stdinState());
            assertEquals(1, stops.get());
            assertFalse(process.isAlive());
            assertTrue(run.inputFinished());
            assertTrue(pipe.closed);
        } finally { run.stop(true); pipe.release.countDown(); }
    }

    @Test(timeout = 5000) public void backgroundReturnsBeforeInputAndStillHasInputDeadline() throws Exception {
        BlockingPipe pipe = new BlockingPipe();
        FakeProcess process = new FakeProcess(pipe);
        ProcessExecution run = new ProcessExecution(process, new byte[1024], deadline(500), true,
                force -> { process.destroy(); pipe.release.countDown(); });
        try {
            assertTrue(pipe.entered.await(1, TimeUnit.SECONDS));
            assertFalse(run.inputFinished());
            assertTrue(process.isAlive());
            assertTrue(run.await(2, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertFalse(process.isAlive());
            assertTrue(run.inputFinished());
        } finally { run.stop(true); pipe.release.countDown(); }
    }

    @Test(timeout = 5000) public void successfulBackgroundInputDoesNotLimitServerLifetime() throws Exception {
        FakeProcess process = new FakeProcess(new ByteArrayOutputStream());
        AtomicInteger stops = new AtomicInteger();
        ProcessExecution run = new ProcessExecution(process, new byte[0], deadline(200), true,
                force -> { stops.incrementAndGet(); process.destroy(); });
        try {
            assertTrue(run.await(1, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertEquals("completed", run.stdinState());
            assertFalse(run.timedOut());
            assertTrue(process.isAlive());
            assertEquals(0, stops.get());
        } finally { run.stop(true); }
        assertFalse(process.isAlive());
    }

    @Test(timeout = 5000) public void inputTimeIsDeductedFromTheProcessWait() throws Exception {
        BlockingPipe pipe = new BlockingPipe();
        FakeProcess process = new FakeProcess(pipe);
        process.finishWaitImmediately = true;
        long start = System.nanoTime();
        ProcessExecution run = new ProcessExecution(process, new byte[1], deadline(2000), false,
                force -> process.destroy());
        try {
            assertTrue(pipe.entered.await(1, TimeUnit.SECONDS));
            Thread.sleep(150);
            pipe.release.countDown();
            assertTrue(run.await(1, TimeUnit.SECONDS));
            assertTrue(process.waitNanos > 0);
            assertTrue("stdin must not reset the timeout", process.waitNanos < TimeUnit.MILLISECONDS.toNanos(1900));
            assertTrue(process.waitNanos <= TimeUnit.SECONDS.toNanos(2));
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(150));
        } finally { pipe.release.countDown(); run.stop(true); }
    }

    @Test(timeout = 5000) public void blockingCloseIsAlsoCoveredByInputDeadline() throws Exception {
        CountDownLatch releaseClose = new CountDownLatch(1);
        OutputStream stream = new ByteArrayOutputStream() {
            @Override public void close() { awaitUninterruptibly(releaseClose); }
        };
        FakeProcess process = new FakeProcess(stream);
        ProcessExecution run = new ProcessExecution(process, new byte[0], deadline(100), false,
                force -> { process.destroy(); releaseClose.countDown(); });
        try {
            assertTrue(run.await(2, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertTrue(run.inputFinished());
        } finally { releaseClose.countDown(); run.stop(true); }
    }

    @Test(timeout = 5000) public void brokenPipeIsReportedRatherThanSuccessfulInput() throws Exception {
        FakeProcess process = new FakeProcess(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("test pipe closed"); }
        });
        ProcessExecution run = new ProcessExecution(process, new byte[1], deadline(1000), false,
                force -> process.destroy());
        try {
            assertTrue(run.await(1, TimeUnit.SECONDS));
            assertFalse(run.timedOut());
            assertEquals("failed", run.stdinState());
            assertTrue(run.stdinError().contains("test pipe closed"));
            assertFalse(process.isAlive());
        } finally { run.stop(true); }
    }

    @Test(timeout = 5000) public void stopWhileInputIsBlockedDoesNotCloseFromTheCallerThread() throws Exception {
        BlockingPipe pipe = new BlockingPipe();
        FakeProcess process = new FakeProcess(pipe);
        AtomicInteger stops = new AtomicInteger();
        ProcessExecution run = new ProcessExecution(process, new byte[1], deadline(3000), true,
                force -> { stops.incrementAndGet(); process.destroy(); pipe.release.countDown(); });
        try {
            assertTrue(pipe.entered.await(1, TimeUnit.SECONDS));
            run.stop(true);
            run.stop(true);
            assertTrue(run.await(1, TimeUnit.SECONDS));
            assertEquals(1, stops.get());
            assertEquals("cancelled", run.stdinState());
            assertTrue(run.inputFinished());
            assertEquals("pickpico-exec-stdin", pipe.closingThread);
        } finally { pipe.release.countDown(); run.stop(true); }
    }

    @Test(timeout = 10000) public void realChildThatNeverReadsStdinIsTerminatedWithinDeadline() throws Exception {
        Process process = child("no-read");
        ProcessExecution run = null;
        try {
            assertEquals("READY", new BufferedReader(new InputStreamReader(process.getInputStream())).readLine());
            byte[] input = "\u4e2d".repeat(65536).getBytes(StandardCharsets.UTF_8);
            run = new ProcessExecution(process, input, deadline(200), false, force -> process.destroyForcibly());
            assertTrue(run.await(3, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertFalse(process.isAlive());
            assertTrue(run.inputFinished());
        } finally { process.destroyForcibly(); if (run != null) run.stop(true); process.waitFor(); }
    }

    @Test(timeout = 10000) public void realReaderGetsAllUtf8BytesAndEof() throws Exception {
        Process process = child("read");
        byte[] input = "\u4e2d\u6587\n".repeat(8192).getBytes(StandardCharsets.UTF_8);
        ProcessExecution run = new ProcessExecution(process, input, deadline(4000), false,
                force -> process.destroyForcibly());
        try {
            assertTrue(run.await(5, TimeUnit.SECONDS));
            assertFalse(run.timedOut());
            assertEquals("", run.stdinError());
            assertEquals("completed", run.stdinState());
            assertEquals(String.valueOf(input.length),
                    new BufferedReader(new InputStreamReader(process.getInputStream())).readLine());
            assertEquals(0, process.exitValue());
        } finally { process.destroyForcibly(); run.stop(true); process.waitFor(); }
    }

    @Test(timeout = 5000) public void expiredStartupCannotDeliverInputOrKeepBackgroundAlive() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        FakeProcess process = new FakeProcess(input);
        ProcessExecution run = new ProcessExecution(process, new byte[1024], deadline(-100), true,
                force -> process.destroy());
        try {
            assertTrue(run.await(1, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertEquals("timed_out", run.stdinState());
            assertEquals(0, input.size());
            assertFalse(process.isAlive());
        } finally { run.stop(true); }
    }

    @Test(timeout = 5000) public void stopEscalatesWhenExitedParentLeavesInputHeldByAChild() throws Exception {
        BlockingPipe pipe = new BlockingPipe();
        FakeProcess process = new FakeProcess(pipe);
        AtomicInteger graceful = new AtomicInteger(), forced = new AtomicInteger();
        ProcessExecution run = new ProcessExecution(process, new byte[1], deadline(3000), true, force -> {
            process.destroy();
            if (force) { forced.incrementAndGet(); pipe.release.countDown(); }
            else graceful.incrementAndGet();
        });
        try {
            assertTrue(pipe.entered.await(1, TimeUnit.SECONDS));
            run.stop(false);
            assertTrue(run.await(1, TimeUnit.SECONDS));
            assertEquals(1, graceful.get());
            assertEquals(1, forced.get());
            assertTrue(run.inputFinished());
            assertEquals("cancelled", run.stdinState());
            assertEquals("pickpico-exec-stdin", pipe.closingThread);
            run.stop(true);
            assertEquals(1, forced.get());
        } finally { pipe.release.countDown(); run.stop(true); }
    }

    @Test(timeout = 5000) public void stoppingServerDoesNotRelabelDeliveredInputAsCancelled() throws Exception {
        FakeProcess process = new FakeProcess(new ByteArrayOutputStream());
        ProcessExecution run = new ProcessExecution(process, new byte[]{1, 2, 3}, deadline(2000), true,
                force -> process.destroy());
        try {
            assertTrue(run.await(1, TimeUnit.SECONDS));
            run.stop(false);
            assertEquals("completed", run.stdinState());
            assertFalse(process.isAlive());
        } finally { run.stop(true); }
    }

    @Test(timeout = 5000) public void foregroundDeadlineStillAppliesAfterInputIsDelivered() throws Exception {
        FakeProcess process = new FakeProcess(new ByteArrayOutputStream());
        ProcessExecution run = new ProcessExecution(process, new byte[0], deadline(200), false,
                force -> process.destroy());
        try {
            assertTrue(run.await(2, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertEquals("completed", run.stdinState());
            assertFalse(process.isAlive());
        } finally { run.stop(true); }
    }

    @Test(timeout = 5000) public void blockedFlushIsIncludedInInputDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        OutputStream input = new ByteArrayOutputStream() {
            @Override public void flush() { awaitUninterruptibly(release); }
        };
        FakeProcess process = new FakeProcess(input);
        ProcessExecution run = new ProcessExecution(process, new byte[1], deadline(100), true,
                force -> { process.destroy(); release.countDown(); });
        try {
            assertTrue(run.await(2, TimeUnit.SECONDS));
            assertTrue(run.timedOut());
            assertTrue(run.inputFinished());
        } finally { release.countDown(); run.stop(true); }
    }

    private static Process child(String mode) throws Exception {
        String executable = new File(System.getProperty("java.home"),
                "bin/" + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")).getPath();
        String classes = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        return new ProcessBuilder(executable, "-cp", classes, Child.class.getName(), mode).start();
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            if ("no-read".equals(args[0])) { System.out.println("READY"); Thread.sleep(30000); return; }
            int count = 0, read; byte[] buffer = new byte[4096];
            while ((read = System.in.read(buffer)) != -1) count += read;
            System.out.println(count);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { latch.await(); break; } catch (InterruptedException error) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class BlockingPipe extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean closed;
        volatile String closingThread;
        @Override public void write(int value) { entered.countDown(); awaitUninterruptibly(release); }
        @Override public void write(byte[] bytes, int offset, int length) { write(0); }
        @Override public void close() { closed = true; closingThread = Thread.currentThread().getName(); }
    }

    private static final class FakeProcess extends Process {
        final OutputStream input;
        final CountDownLatch exited = new CountDownLatch(1);
        volatile long waitNanos;
        volatile boolean finishWaitImmediately;
        FakeProcess(OutputStream input) { this.input = input; }
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { exited.await(); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitNanos = unit.toNanos(timeout);
            if (finishWaitImmediately) { destroy(); return true; }
            return exited.await(timeout, unit);
        }
        @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { exited.countDown(); }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return exited.getCount() != 0; }
    }
}
