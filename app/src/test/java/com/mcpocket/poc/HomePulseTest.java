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
        assertEquals(PicoOrbState.COMPLETED, state(true, "connected", null).orbMode);
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
        assertEquals(PicoOrbState.HUMAN_HELP, s.orbMode);
        assertEquals("help", s.action);
        assertEquals("Turn the PCB over", s.actionDetail);
        assertEquals("Remote access unavailable", s.connection);
    }
    @Test public void stoppedNodeDoesNotShowOldTaskAsRunning() throws Exception {
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "running").put("agent", "Codex"));
        assertEquals("OFFLINE", state(false, "connected", null).label);
        assertEquals(PicoOrbState.HIDDEN, state(false, "connected", null).orbMode);
        HomePulse.reset();
        assertEquals("idle", state(true, "connected", null).mode);
    }
    @Test public void tasksKeepRealIdentityThroughRecentCompletion() throws Exception {
        String now = java.time.Instant.now().toString();
        JSONObject task = new JSONObject().put("taskId", "t").put("status", "running").put("agent", "Codex").put("title", "Inspect the board").put("updatedAt", now);
        HomePulse.task(task);
        assertEquals("Codex · Running", state(true, "connected", null).agentState);
        assertEquals("Inspect the board", state(true, "connected", null).title);
        task.put("status", "completed").put("updatedAt", java.time.Instant.now().toString()); HomePulse.task(task);
        assertEquals("idle", state(true, "connected", null).mode);
        assertEquals("Codex", state(true, "connected", null).agent);
        assertEquals("Inspect the board", state(true, "connected", null).title);
        assertTrue(state(true, "connected", null).recent);
    }

    @Test public void pendingHumanHelpKeepsActiveTaskIdentity() throws Exception {
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "running")
                .put("agent", "GPT-5.6 Sol").put("title", "Inspect UI").put("updatedAt", java.time.Instant.now().toString()));
        HomePulse.Snapshot s = state(true, "connected", new JSONObject().put("title", "Please confirm"));
        assertEquals("GPT-5.6 Sol", s.agent);
        assertEquals("waiting", s.mode);
    }

    @Test public void waitingTaskTurnsOnHumanHelpOrbEvenBeforeARequestIsStored() throws Exception {
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "waiting_human")
                .put("agent", "Codex").put("updatedAt", java.time.Instant.now().toString()));
        assertEquals(PicoOrbState.HUMAN_HELP, state(true, "connected", null).orbMode);
    }
    @Test public void failureHasBoundedLifetimeAndConnectingNeedsNoButton() {
        long command = HomePulse.begin("ui.inspect"); HomePulse.finish(command, true);
        assertEquals("blocked", state(true, "connected", null).mode);
        assertEquals("idle", HomePulse.snapshot(true, true, "connected", null, System.currentTimeMillis()+16000).mode);
        assertEquals("connecting", state(true, "connecting", null).mode);
        assertEquals(PicoOrbState.BLOCKED, state(true, "connecting", null).orbMode);
        assertEquals("", state(true, "connecting", null).action);
    }

    @Test public void connectingUsesConnectionOrbWhenNoHigherPriorityProblemExists() {
        assertEquals(PicoOrbState.CONNECTING, state(true, "connecting", null).orbMode);
    }

    @Test public void successfulCommandReturnsFromGreenToReadyAfterFiveSeconds() {
        long command = HomePulse.begin("ui.inspect");
        HomePulse.finish(command, false);
        long later = System.currentTimeMillis() + PicoOrbState.COMPLETED_VISIBLE_MS + 50L;
        assertEquals(PicoOrbState.READY,
                HomePulse.snapshot(true, true, "connected", null, later).orbMode);
    }

    @Test public void relayLossUsesConnectionAttentionWithoutCallingLocalOffline() {
        HomePulse.Snapshot snapshot = state(true, "disconnected", null);
        assertEquals(PicoOrbState.CONNECTION_ATTENTION, snapshot.orbMode);
        assertEquals("Remote access unavailable", snapshot.connection);
    }

    @Test public void cancelledTaskDoesNotFlashCompletedGreen() throws Exception {
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "cancelled")
                .put("updatedAt", java.time.Instant.now().toString()));
        assertEquals(PicoOrbState.READY, state(true, "connected", null).orbMode);
    }

    @Test public void staleBlockedTaskStopsControllingPresenceButRemainsInHistory() throws Exception {
        long now = System.currentTimeMillis();
        String stale = java.time.Instant.ofEpochMilli(
                now - AgentTaskRuntime.ACTIVE_PROJECTION_LEASE_MS - 1L).toString();
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "blocked")
                .put("agent", "ChatGPT").put("title", "Old blocked task").put("updatedAt", stale));

        HomePulse.Snapshot snapshot = HomePulse.snapshot(true, true, "connected", null, now);
        assertEquals("idle", snapshot.mode);
        assertEquals(PicoOrbState.READY, snapshot.orbMode);
        assertEquals(0, snapshot.activeTasks);
        assertEquals(1, HomePulse.taskHistory().length());
    }

    @Test public void freshBlockedTaskKeepsControllingPresenceWithinLease() throws Exception {
        long now = System.currentTimeMillis();
        String fresh = java.time.Instant.ofEpochMilli(
                now - AgentTaskRuntime.ACTIVE_PROJECTION_LEASE_MS + 1_000L).toString();
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "blocked")
                .put("agent", "ChatGPT").put("updatedAt", fresh));

        HomePulse.Snapshot snapshot = HomePulse.snapshot(true, true, "connected", null, now);
        assertEquals("blocked", snapshot.mode);
        assertEquals(PicoOrbState.BLOCKED, snapshot.orbMode);
        assertEquals(1, snapshot.activeTasks);
    }

    @Test public void staleWaitingTaskCannotPinHumanHelpWithoutRealPendingRequest() throws Exception {
        long now = System.currentTimeMillis();
        String stale = java.time.Instant.ofEpochMilli(
                now - AgentTaskRuntime.ACTIVE_PROJECTION_LEASE_MS - 1L).toString();
        HomePulse.task(new JSONObject().put("taskId", "t").put("status", "waiting_human")
                .put("agent", "ChatGPT").put("updatedAt", stale));

        assertEquals(PicoOrbState.READY,
                HomePulse.snapshot(true, true, "connected", null, now).orbMode);
        assertEquals(PicoOrbState.HUMAN_HELP,
                HomePulse.snapshot(true, true, "connected", new JSONObject().put("title", "Still pending"), now).orbMode);
    }
    @Test public void wakeKeyChangesForNewCallsAndTaskUpdates() throws Exception {
        String idle = HomePulse.snapshot(true, true, "connected", null, 1L).wakeKey;
        long command = HomePulse.begin("ui.inspect");
        String called = HomePulse.snapshot(true, true, "connected", null, 2L).wakeKey;
        assertNotEquals(idle, called);
        HomePulse.finish(command, false);
        assertEquals(called, HomePulse.snapshot(true, true, "connected", null, 3L).wakeKey);

        JSONObject task = new JSONObject().put("taskId", "wake").put("status", "running")
                .put("updatedAt", "2026-01-01T00:00:00Z");
        HomePulse.task(task);
        String firstUpdate = HomePulse.snapshot(true, true, "connected", null, 4L).wakeKey;
        task.put("updatedAt", "2026-01-01T00:00:01Z");
        HomePulse.task(task);
        assertNotEquals(firstUpdate, HomePulse.snapshot(true, true, "connected", null, 5L).wakeKey);
        assertNotEquals(firstUpdate, HomePulse.snapshot(true, true, "connected",
                new JSONObject().put("requestId", "help-1"), 5L).wakeKey);
    }
}
