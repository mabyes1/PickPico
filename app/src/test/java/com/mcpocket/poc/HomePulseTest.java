package com.mcpocket.poc;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HomePulseTest {
    @Before public void reset() { HomePulse.reset(); }
    private HomePulse.Snapshot state(boolean node, String relay, JSONObject pending) {
        return HomePulse.snapshot(node, true, relay, pending, System.currentTimeMillis());
    }
    @Test public void completionIsNotMistakenForRunning() {
        long command = HomePulse.begin("camera.capture");
        assertEquals("Checking the camera feed", state(true, "connected", null).title);
        HomePulse.finish(command, false);
        assertEquals("idle", state(true, "connected", null).mode);
        assertTrue(state(true, "connected", null).flow.startsWith("Last ·"));
    }
    @Test public void concurrentCommandsRemainActiveUntilBothFinish() {
        long a = HomePulse.begin("camera.capture"), b = HomePulse.begin("ui.inspect");
        HomePulse.finish(a, false);
        assertEquals("running", state(true, "connected", null).mode);
        assertEquals("ui.inspect", state(true, "connected", null).flow);
        HomePulse.finish(b, false);
        assertEquals("idle", state(true, "connected", null).mode);
    }
    @Test public void humanRequestWinsOverConnectionFailure() throws Exception {
        HomePulse.Snapshot s = state(true, "disconnected", new JSONObject().put("title", "Turn the PCB over"));
        assertEquals("waiting", s.mode);
        assertEquals("help", s.action);
        assertEquals("Turn the PCB over", s.actionDetail);
        assertEquals("Remote access unavailable", s.connection);
    }
    @Test public void stoppedNodeDoesNotShowOldTaskAsRunning() throws Exception {
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "running").put("agent", "Codex"));
        assertEquals("OFFLINE", state(false, "connected", null).label);
        HomePulse.reset();
        assertEquals("idle", state(true, "connected", null).mode);
    }
    @Test public void tasksShowRealIdentityAndTerminalTasksClear() throws Exception {
        JSONObject task = new JSONObject().put("taskId", "t").put("status", "running").put("agent", "Codex").put("title", "Inspect the board");
        HomePulse.task(task);
        assertEquals("Codex · Running", state(true, "connected", null).agentState);
        assertEquals("Inspect the board", state(true, "connected", null).title);
        task.put("status", "completed"); HomePulse.task(task);
        assertEquals("idle", state(true, "connected", null).mode);
    }
    @Test public void failureHasBoundedLifetimeAndConnectingNeedsNoButton() {
        long command = HomePulse.begin("ui.inspect"); HomePulse.finish(command, true);
        assertEquals("blocked", state(true, "connected", null).mode);
        assertEquals("idle", HomePulse.snapshot(true, true, "connected", null, System.currentTimeMillis()+16000).mode);
        assertEquals("connecting", state(true, "connecting", null).mode);
        assertEquals("", state(true, "connecting", null).action);
    }
}
