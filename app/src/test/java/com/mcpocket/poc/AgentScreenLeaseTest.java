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
        assertTrue(AgentScreenLease.isActivity("ui.inspect"));
        assertTrue(AgentScreenLease.isActivity("process.exec"));
    }
}
