package com.mcpocket.poc;

import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class AccessibilityScreenshotLifecycleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private McpAccessibilityService service;
    private HardwareBuffer buffer;
    private ScreenshotResult screenshot;
    private final AtomicReference<TakeScreenshotCallback> callback = new AtomicReference<>();

    @Before public void setUp() {
        service = mock(McpAccessibilityService.class);
        buffer = mock(HardwareBuffer.class);
        screenshot = mock(ScreenshotResult.class);
        when(screenshot.getHardwareBuffer()).thenReturn(buffer);
        when(service.getMainExecutor()).thenReturn(Runnable::run);
        when(service.getFilesDir()).thenReturn(temp.getRoot());
        doAnswer(call -> { callback.set(call.getArgument(2)); return null; })
                .when(service).takeScreenshot(eq(Display.DEFAULT_DISPLAY), any(Executor.class), any());
    }

    @Test public void timeoutClosesLateScreenshotBuffer() throws Exception {
        JSONObject result = McpAccessibilityService.screenCapture(service, new JSONObject(), 1L, 0L);
        assertEquals("accessibility_screen_capture_timeout", result.getString("error"));
        verifyNoInteractions(buffer);
        callback.get().onSuccess(screenshot);
        verify(buffer, times(1)).close();
    }

    @Test public void interruptedWaitClosesLateBufferAndPreservesInterrupt() throws Exception {
        JSONObject result;
        Thread.currentThread().interrupt();
        try {
            result = McpAccessibilityService.screenCapture(service, new JSONObject(), 1L, 1000L);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("accessibility_screen_capture_interrupted", result.getString("error"));
        callback.get().onSuccess(screenshot);
        verify(buffer, times(1)).close();
    }

    @Test public void requestExceptionClosesAlreadyDeliveredBuffer() throws Exception {
        doAnswer(call -> {
            TakeScreenshotCallback delivered = call.getArgument(2);
            delivered.onSuccess(screenshot);
            throw new IllegalStateException("injected request failure");
        }).when(service).takeScreenshot(anyInt(), any(Executor.class), any());
        JSONObject result = McpAccessibilityService.screenCapture(service, new JSONObject(), 1L, 0L);
        assertFalse(result.getBoolean("captured"));
        verify(buffer, times(1)).close();
    }

    @Test public void conversionFailureClosesOwnedBuffer() throws Exception {
        deliverImmediately();
        try (MockedStatic<Bitmap> bitmaps = mockStatic(Bitmap.class)) {
            bitmaps.when(() -> Bitmap.wrapHardwareBuffer(buffer, null))
                    .thenThrow(new IllegalStateException("injected conversion failure"));
            JSONObject result = McpAccessibilityService.screenCapture(service, new JSONObject(), 1L, 0L);
            assertFalse(result.getBoolean("captured"));
        }
        verify(buffer, times(1)).close();
    }

    @Test public void successfulEncodingClosesBufferAndBothBitmapsOnce() throws Exception {
        deliverImmediately();
        Bitmap hardware = mock(Bitmap.class);
        Bitmap bitmap = mock(Bitmap.class);
        when(hardware.copy(Bitmap.Config.ARGB_8888, false)).thenReturn(bitmap);
        when(bitmap.getWidth()).thenReturn(2);
        when(bitmap.getHeight()).thenReturn(2);
        when(bitmap.compress(any(), anyInt(), any(OutputStream.class))).thenAnswer(call -> {
            ((OutputStream) call.getArgument(2)).write(new byte[]{1, 2, 3});
            return true;
        });
        try (MockedStatic<Bitmap> bitmaps = mockStatic(Bitmap.class)) {
            bitmaps.when(() -> Bitmap.wrapHardwareBuffer(buffer, null)).thenReturn(hardware);
            JSONObject result = McpAccessibilityService.screenCapture(service,
                    new JSONObject().put("returnContent", false), 1L, 0L);
            assertTrue(result.getBoolean("captured"));
            assertEquals(3, result.getInt("sizeBytes"));
        }
        verify(buffer, times(1)).close();
        verify(hardware, times(1)).recycle();
        verify(bitmap, times(1)).recycle();
    }

    private void deliverImmediately() {
        doAnswer(call -> {
            ((TakeScreenshotCallback) call.getArgument(2)).onSuccess(screenshot);
            return null;
        }).when(service).takeScreenshot(anyInt(), any(Executor.class), any());
    }
}
