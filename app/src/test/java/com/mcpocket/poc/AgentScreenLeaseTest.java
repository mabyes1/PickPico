package com.mcpocket.poc;

import org.junit.Test;

import static org.junit.Assert.*;

public class AgentScreenLeaseTest {
    @Test public void firstOperationAcquiresAndLastFinishReleases() {
        AgentScreenLease lease = new AgentScreenLease();

        assertTrue(lease.started("ui.inspect", 100L));
        assertEquals(1, lease.activeOperations());
        assertFalse(lease.started("ui.action", 200L));
        assertEquals(2, lease.activeOperations());

        assertFalse(lease.finished("ui.inspect"));
        assertEquals(1, lease.activeOperations());
        assertTrue(lease.finished("ui.action"));
        assertEquals(0, lease.activeOperations());
        assertEquals(0L, lease.remaining(300L));
    }

    @Test public void watchdogOnlyRecoversHungOperation() {
        AgentScreenLease lease = new AgentScreenLease();

        lease.started("ui.inspect", 100L);
        assertTrue(lease.remaining(100L) > 0L);
        assertFalse(lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS - 1L));
        assertTrue(lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS));
        assertEquals(0, lease.activeOperations());
    }

    @Test public void lateFinishAfterWatchdogCannotUnderflow() {
        AgentScreenLease lease = new AgentScreenLease();

        lease.started("ui.inspect", 100L);
        lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS);
        assertTrue(lease.finished("ui.inspect"));
        assertEquals(0, lease.activeOperations());
    }

    @Test public void threeTierScreenPolicyClassifiesCapabilitiesCorrectly() throws Exception {
        // Tier 1: INTERACTIVE_UNLOCKED (needs display lit + unlocked)
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("ui.inspect", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("ui.action", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("ui.type", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("ui.scroll", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("screen.capture", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("app.launch", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("url.open", null));
        assertEquals(ScreenPolicy.Tier.INTERACTIVE_UNLOCKED, ScreenPolicy.tier("phone.home", null));

        // Tier 2: UNLOCKED_ONLY (needs unlocked, does NOT need display lit)
        assertEquals(
                ScreenPolicy.Tier.UNLOCKED_ONLY,
                ScreenPolicy.tier("picoadb.set_enabled", new org.json.JSONObject().put("enabled", true)));
        assertEquals(
                ScreenPolicy.Tier.UNLOCKED_ONLY,
                ScreenPolicy.tier("phone.extend_unlock", new org.json.JSONObject().put("action", "extend")));

        // Tier 3: BACKGROUND (needs neither display nor unlock)
        assertEquals(
                ScreenPolicy.Tier.BACKGROUND,
                ScreenPolicy.tier("picoadb.set_enabled", new org.json.JSONObject().put("enabled", false)));
        assertEquals(
                ScreenPolicy.Tier.BACKGROUND,
                ScreenPolicy.tier("phone.extend_unlock", new org.json.JSONObject().put("action", "status")));
        assertEquals(
                ScreenPolicy.Tier.BACKGROUND,
                ScreenPolicy.tier("phone.extend_unlock", new org.json.JSONObject().put("action", "release")));
        assertEquals(ScreenPolicy.Tier.BACKGROUND, ScreenPolicy.tier("phone.status", null));
        assertEquals(ScreenPolicy.Tier.BACKGROUND, ScreenPolicy.tier("notification.list", null));
        assertEquals(ScreenPolicy.Tier.BACKGROUND, ScreenPolicy.tier("picoadb.shell", null));

        // Only Tier 1 + phone.wake hold the physical screen wake lock
        assertFalse(AgentScreenLease.isActivity("picoadb.set_enabled"));
        assertFalse(AgentScreenLease.isActivity("phone.extend_unlock"));
        assertTrue(AgentScreenLease.isActivity("ui.inspect"));
    }

    @Test public void taskExtendsUnlockWithoutHoldingDisplayBetweenCommands() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100L);
        assertFalse("Task alone must not hold display bright", lease.holdsScreen(100L));
        assertTrue("Task must extend unlock for 3 minutes", lease.extendsUnlock(100L));

        lease.started("ui.inspect", 200L);
        assertTrue("Active Tier 1 command holds display", lease.holdsScreen(200L));
        lease.finished("ui.inspect");
        assertFalse("Finishing Tier 1 command releases display hold immediately", lease.holdsScreen(30_000L));
        assertTrue("Task extend-unlock stays active between commands", lease.extendsUnlock(30_000L));

        lease.tasksChanged(0, 30_001L);
        assertFalse(lease.extendsUnlock(30_001L));
    }

    @Test public void abandonedTaskExpiresAndTaskTouchOrCommandRenewsExtendUnlock() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100L);
        assertTrue(lease.extendsUnlock(100L + AgentScreenLease.IDLE_MS - 1L));
        lease.expireIfNeeded(100L + AgentScreenLease.IDLE_MS);
        assertFalse(lease.extendsUnlock(100L + AgentScreenLease.IDLE_MS));

        // Any dispatched agent command or task touch renews extend-unlock for an active task.
        lease.touchTask(190_000L);
        assertTrue(lease.extendsUnlock(190_001L));
    }

    @Test public void manualExtendUnlockWorksIndependentlyOfTasks() {
        AgentScreenLease lease = new AgentScreenLease();
        long remaining = lease.extendUnlock(180_000L, 1_000L);
        assertEquals(180_000L, remaining);
        assertTrue(lease.extendsUnlock(1_000L));
        assertFalse(lease.holdsScreen(1_000L));
        assertTrue(lease.hasManualUnlockLease(1_000L));

        lease.releaseManualUnlock();
        assertFalse(lease.extendsUnlock(2_000L));
    }

    @Test public void zombieTaskAutoExpiresAfterIdleWindow() throws Exception {
        AgentTaskRuntime runtime = new AgentTaskRuntime();
        org.json.JSONObject created = runtime.create(new org.json.JSONObject()
                .put("objective", "Inspect UI")
                .put("agent", "Claude Opus 5.5"));
        String taskId = created.getString("taskId");
        assertEquals(1, runtime.awakeTaskCount());

        long future = System.currentTimeMillis() + AgentTaskRuntime.TASK_IDLE_EXPIRY_MS + 1_000L;
        assertTrue(runtime.expireIdle(future));
        assertEquals(0, runtime.awakeTaskCount());
        assertEquals("expired", runtime.status(new org.json.JSONObject().put("taskId", taskId)).getString("status"));
    }
}
