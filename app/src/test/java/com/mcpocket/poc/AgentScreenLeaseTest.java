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

    @Test public void backgroundCapabilitiesNeverOwnTheScreen() {
        assertFalse(AgentScreenLease.isActivity("node.info"));
        assertFalse(AgentScreenLease.isActivity("phone.status"));
        assertFalse(AgentScreenLease.isActivity("notification.list"));
        assertFalse(AgentScreenLease.isActivity("location.get"));
        assertFalse(AgentScreenLease.isActivity("app.update_check"));
        assertFalse(AgentScreenLease.isActivity("picoadb.status"));
        assertFalse(AgentScreenLease.isActivity("process.exec"));
        assertFalse(AgentScreenLease.isActivity("picoadb.discover"));
        assertFalse(AgentScreenLease.isActivity("picoadb.battery_diagnostics"));
        assertTrue(AgentScreenLease.isActivity("ui.inspect"));
        assertTrue(AgentScreenLease.isActivity("ui.action"));
        assertTrue(AgentScreenLease.isActivity("screen.capture"));
        assertTrue(AgentScreenLease.isActivity("app.launch"));
        assertTrue(AgentScreenLease.isActivity("url.open"));
    }

    @Test public void taskBridgesCallsAndTerminalUpdateReleases() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100L);
        lease.started("ui.inspect", 200L);
        lease.finished("ui.inspect");
        assertTrue(lease.holdsScreen(30_000L));
        lease.tasksChanged(0, 30_001L);
        assertFalse(lease.holdsScreen(30_001L));
    }

    @Test public void abandonedTaskExpiresAndRealActivityRenews() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100L);
        assertTrue(lease.holdsScreen(100L + AgentScreenLease.IDLE_MS - 1L));
        lease.expireIfNeeded(100L + AgentScreenLease.IDLE_MS);
        assertFalse(lease.holdsScreen(100L + AgentScreenLease.IDLE_MS));
        // Background commands must not re-arm an expired task lease.
        assertFalse(lease.started("phone.status", 190_000L));
        assertFalse(lease.holdsScreen(190_001L));
        // Screen-driving commands renew the lease for an open task.
        lease.started("ui.inspect", 200_000L);
        lease.finished("ui.inspect");
        assertTrue(lease.holdsScreen(200_001L));
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
