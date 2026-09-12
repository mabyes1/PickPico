package com.mcpocket.poc;

import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public final class HybridToolsTest {
    private McpToolActions actions;
    private McpToolRegistry tools;
    private CommandRuntime runtime;
    @Before public void setup() throws Exception {
        actions = mock(McpToolActions.class, CALLS_REAL_METHODS);
        tools = new McpToolRegistry(actions);
        runtime = new CommandRuntime(actions);
    }
    private JSONObject call(String name, JSONObject args) throws Exception {
        return tools.call(new JSONObject().put("name", name).put("arguments", args), false, "thin-v1", 1);
    }
    @Test public void completeCatalogAndDirectSchemasStayAligned() throws Exception {
        JSONArray listed = tools.list(false, "thin-v1").getJSONArray("tools");
        assertEquals(30, listed.length());
        assertEquals(60, runtime.commandIds().length());
        assertEquals(38, runtime.dynamicIndex().trim().split("\n").length);
        for (String id : CapabilityIndex.DIRECT) {
            JSONObject found = null;
            for (int i = 0; i < listed.length(); i++) if (listed.getJSONObject(i).getString("name").equals(CapabilityIndex.tool(id))) found = listed.getJSONObject(i);
            assertNotNull(id, found);
            JSONObject published = new JSONObject(found.getJSONObject("inputSchema").toString());
            assertTrue(published.getJSONObject("properties").has("agent"));
            published.getJSONObject("properties").remove("agent");
            JSONArray required = published.getJSONArray("required"), filtered = new JSONArray();
            for (int i = 0; i < required.length(); i++) if (!required.getString(i).equals("agent")) filtered.put(required.get(i));
            if (filtered.length() == 0) published.remove("required"); else published.put("required", filtered);
            assertEquals(id, runtime.schema(id).toString(), published.toString());
        }
        for (int i = 0; i < runtime.commandIds().length(); i++) {
            String id = runtime.commandIds().getString(i);
            JSONObject status = runtime.execute("capability.status", new JSONObject().put("id", id), 1);
            assertEquals(id, runtime.schema(id).toString(), status.getJSONObject("inputSchema").toString());
        }
    }
    @Test public void searchPreservesReadWriteIntentAndExactIds() throws Exception {
        String[][] cases = {{"check battery level","phone.status"},{"lower volume","audio.set"},{"calendar list","calendar.list"},{"calendar create","calendar.create"},{"clipboard read","clipboard.get"},{"clipboard write","clipboard.set"},{"notification dismiss","notification.dismiss"},{"notification list","notification.list"},{"查看行事曆","calendar.list"},{"開相機","app.list"}};
        for (String[] c : cases) assertEquals(c[0], c[1], runtime.search(new JSONObject().put("query",c[0])).getJSONArray("matches").getJSONObject(0).getString("id"));
        JSONObject exact = runtime.search(new JSONObject().put("query","ui.type"));
        assertEquals(1, exact.getInt("count")); assertEquals(0, exact.getJSONArray("guides").length());
    }
    @Test public void invalidInputNeverStartsOrAsksApproval() throws Exception {
        JSONObject missing = call("ui_type", new JSONObject().put("agent","Test 1").put("observationId","ui-test").put("selector",new JSONObject().put("path","0")));
        assertTrue(missing.getBoolean("isError"));
        assertEquals("arguments.text",missing.getJSONObject("structuredContent").getJSONObject("error").getString("field"));
        assertTrue(call("command_run",new JSONObject().put("agent","Test 1").put("commandId","workspace.write").put("arguments",new JSONObject().put("path","file"))).getBoolean("isError"));
        assertTrue(call("phone_status",new JSONObject().put("agent","Test 1").put("typo",true)).getBoolean("isError"));
        verify(actions,never()).onAgentCommandStarted(anyString());
        verify(actions,never()).requestApproval(anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test public void missingObservationRejectsDirectUiBeforeDispatch() throws Exception {
        assertTrue(call("ui_action",new JSONObject().put("agent","Test 1").put("action","back")).getBoolean("isError"));
        verify(actions,never()).onAgentCommandStarted(anyString());
    }
    @Test public void directHelpReturnsTrackableWaitButLegacyRetainsDefault() throws Exception {
        when(actions.humanHelp(any(),anyLong())).thenAnswer(i -> new JSONObject().put("status","waiting_human").put("requestId","hh-test").put("receivedWait",i.<JSONObject>getArgument(0).optBoolean("wait",true)));
        JSONObject direct = call("human_help",new JSONObject().put("agent","Test 1").put("instruction","Test only")).getJSONObject("structuredContent");
        assertFalse(direct.getBoolean("receivedWait")); assertEquals("hh-test",direct.getString("requestId"));
        JSONObject legacy = call("command_run",new JSONObject().put("agent","Test 1").put("commandId","human.help").put("arguments",new JSONObject().put("instruction","Test only"))).getJSONObject("structuredContent");
        assertEquals("waiting_human",legacy.getString("status")); assertTrue(legacy.getJSONObject("result").getBoolean("receivedWait"));
    }
    @Test public void interruptedWriteIsUnknownAndHistoryKeepsIt() throws Exception {
        when(actions.workspaceWriteFile(any(),anyLong())).thenThrow(new IllegalStateException("connection ended after write"));
        JSONObject run = call("command_run",new JSONObject().put("agent","Test 1").put("commandId","workspace.write").put("arguments",new JSONObject().put("path","x").put("content","x"))).getJSONObject("structuredContent");
        assertEquals("unknown",run.getString("status"));
        assertFalse(run.getJSONObject("result").getJSONObject("error").getBoolean("retryable"));
        JSONObject query = call("command_status",new JSONObject().put("executionId",run.getString("executionId")));
        assertFalse(query.getBoolean("isError"));
        assertEquals("unknown",query.getJSONObject("structuredContent").getString("status"));
        assertFalse(call("command_status",new JSONObject()).getJSONObject("structuredContent").getJSONArray("recent").getJSONObject(0).has("result"));
    }
    @Test public void annotationsDoNotPretendGatewayIsReadOnly() throws Exception {
        JSONArray listed = tools.list(false,"thin-v1").getJSONArray("tools");
        for(int i=0;i<listed.length();i++) {
            JSONObject t=listed.getJSONObject(i);
            if(t.getString("name").equals("command_run")) assertFalse(t.getJSONObject("annotations").getBoolean("readOnlyHint"));
            if(t.getString("name").equals("phone_status")) assertTrue(t.getJSONObject("annotations").getBoolean("readOnlyHint"));
        }
    }
}
