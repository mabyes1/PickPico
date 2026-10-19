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

        service.onAgentCommandStarted("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(1, lease.activeOperations());

        PowerManager.WakeLock lock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        if (lock != null) assertTrue(lock.isHeld());

        service.onAgentCommandFinished("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, lease.activeOperations());
        PowerManager.WakeLock released = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(released == null || !released.isHeld());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
    }

    @Test public void backgroundCommandNeverAcquiresWakeLock() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentCommandStarted("process.exec");
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(0, lease.activeOperations());
        assertNull(ReflectionHelpers.getField(service, "agentScreenWakeLock"));
    }

    @Test public void taskExtendsUnlockWithoutHoldingDisplayBetweenCommandsAndRestoresOnCompletion() throws Exception {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        shadowOf(service).grantPermissions(ExtendUnlockControl.PERMISSION);
        android.provider.Settings.Secure.putLong(
                service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, 5_000L);
        android.provider.Settings.Secure.putInt(
                service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, 1);

        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(0, lease.activeOperations());
        assertFalse("Task alone must not hold screen bright", lease.holdsScreen(android.os.SystemClock.elapsedRealtime()));
        assertTrue("Task must extend unlock for 3 minutes", lease.extendsUnlock(android.os.SystemClock.elapsedRealtime()));
        PowerManager.WakeLock idleLock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(idleLock == null || !idleLock.isHeld());

        // Lock-after-timeout is extended to 180_000ms and instant power-button lock disabled
        assertEquals(
                180_000L,
                android.provider.Settings.Secure.getLong(
                        service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, -1L));
        assertEquals(
                0,
                android.provider.Settings.Secure.getInt(
                        service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, -1));

        // While a Tier 1 command runs, the display wake lock is held; when it finishes, wake lock releases
        // while extend-unlock remains active.
        service.onAgentCommandStarted("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();
        PowerManager.WakeLock activeLock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertNotNull(activeLock);
        assertTrue(activeLock.isHeld());

        service.onAgentCommandFinished("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();
        PowerManager.WakeLock afterCommandLock = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(afterCommandLock == null || !afterCommandLock.isHeld());
        assertTrue(lease.extendsUnlock(android.os.SystemClock.elapsedRealtime()));

        // Completing the task restores the owner's exact original lock settings
        service.onAgentTasksChanged(0);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(lease.extendsUnlock(android.os.SystemClock.elapsedRealtime()));
        assertEquals(
                5_000L,
                android.provider.Settings.Secure.getLong(
                        service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, -1L));
        assertEquals(
                1,
                android.provider.Settings.Secure.getInt(
                        service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, -1));
    }

    @Test public void hungOperationIsReleasedByWatchdog() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();

        service.onAgentCommandStarted("ui.inspect");
        shadowOf(Looper.getMainLooper()).idle();

        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertEquals(1, lease.activeOperations());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentScreenLease.WATCHDOG_MS));

        assertEquals(0, lease.activeOperations());
        PowerManager.WakeLock released = ReflectionHelpers.getField(service, "agentScreenWakeLock");
        assertTrue(released == null || !released.isHeld());
        assertNull(ReflectionHelpers.getField(service, "agentScreenKeepAwakeView"));
    }

    @Test public void abandonedTaskRestoresExtendUnlockSettingsAfterThreeMinutes() {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        shadowOf(service).grantPermissions(ExtendUnlockControl.PERMISSION);
        android.provider.Settings.Secure.putLong(
                service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, 10_000L);
        android.provider.Settings.Secure.putInt(
                service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, 1);

        service.onAgentTasksChanged(1);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(
                180_000L,
                android.provider.Settings.Secure.getLong(
                        service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, -1L));

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AgentScreenLease.IDLE_MS));
        AgentScreenLease lease = ReflectionHelpers.getField(service, "agentScreenLease");
        assertFalse(lease.extendsUnlock(android.os.SystemClock.elapsedRealtime()));
        assertEquals(
                10_000L,
                android.provider.Settings.Secure.getLong(
                        service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, -1L));
        assertEquals(
                1,
                android.provider.Settings.Secure.getInt(
                        service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, -1));
    }

    @Test public void phoneExtendUnlockDynamicToolExtendsQueriesAndReleases() throws Exception {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        shadowOf(service).grantPermissions(ExtendUnlockControl.PERMISSION);
        android.provider.Settings.Secure.putLong(
                service.getContentResolver(), ExtendUnlockControl.KEY_LOCK_AFTER_TIMEOUT, 5_000L);
        android.provider.Settings.Secure.putInt(
                service.getContentResolver(), ExtendUnlockControl.KEY_POWER_BUTTON_INSTANTLY_LOCKS, 1);

        org.json.JSONObject extended = service.phoneExtendUnlock(
                new org.json.JSONObject().put("action", "extend").put("durationSeconds", 180), 1L);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(extended.getBoolean("extended"));
        assertTrue(extended.getBoolean("active"));
        assertTrue(extended.getBoolean("manualLeaseActive"));
        assertEquals(180_000L, extended.getLong("lockAfterTimeoutMs"));

        org.json.JSONObject status = service.phoneExtendUnlock(
                new org.json.JSONObject().put("action", "status"), 2L);
        assertTrue(status.getBoolean("active"));

        org.json.JSONObject released = service.phoneExtendUnlock(
                new org.json.JSONObject().put("action", "release"), 3L);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(released.getBoolean("active"));
        assertEquals(5_000L, released.getLong("lockAfterTimeoutMs"));
        assertTrue(released.getBoolean("powerButtonInstantlyLocks"));
    }

    @Test public void tierOneWakesUnlockedDarkScreenWhileTierTwoStaysDarkAndBothPendWhenLocked() throws Exception {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).create().get();
        android.app.KeyguardManager keyguard =
                (android.app.KeyguardManager) service.getSystemService(android.content.Context.KEYGUARD_SERVICE);
        PowerManager power =
                (PowerManager) service.getSystemService(android.content.Context.POWER_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(true);

        // When locked, both Tier 1 (ui.inspect) and Tier 2 (picoadb.set_enabled(true)) pend and return UNLOCK_REQUIRED on timeout.
        service.unlockWaitTimeoutMs = 300L;
        org.json.JSONObject blockedTier1 = service.awaitUnlockIfRequired(
                "ui.inspect", "Read UI", new org.json.JSONObject(), 1L);
        assertNotNull(blockedTier1);
        assertEquals("INTERACTIVE_UNLOCKED", blockedTier1.getString("tier"));
        assertEquals("UNLOCK_REQUIRED", blockedTier1.getJSONObject("error").getString("code"));

        org.json.JSONObject blockedTier2 = service.awaitUnlockIfRequired(
                "picoadb.set_enabled", "Enable wireless debugging", new org.json.JSONObject().put("enabled", true), 2L);
        assertNotNull(blockedTier2);
        assertEquals("UNLOCKED_ONLY", blockedTier2.getString("tier"));
        assertEquals("UNLOCK_REQUIRED", blockedTier2.getJSONObject("error").getString("code"));

        // When unlocked with the screen dark (interactive == false):
        shadowOf(keyguard).setKeyguardLocked(false);
        shadowOf(power).setIsInteractive(false);

        // Tier 2 (picoadb.set_enabled(true)) succeeds immediately WITHOUT waking the display.
        org.json.JSONObject allowedTier2 = service.awaitUnlockIfRequired(
                "picoadb.set_enabled", "Enable wireless debugging", new org.json.JSONObject().put("enabled", true), 3L);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(allowedTier2);
        assertFalse("Tier 2 must not wake a dark unlocked screen", power.isInteractive());
    }
}
