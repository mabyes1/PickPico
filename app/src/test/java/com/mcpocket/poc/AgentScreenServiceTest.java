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

    @Test public void taskKeepsDisplayAcrossCommandsAndCompletionReleases() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(0, lease.activeOperations());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
        assertTrue(lease.holdsScreen(android.os.SystemClock.elapsedRealtime()));
        PowerManager.WakeLock held = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertNotNull(held);
        assertTrue(held.isHeld());
        service.onAgentCommandStarted("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();
        service.onAgentCommandFinished("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(lease.holdsScreen(android.os.SystemClock.elapsedRealtime()));
        service.onAgentTasksChanged(0);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(lease.holdsScreen(android.os.SystemClock.elapsedRealtime()));
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
        PowerManager.WakeLock released = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(released == null || !released.isHeld());
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

    @Test public void abandonedTaskReleasesDisplayWithoutChangingSystemTimeout() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        android.provider.Settings.System.putInt(service.getContentResolver(), "screen_off_timeout", 15000);
        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentScreenLease.IDLE_MS));
        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertFalse(lease.holdsScreen(android.os.SystemClock.elapsedRealtime()));
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
        assertEquals(15000, android.provider.Settings.System.getInt(service.getContentResolver(), "screen_off_timeout", 0));
    }

    @Test public void taskActivityRenewsTheSameLockWithoutASleepGap() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();
        PowerManager.WakeLock lock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertNotNull(lock);
        assertTrue(lock.isHeld());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(170));
        service.onAgentCommandStarted("ui.inspect");
        service.onAgentCommandFinished("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(lock, ReflectionHelpers.getField(service, "agentScreenWakeLock"));
        assertTrue(lock.isHeld());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertTrue("Renewal must outlive the original lease", lock.isHeld());
        service.onAgentTasksChanged(0);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(lock.isHeld());
    }
}
