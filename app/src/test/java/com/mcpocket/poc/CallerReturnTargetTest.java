package com.mcpocket.poc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallerReturnTargetTest {
    private JSONObject task(String id, JSONObject caller) throws Exception {
        return new JSONObject().put("taskId", id).put("status", "running")
                .put("updatedAt", java.time.Instant.now().toString())
                .put("context", new JSONObject().put("caller", caller));
    }

    @Test public void explicitDestinationsAndRemoteFallback() throws Exception {
        CallerReturnTarget app = CallerReturnTarget.fromTask(task("a", new JSONObject()
                .put("name", "Example").put("packageName", "com.example.app")));
        assertTrue(app.available());
        assertEquals("com.example.app", app.packageName);
        CallerReturnTarget remote = CallerReturnTarget.fromTask(task("a", new JSONObject().put("type", "remote")));
        assertFalse(remote.available());
        assertTrue(remote.label().contains("原裝置"));
        assertFalse(CallerReturnTarget.fromTask(null).available());
    }

    @Test public void rejectExecutableAndCredentialUrls() throws Exception {
        for (String url : new String[]{"intent://test", "javascript:alert(1)", "file:///etc/passwd", "https://user:pass@example.com"}) {
            assertFalse(CallerReturnTarget.fromTask(task("a", new JSONObject().put("returnUrl", url))).available());
        }
        assertTrue(CallerReturnTarget.fromTask(task("a", new JSONObject().put("returnUrl", "https://example.com/chat/1"))).available());
    }

    @Test public void concurrentCommandsAndTasksNeverBorrowDestination() throws Exception {
        HomePulse.reset();
        try {
            HomePulse.task(task("a", new JSONObject().put("packageName", "com.example.app")));
            assertTrue(snapshot().caller.available());
            long command = HomePulse.begin("ui.inspect");
            assertFalse(snapshot().caller.available());
            HomePulse.finish(command, false);
            assertTrue(snapshot().caller.available());
            HomePulse.task(task("b", new JSONObject().put("packageName", "com.other.app")));
            assertFalse(snapshot().caller.available());
        } finally { HomePulse.reset(); }
    }

    private HomePulse.Snapshot snapshot() {
        return HomePulse.snapshot(true, true, "connected", null, System.currentTimeMillis());
    }
}
