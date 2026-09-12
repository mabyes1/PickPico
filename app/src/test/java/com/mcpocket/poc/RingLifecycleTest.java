package com.mcpocket.poc;

import android.media.Ringtone;
import android.os.Handler;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class RingLifecycleTest {
    @Test public void stoppingRingPreservesUpdateScreenAndRecoveryCallbacks() throws Exception {
        McpNodeService service = mock(McpNodeService.class, CALLS_REAL_METHODS);
        Handler handler = mock(Handler.class);
        Ringtone ring = mock(Ringtone.class);
        Runnable stopRing = () -> {};
        Runnable update = () -> {};
        Runnable releaseScreen = () -> {};
        Runnable recovery = () -> {};
        Set<Runnable> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(queued, stopRing, update, releaseScreen, recovery);
        doAnswer(call -> { queued.remove(call.getArgument(0)); return null; })
                .when(handler).removeCallbacks(any(Runnable.class));
        doAnswer(call -> { queued.clear(); return null; })
                .when(handler).removeCallbacksAndMessages(any());
        set(service, "mainHandler", handler);
        set(service, "ringStopRunnable", stopRing);
        set(service, "activeRing", ring);
        set(service, "previousAlarmVolume", -1);
        stop(service);
        assertFalse(queued.contains(stopRing));
        assertEquals(3, queued.size());
        assertTrue(queued.contains(update));
        assertTrue(queued.contains(releaseScreen));
        assertTrue(queued.contains(recovery));
        verify(ring).stop();
        verify(handler, never()).removeCallbacksAndMessages(any());
        stop(service);
        verify(ring, times(1)).stop();
    }

    @Test public void ringtoneFailureStillOnlyCancelsItsOwnCallback() throws Exception {
        McpNodeService service = mock(McpNodeService.class, CALLS_REAL_METHODS);
        Handler handler = mock(Handler.class);
        Ringtone ring = mock(Ringtone.class);
        Runnable stopRing = () -> {};
        doThrow(new IllegalStateException("test ringtone unavailable")).when(ring).stop();
        set(service, "mainHandler", handler);
        set(service, "ringStopRunnable", stopRing);
        set(service, "activeRing", ring);
        set(service, "previousAlarmVolume", -1);
        stop(service);
        verify(handler).removeCallbacks(same(stopRing));
        verify(handler, never()).removeCallbacksAndMessages(any());
        stop(service);
        verify(ring, times(1)).stop();
    }

    private static void set(McpNodeService service, String name, Object value) throws Exception {
        Field field = McpNodeService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static void stop(McpNodeService service) throws Exception {
        Method method = McpNodeService.class.getDeclaredMethod("stopAlertSound");
        method.setAccessible(true);
        method.invoke(service);
    }
}
