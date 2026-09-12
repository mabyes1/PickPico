package com.mcpocket.poc;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class CaptureResourcesTest {
    @Test public void callbackRacingWithTimeoutClosesBufferExactlyOnce() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                CaptureResources resources = new CaptureResources();
                AtomicInteger closes = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                java.util.concurrent.Future<?> callback = executor.submit(() -> {
                    start.await();
                    resources.add(() -> closes.incrementAndGet());
                    return null;
                });
                java.util.concurrent.Future<?> timeout = executor.submit(() -> {
                    start.await();
                    resources.close();
                    return null;
                });
                start.countDown();
                callback.get(2, TimeUnit.SECONDS);
                timeout.get(2, TimeUnit.SECONDS);
                resources.close();
                assertEquals(1, closes.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void timeoutClosesLateCameraAndLateSession() {
        CaptureResources resources = new CaptureResources();
        resources.close();
        AtomicInteger closes = new AtomicInteger();
        assertFalse(resources.add(() -> closes.incrementAndGet()));
        assertFalse(resources.add(() -> closes.incrementAndGet()));
        assertEquals(2, closes.get());
    }

    @Test public void cleanupClosesOwnedResourcesInReverseOrderOnce() {
        CaptureResources resources = new CaptureResources();
        List<String> closed = new ArrayList<>();
        AutoCloseable camera = () -> closed.add("camera");
        resources.add(() -> closed.add("reader"));
        resources.add(camera);
        resources.add(camera);
        resources.add(() -> closed.add("session"));
        resources.close();
        resources.close();
        assertEquals(Arrays.asList("session", "camera", "reader"), closed);
    }

    @Test public void failingSessionCloseDoesNotLeakCameraOrReader() {
        CaptureResources resources = new CaptureResources();
        AtomicInteger closes = new AtomicInteger();
        resources.add(() -> closes.incrementAndGet());
        resources.add(() -> closes.incrementAndGet());
        resources.add(() -> { throw new IllegalStateException("session failed"); });
        resources.close();
        assertEquals(2, closes.get());
    }

    @Test public void interruptedWaitStillClosesAlreadyOpenedCamera() throws Exception {
        CaptureResources resources = new CaptureResources();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                resources.add(() -> closes.incrementAndGet());
                opened.countDown();
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } finally {
                resources.close();
                finished.countDown();
            }
        });
        worker.start();
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS));
        } finally {
            worker.interrupt();
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }
}
