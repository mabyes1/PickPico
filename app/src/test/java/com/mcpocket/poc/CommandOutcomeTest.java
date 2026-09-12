package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class CommandOutcomeTest {
    private McpToolActions actions;
    private McpToolRegistry tools;

    @Before public void setUp() throws Exception {
        HomePulse.reset();
        actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        tools = new McpToolRegistry(actions);
    }

    private JSONObject call(String name, JSONObject arguments) throws Exception {
        arguments = new JSONObject(arguments.toString());
        JSONArray listed = tools.list(false, "full").getJSONArray("tools");
        for (int i = 0; i < listed.length(); i++) {
            JSONObject tool = listed.getJSONObject(i);
            if (name.equals(tool.getString("name")) && tool.getJSONObject("inputSchema").getJSONObject("properties").has("agent"))
                arguments.put("agent", "Test Model 1.0");
        }
        return tools.call(new JSONObject().put("name", name).put("arguments", arguments),
                false, "full", 1L);
    }

    @Test public void failedCaptureIsToolErrorAndBlocksPulse() throws Exception {
        when(actions.cameraCapture(any(), anyLong())).thenReturn(new JSONObject()
                .put("captured", false).put("error", "camera_unavailable"));
        JSONObject result = call("camera_capture", new JSONObject());
        assertTrue(result.getBoolean("isError"));
        assertFalse(result.getJSONObject("structuredContent").getBoolean("captured"));
        assertEquals("camera_unavailable", result.getJSONObject("structuredContent").getString("error"));
        assertEquals("blocked", HomePulse.snapshot(true, true, "connected", null,
                System.currentTimeMillis()).mode);
    }

    @Test public void commandRunAndHistoryAgreeButHistoryQueryItselfSucceeds() throws Exception {
        when(actions.cameraCapture(any(), anyLong())).thenReturn(new JSONObject()
                .put("captured", false).put("error", "capture_timeout"));
        JSONObject call = call("command_run", new JSONObject().put("commandId", "camera.capture"));
        assertTrue(call.getBoolean("isError"));
        JSONObject execution = call.getJSONObject("structuredContent");
        assertEquals("failed", execution.getString("status"));
        assertTrue(execution.getJSONObject("result").getBoolean("isError"));
        JSONObject history = call("command_status", new JSONObject()
                .put("executionId", execution.getString("executionId")));
        assertFalse(history.getBoolean("isError"));
        assertEquals("failed", history.getJSONObject("structuredContent").getString("status"));
        assertTrue(history.getJSONObject("structuredContent").getJSONObject("result").getBoolean("isError"));
    }

    @Test public void successfulCaptureKeepsNativeMedia() throws Exception {
        JSONArray media = new JSONArray().put(new JSONObject().put("type", "image")
                .put("mimeType", "image/jpeg").put("data", "unit-test-media"));
        when(actions.cameraCapture(any(), anyLong())).thenReturn(new JSONObject()
                .put("captured", true).put("_mcpContent", media));
        JSONObject result = call("camera_capture", new JSONObject());
        assertFalse(result.getBoolean("isError"));
        assertEquals("unit-test-media", result.getJSONArray("content").getJSONObject(0).getString("data"));
        assertFalse(result.getJSONObject("structuredContent").has("_mcpContent"));
    }

    @Test public void timeoutAndNonzeroExitAreExecutionFailures() throws Exception {
        when(actions.execCommand(any(), anyLong())).thenReturn(new JSONObject()
                .put("executed", true).put("timedOut", true).put("exitCode", -1));
        assertTrue(call("exec_command", new JSONObject().put("command", "unit-test-only")).getBoolean("isError"));
        assertTrue(CommandOutcome.isFailure("process.exec", new JSONObject().put("exitCode", 7)));
        assertFalse(CommandOutcome.isFailure("process.exec", new JSONObject()
                .put("running", true).put("exitCode", JSONObject.NULL)));
        assertFalse(CommandOutcome.isFailure("process.exec", new JSONObject().put("exitCode", 0)));
        assertTrue(CommandOutcome.isFailure("process.exec", new JSONObject()
                .put("exitCode", 0).put("stdinError", "pipe closed before all input was delivered")));
    }

    @Test public void nodeStatusCanReportAPastFailureWithoutFailingTheQuery() throws Exception {
        when(actions.nodeStatus(anyLong())).thenReturn(new JSONObject()
                .put("status", "failed").put("running", false).put("error", "past_error"));
        JSONObject result = call("node_status", new JSONObject());
        assertFalse(result.getBoolean("isError"));
        assertEquals("past_error", result.getJSONObject("structuredContent").getString("error"));
    }

    @Test public void normalFalseStateAndEmptyErrorsDoNotBecomeFailures() throws Exception {
        assertFalse(CommandOutcome.isFailure("notification.get", new JSONObject().put("found", false)));
        assertFalse(CommandOutcome.isFailure("capability.status", new JSONObject().put("available", false)));
        assertFalse(CommandOutcome.isFailure("app.update_check", new JSONObject().put("hasUpdate", false)));
        assertFalse(CommandOutcome.isFailure("node.status", new JSONObject().put("running", false)));
        assertFalse(CommandOutcome.isFailure("process.output", new JSONObject().put("exitCode", 1)));
        for (Object error : new Object[]{JSONObject.NULL, "", false}) {
            assertFalse(CommandOutcome.isFailure("camera.capture", new JSONObject()
                    .put("captured", true).put("error", error)));
        }
    }

    @Test public void operationSpecificFalseFlagsAreFailures() throws Exception {
        assertTrue(CommandOutcome.isFailure("camera.capture", new JSONObject().put("captured", false)));
        assertTrue(CommandOutcome.isFailure("screen.capture", new JSONObject().put("captured", false)));
        assertTrue(CommandOutcome.isFailure("microphone.record", new JSONObject().put("recorded", false)));
        assertTrue(CommandOutcome.isFailure("ui.action", new JSONObject().put("performed", false)));
        assertTrue(CommandOutcome.isFailure("ui.type", new JSONObject().put("performed", false)));
        assertTrue(CommandOutcome.isFailure("ui.scroll", new JSONObject().put("performed", false)));
        assertTrue(CommandOutcome.isFailure("process.exec", new JSONObject().put("executed", false)));
    }

    @Test public void updatingTaskToFailedIsStillASuccessfulUpdate() throws Exception {
        JSONObject created = call("task_create", new JSONObject().put("objective", "unit-test task"));
        String taskId = created.getJSONObject("structuredContent").getString("taskId");
        JSONObject updated = call("task_update", new JSONObject().put("taskId", taskId).put("status", "failed"));
        assertFalse(updated.getBoolean("isError"));
        assertEquals("failed", updated.getJSONObject("structuredContent").getString("status"));
    }

    @Test public void stoppingAnAlreadyTimedOutSessionIsNotASecondFailure() throws Exception {
        assertFalse(CommandOutcome.isFailure("process.stop", new JSONObject()
                .put("sessionId", "test-session").put("running", false)
                .put("timedOut", true).put("stdinError", "old input failure")));
        assertTrue(CommandOutcome.isFailure("process.stop", new JSONObject()
                .put("sessionId", "test-session").put("running", true).put("stopRequested", true)));
    }

    @Test public void pendingAndRunningOperationsAreNotReportedCompleted() throws Exception {
        assertEquals("waiting_user", CommandOutcome.executionStatus(new JSONObject().put("status", "pending_user_action")));
        assertEquals("waiting_user", CommandOutcome.executionStatus(new JSONObject().put("requiresUserAction", true)));
        assertEquals("running", CommandOutcome.executionStatus(new JSONObject().put("running", true)));
        assertEquals("failed", CommandOutcome.executionStatus(new JSONObject().put("isError", true)));
    }
}
