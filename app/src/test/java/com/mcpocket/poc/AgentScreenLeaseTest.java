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

        lease.started("process.exec", 100L);
        assertTrue(lease.remaining(100L) > 0L);
        assertFalse(lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS - 1L));
        assertTrue(lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS));
        assertEquals(0, lease.activeOperations());
    }

    @Test public void lateFinishAfterWatchdogCannotUnderflow() {
        AgentScreenLease lease = new AgentScreenLease();

        lease.started("process.exec", 100L);
        lease.expireIfNeeded(100L + AgentScreenLease.WATCHDOG_MS);
        assertTrue(lease.finished("process.exec"));
        assertEquals(0, lease.activeOperations());
    }

    @Test public void healthAndAutomaticChecksDoNotOwnScreen() {
        assertFalse(AgentScreenLease.isActivity("node.info"));
        assertFalse(AgentScreenLease.isActivity("app.update_check"));
        assertFalse(AgentScreenLease.isActivity("picoadb.status"));
        assertTrue(AgentScreenLease.isActivity("ui.inspect"));
        assertTrue(AgentScreenLease.isActivity("process.exec"));
        assertTrue(AgentScreenLease.isActivity("picoadb.discover"));
        assertTrue(AgentScreenLease.isActivity("picoadb.battery_diagnostics"));
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
        lease.started("ui.inspect", 200_000L);
        lease.finished("ui.inspect");
        assertTrue(lease.holdsScreen(200_001L));
    }
}
