package com.mcpocket.poc;

import org.json.*;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ButlerWorkflowTest {
    @Test public void taskHoldsSurvivePresentationExpiryAndReleaseOnlyAfterLastTask() throws Exception {
        AtomicInteger count = new AtomicInteger();
        AgentTaskRuntime runtime = new AgentTaskRuntime(count::set);
        JSONObject a = runtime.create(new JSONObject().put("agent", "test model").put("objective", "a"));
        JSONObject b = runtime.create(new JSONObject().put("agent", "test model").put("objective", "b"));
        a.put("updatedAt", Instant.EPOCH.toString());
        assertFalse(AgentTaskRuntime.holdsProjectionLease(a, System.currentTimeMillis()));
        assertEquals(2, count.get());
        runtime.update(new JSONObject().put("taskId", a.getString("taskId")).put("status", "completed"));
        assertEquals(1, count.get());
        runtime.update(new JSONObject().put("taskId", b.getString("taskId")).put("status", "cancelled"));
        assertEquals(0, count.get());
    }
    @Test public void retainedTasksNeverEvictUnfinishedScreenHolds() throws Exception {
        AgentTaskRuntime runtime = new AgentTaskRuntime();
        JSONObject first = runtime.create(new JSONObject().put("agent", "test model").put("objective", "keep"));
        for (int i = 0; i < 60; i++) {
            JSONObject next = runtime.create(new JSONObject().put("agent", "test model").put("objective", "done"));
            runtime.update(new JSONObject().put("taskId", next.getString("taskId")).put("status", "failed"));
        }
        assertEquals("created", runtime.status(new JSONObject().put("taskId", first.getString("taskId"))).getString("status"));
        assertEquals(1, runtime.awakeTaskCount());
    }
    @Test public void batchRequiresExplicitModeAndSafeReplayId() throws Exception {
        CommandRuntime.validateNotificationDismiss(new JSONObject().put("all", true).put("requestId", "batch_20260922"));
        for (JSONObject bad : new JSONObject[]{new JSONObject(), new JSONObject().put("all", true),
                new JSONObject().put("all", true).put("requestId", "../../bad"),
                new JSONObject().put("all", true).put("requestId", "batch_20260922").put("key", "x")}) {
            try { CommandRuntime.validateNotificationDismiss(bad); fail("Must reject ambiguous or unsafe input"); }
            catch (CommandRuntime.CommandInputException expected) { }
        }
    }
    @Test public void verificationDoesNotClaimAllRemovedOrIncludeNewArrivals() throws Exception {
        Set<String> requested = new LinkedHashSet<>(Arrays.asList("a", "b"));
        JSONObject result = NotificationBatch.verify(requested, new HashSet<>(Arrays.asList("b", "new")));
        assertEquals(1, result.getInt("confirmedAbsentCount"));
        assertEquals(1, result.getInt("remainingCount"));
        assertFalse(result.getBoolean("verified"));
        assertEquals("partial", result.getString("status"));
    }
    @Test public void compactDigestReportsTruncationAndKeepsNonclearableOutOfTargets() throws Exception {
        JSONObject n = new JSONObject().put("key", "ongoing").put("clearable", false).put("bigText", "x".repeat(900));
        JSONObject compact = NotificationBatch.compact(n);
        assertEquals(600, compact.getString("text").length());
        assertTrue(compact.getBoolean("textTruncated"));
        assertTrue(NotificationBatch.candidates(new JSONArray().put(n)).isEmpty());
    }
    @Test public void classifyEvidenceWithoutCallingEveryTimeoutAKilledProcess() {
        assertEquals("heartbeat_timeout", ConnectionDiagnostics.reasonCode("relay heartbeat timed out"));
        assertEquals("network_lost", ConnectionDiagnostics.reasonCode("active network lost"));
        assertEquals("unknown", ConnectionDiagnostics.reasonCode("mysterious failure"));
    }
}
