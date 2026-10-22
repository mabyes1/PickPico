package com.mcpocket.poc;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnlockWaitPolicyTest {
    @Test public void waitingForUnlockEndsBeforeAnAgentToolCallTimesOut() {
        // MCP clients stop waiting after about a minute; the agent must get UNLOCK_REQUIRED, not a timeout.
        assertTrue(McpNodeService.DEFAULT_UNLOCK_WAIT_MS < 60_000L);
        assertTrue(McpNodeService.DEFAULT_UNLOCK_WAIT_MS >= 30_000L);
    }
}
