package com.mcpocket.poc;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentScreenLeaseTest {
    @Test public void abandonedTaskAndNeverFinishedCommandExpire() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100);
        lease.activity(200);
        assertEquals(1, lease.remaining(180199));
        assertEquals(0, lease.remaining(180200));
        assertEquals(0, lease.remaining(999999));
    }
    @Test public void activityRequiresTaskAndCompletionReleasesImmediately() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.activity(100);
        assertEquals(0, lease.remaining(100));
        lease.tasksChanged(2, 200);
        lease.tasksChanged(1, 300);
        assertTrue(lease.remaining(300) > 0);
        lease.tasksChanged(0, 400);
        lease.activity(500);
        assertEquals(0, lease.remaining(500));
    }
    @Test public void continuingActivityRenewsAndExpiredWorkCanResume() {
        AgentScreenLease lease = new AgentScreenLease();
        lease.tasksChanged(1, 100);
        lease.activity(150000);
        assertEquals(150000, lease.remaining(180000));
        assertEquals(0, lease.remaining(330000));
        lease.activity(400000);
        assertEquals(180000, lease.remaining(400000));
    }
    @Test public void healthAndAutomaticChecksDoNotRenew() {
        assertFalse(AgentScreenLease.isActivity("node.info"));
        assertFalse(AgentScreenLease.isActivity("app.update_check"));
        assertTrue(AgentScreenLease.isActivity("ui.inspect"));
        assertTrue(AgentScreenLease.isActivity("process.exec"));
    }
}
