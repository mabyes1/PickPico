package com.mcpocket.poc;

import android.os.Looper;
import android.os.PowerManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AgentScreenServiceTest {
    @Test public void operationHoldsScreenAndFinishReleasesImmediately() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentCommandStarted("process.exec");
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(1, lease.activeOperations());

        PowerManager.WakeLock lock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        if (lock != null) assertTrue(lock.isHeld());

        service.onAgentCommandFinished("process.exec");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, lease.activeOperations());
        PowerManager.WakeLock released = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(released == null || !released.isHeld());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
    }

    @Test public void taskStateAloneNeverOwnsScreen() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(0, lease.activeOperations());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
        assertNull(ReflectionHelpers.getField(service, "agentScreenWakeLock"));
    }

    @Test public void hungOperationIsReleasedByWatchdog() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentCommandStarted("process.exec");
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(1, lease.activeOperations());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentScreenLease.WATCHDOG_MS));

        assertEquals(0, lease.activeOperations());
        PowerManager.WakeLock released = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(released == null || !released.isHeld());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
    }
}
