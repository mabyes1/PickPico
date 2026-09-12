package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class AgentIdentityTest {
    private McpToolActions actions;
    private McpToolRegistry tools;

    @Before public void setUp() throws Exception {
        HomePulse.reset();
        actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        tools = new McpToolRegistry(actions);
    }

    private JSONObject call(String name, JSONObject arguments, String profile) throws Exception {
        return tools.call(new JSONObject().put("name", name).put("arguments", arguments),
                false, profile, 1L);
    }

    @Test public void taskCreateRejectsMissingIdentityWithoutCreatingTask() throws Exception {
        JSONObject failed = call("task_create", new JSONObject().put("objective", "test"), "thin-v1");
        assertTrue(failed.getBoolean("isError"));
        assertTrue(failed.toString().contains("agent is required"));
        assertEquals(0, call("task_status", new JSONObject(), "thin-v1")
                .getJSONObject("structuredContent").getInt("count"));
    }

    @Test public void taskRuntimeItselfRejectsNullBlankNonStringAndTooLongIdentity() throws Exception {
        AgentTaskRuntime tasks = new AgentTaskRuntime();
        for (Object value : new Object[]{JSONObject.NULL, "", " \t\n", "\u3000\u00a0\u200b", 123, true,
                new JSONObject(), new JSONArray(), "x".repeat(161)}) {
            JSONObject arguments = new JSONObject().put("objective", "test").put("agent", value);
            assertThrows("must reject " + value, CommandRuntime.CommandInputException.class,
                    () -> tasks.create(arguments));
        }
        assertEquals(0, tasks.status(new JSONObject()).getInt("count"));
    }

    @Test public void formatOnlyIdentityCannotPassTheVisibleNameRequirement() throws Exception {
        for (String value : new String[]{"\u200d", "\u200e\u200f", "\u2060\u202e", "\u007f\u0080"}) {
            JSONObject arguments = new JSONObject().put("agent", value);
            assertThrows(CommandRuntime.CommandInputException.class, () -> AgentIdentity.require(arguments));
        }
        assertEquals("Test Model 1.0", AgentIdentity.require(new JSONObject().put("agent", "Test Model 1.0")));
    }

    @Test public void validTaskKeepsNormalizedModelAcrossUpdatesAndUi() throws Exception {
        JSONObject task = call("task_create", new JSONObject().put("objective", "test")
                .put("agent", "  Test Model 1.0  "), "thin-v1").getJSONObject("structuredContent");
        assertEquals("Test Model 1.0", task.getString("agent"));
        JSONObject updated = call("task_update", new JSONObject().put("taskId", task.getString("taskId"))
                .put("status", "running"), "thin-v1").getJSONObject("structuredContent");
        assertEquals("Test Model 1.0", updated.getString("agent"));
        assertEquals("Test Model 1.0", HomePulse.snapshot(true, true, "connected", null,
                System.currentTimeMillis()).agent);
    }

    @Test public void unknownIdentityMustBeExplicitAndIsNeverInvented() throws Exception {
        String unknown = "unknown (model not exposed)";
        JSONObject task = new AgentTaskRuntime().create(new JSONObject().put("objective", "test").put("agent", unknown));
        assertEquals(unknown, task.getString("agent"));
    }

    @Test public void publicIdentitySchemaIsPortableWhileRuntimeRejectsInvisibleNames() throws Exception {
        for (String profile : new String[]{"thin-v1", "full"}) {
            JSONArray listed = tools.list(false, profile).getJSONArray("tools");
            for (int i = 0; i < listed.length(); i++) {
                JSONObject schema = listed.getJSONObject(i).getJSONObject("inputSchema").getJSONObject("properties").optJSONObject("agent");
                if (schema == null) continue;
                assertFalse("Connector must not reinterpret an identity regex", schema.has("pattern"));
                assertEquals("string", schema.getString("type"));
                assertEquals(1, schema.getInt("minLength"));
                assertEquals(160, schema.getInt("maxLength"));
            }
        }
        when(actions.serverInfo(anyLong())).thenReturn(new JSONObject().put("version", "test"));
        for (String identity : new String[]{"GPT-6", "Test Model 1.0", "unknown (model not exposed)"}) {
            JSONObject result = call("command_run", new JSONObject().put("commandId", "node.info")
                    .put("arguments", new JSONObject()).put("agent", identity), "thin-v1");
            assertFalse(result.toString(), result.getBoolean("isError"));
            assertEquals(identity, result.getJSONObject("structuredContent").getJSONObject("result").getString("agent"));
        }
        clearInvocations(actions);
        for (String identity : new String[]{"", " \t\n", "\u3000\u00a0\u200b", "\u200d\u2060", "x".repeat(161)}) {
            assertTrue(call("command_run", new JSONObject().put("commandId", "node.info")
                    .put("agent", identity), "thin-v1").getBoolean("isError"));
        }
        verify(actions, never()).serverInfo(anyLong());
        verify(actions, never()).onAgentCommandStarted(anyString());
    }

    @Test public void bothCommandGatewayAndLegacyToolsRejectMissingIdentityBeforeActions() throws Exception {
        assertTrue(call("command_run", new JSONObject().put("commandId", "camera.capture"), "thin-v1").getBoolean("isError"));
        assertTrue(call("camera_capture", new JSONObject(), "full").getBoolean("isError"));
        assertTrue(call("exec_command", new JSONObject().put("command", "test-only"), "full").getBoolean("isError"));
        verify(actions, never()).cameraCapture(any(), anyLong());
        verify(actions, never()).execCommand(any(), anyLong());
        assertEquals(0, HomePulse.commandHistory().length());
    }

    @Test public void nestedAgentDoesNotSatisfyTheOuterRequiredField() throws Exception {
        JSONObject result = call("command_run", new JSONObject().put("commandId", "camera.capture")
                .put("arguments", new JSONObject().put("agent", "Test Model 1.0")), "thin-v1");
        assertTrue(result.getBoolean("isError"));
        verify(actions, never()).cameraCapture(any(), anyLong());
    }

    @Test public void standaloneCallShowsItsOwnModelDuringAndAfterExecution() throws Exception {
        when(actions.cameraCapture(any(), anyLong())).thenAnswer(invocation -> {
            assertEquals("Test Model 2.0", AgentIdentity.current());
            assertEquals("Test Model 2.0", HomePulse.snapshot(true, true, "connected", null,
                    System.currentTimeMillis()).agent);
            assertFalse(((JSONObject) invocation.getArgument(0)).has("agent"));
            return new JSONObject().put("captured", true);
        });
        JSONObject result = call("camera_capture", new JSONObject().put("agent", "Test Model 2.0"), "full");
        assertFalse(result.getBoolean("isError"));
        assertEquals("Test Model 2.0", result.getJSONObject("structuredContent").getString("agent"));
        assertEquals("Test Model 2.0", HomePulse.commandHistory().getJSONObject(0).getString("agent"));
        assertEquals("Test Model 2.0", HomePulse.snapshot(true, true, "connected", null,
                System.currentTimeMillis()).agent);
        assertEquals("", AgentIdentity.current());
    }

    @Test public void discoveryRemainsAvailableBeforeIdentification() throws Exception {
        assertFalse(call("capability_search", new JSONObject().put("query", "camera"), "thin-v1").getBoolean("isError"));
        assertFalse(call("capability_list", new JSONObject(), "thin-v1").getBoolean("isError"));
        assertFalse(call("task_runtime_info", new JSONObject(), "thin-v1").getBoolean("isError"));
    }

    @Test public void publicSchemasRequireIdentityOnBothProfiles() throws Exception {
        for (String profile : new String[]{"thin-v1", "full"}) {
            JSONArray listed = tools.list(false, profile).getJSONArray("tools");
            int checked = 0;
            for (int i = 0; i < listed.length(); i++) {
                JSONObject tool = listed.getJSONObject(i);
                String name = tool.getString("name");
                if ("command_run".equals(name) || "task_create".equals(name) || "camera_capture".equals(name)) {
                    JSONObject schema = tool.getJSONObject("inputSchema");
                    assertTrue(schema.getJSONArray("required").toString().contains("\"agent\""));
                    assertEquals(1, schema.getJSONObject("properties").getJSONObject("agent").getInt("minLength"));
                    checked++;
                }
            }
            assertEquals(3, checked);
        }
    }

    @Test public void failedCallClearsIdentityBeforeTheWorkerIsReused() throws Exception {
        when(actions.cameraCapture(any(), anyLong())).thenThrow(new IllegalStateException("test failure"));
        assertTrue(call("camera_capture", new JSONObject().put("agent", "Test Model 3.0"), "full").getBoolean("isError"));
        assertEquals("", AgentIdentity.current());
        assertTrue(call("camera_capture", new JSONObject(), "full").getBoolean("isError"));
        verify(actions, times(1)).cameraCapture(any(), anyLong());
    }

    @Test(timeout = 5000) public void concurrentCallsNeverShareModelIdentity() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger observed = new AtomicInteger();
        when(actions.cameraCapture(any(), anyLong())).thenAnswer(invocation -> {
            String identity = AgentIdentity.current();
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            assertEquals(identity, AgentIdentity.current());
            observed.incrementAndGet();
            return new JSONObject().put("captured", true).put("observed", identity);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JSONObject> a = pool.submit(() -> call("camera_capture", new JSONObject().put("agent", "Test Model A"), "full"));
            Future<JSONObject> b = pool.submit(() -> call("camera_capture", new JSONObject().put("agent", "Test Model B"), "full"));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); release.countDown();
            assertEquals("Test Model A", a.get(2, TimeUnit.SECONDS).getJSONObject("structuredContent").getString("observed"));
            assertEquals("Test Model B", b.get(2, TimeUnit.SECONDS).getJSONObject("structuredContent").getString("observed"));
            assertEquals(2, observed.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void modelForActiveCommandDoesNotComeFromAnotherTask() throws Exception {
        new AgentTaskRuntime().create(new JSONObject().put("objective", "other task").put("agent", "Other Model"));
        new AgentTaskRuntime().create(new JSONObject().put("objective", "second task").put("agent", "Second Model"));
        long command = HomePulse.begin("camera.capture", "Current Model");
        try {
            HomePulse.Snapshot state = HomePulse.snapshot(true, true, "connected", null, System.currentTimeMillis());
            assertEquals(2, state.activeTasks);
            assertEquals("Current Model", state.agent);
            assertEquals("Current Model", state.agentLabel());
            assertTrue(state.agentState.startsWith("Current Model"));
        } finally { HomePulse.finish(command, false); }
    }
}
