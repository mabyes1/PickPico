package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpHttpServerTest {
    private static final String TOKEN = "unit-test-token";

    private McpHttpServer server;
    private int port;
    private AtomicInteger capabilityStateProbeCount;
    private final AtomicBoolean failPhoneStatus = new AtomicBoolean();

    @Before
    public void setUp() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        capabilityStateProbeCount = new AtomicInteger();
        server = new McpHttpServer(port, TOKEN, new McpToolActions() {
            @Override
            public JSONObject capabilityState(String commandId) throws org.json.JSONException {
                capabilityStateProbeCount.incrementAndGet();
                return McpToolActions.super.capabilityState(commandId);
            }

            @Override
            public JSONObject serverInfo(long callCount) throws org.json.JSONException {
                return new JSONObject().put("name", "test-node").put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneStatus(long callCount) throws org.json.JSONException {
                if (failPhoneStatus.get()) {
                    throw new SecurityException("simulated denied permission");
                }
                return new JSONObject()
                        .put("battery", new JSONObject().put("percent", 80))
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneExec(String command, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("command", command)
                        .put("exitCode", 0)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject execCommand(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("command", arguments.optString("command", ""))
                        .put("cwd", arguments.optString("cwd", "workspace-root"))
                        .put("background", arguments.optBoolean("background", false))
                        .put("stdout", "stub-output")
                        .put("exitCode", 0)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject readProcessOutput(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("sessionId", arguments.optString("sessionId", ""))
                        .put("status", "running")
                        .put("stdout", "background-output")
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject killProcessSession(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("sessionId", arguments.optString("sessionId", ""))
                        .put("status", "exited")
                        .put("stopRequested", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject workspaceInfo(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("root", "/data/test/workspaces")
                        .put("backgroundProcesses", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject workspaceList(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("path", arguments.optString("path", "."))
                        .put("entries", new org.json.JSONArray())
                        .put("count", 0)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject workspaceReadFile(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("path", arguments.optString("path", ""))
                        .put("content", "stub-file")
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject workspaceWriteFile(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("path", arguments.optString("path", ""))
                        .put("bytesWritten", arguments.optString("content", "").length())
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject nodeStart(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("started", true)
                        .put("running", true)
                        .put("entry", arguments.optString("entry", ""))
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject nodeStatus(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("status", "running")
                        .put("running", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject nodeStop(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("status", "stopped")
                        .put("running", false)
                        .put("stopped", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject appUpdate(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("status", "downloading")
                        .put("running", true)
                        .put("url", arguments.optString("url", ""))
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject appUpdateStatus(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("status", "idle")
                        .put("running", false)
                        .put("canRequestPackageInstalls", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneRing(String action, int durationSeconds, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("action", action)
                        .put("durationSeconds", durationSeconds)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneLock(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("locked", true)
                        .put("adminActive", true)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneWake(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("woke", true)
                        .put("wasInteractive", false)
                        .put("interactive", true)
                        .put("unlocked", false)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneHome(long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("woke", true)
                        .put("interactive", true)
                        .put("keyguardLocked", false)
                        .put("homeRequested", true)
                        .put("atHome", true)
                        .put("requiresUserAction", false)
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject cameraCapture(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("captured", true)
                        .put("path", "media/camera/test.jpg")
                        .put("mimeType", "image/jpeg")
                        .put("_mcpContent", new org.json.JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "fake camera frame"))
                                .put(new JSONObject()
                                        .put("type", "image")
                                        .put("mimeType", "image/jpeg")
                                        .put("data", "aW1hZ2U=")))
                        .put("toolCallCount", callCount);
            }

            @Override
            public JSONObject screenCapture(JSONObject arguments, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("captured", true)
                        .put("path", "captures/screen-test.jpg")
                        .put("mimeType", "image/jpeg")
                        .put("_mcpContent", new org.json.JSONArray()
                                .put(new JSONObject().put("type", "text").put("text", "fake screen frame"))
                                .put(new JSONObject()
                                        .put("type", "image")
                                        .put("mimeType", "image/jpeg")
                                        .put("data", "aW1hZ2U=")))
                        .put("toolCallCount", callCount);
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void rejectsMissingBearerToken() throws Exception {
        HttpResult response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", new LinkedHashMap<>());
        assertEquals(401, response.status);
    }

    @Test
    public void handlesLegacyInitialize() throws Exception {
        HttpResult initialize = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                        "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," +
                        "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                authorizedHeaders());
        assertEquals(200, initialize.status);
        assertEquals("2025-11-25",
                new JSONObject(initialize.body).getJSONObject("result").getString("protocolVersion"));
    }

    @Test
    public void listsAndCallsPhoneStatus() throws Exception {
        HttpResult list = post(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}",
                authorizedHeaders());
        assertEquals(200, list.status);
        assertTrue(new JSONObject(list.body)
                .getJSONObject("result")
                .getJSONArray("tools")
                .toString()
                .contains("phone_status"));

        HttpResult status = post(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"phone_status\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, status.status);
        assertEquals(80, new JSONObject(status.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getJSONObject("battery")
                .getInt("percent"));
    }

    @Test
    public void unexpectedToolFailureReturnsStructuredErrorInsteadOfDroppingConnection() throws Exception {
        failPhoneStatus.set(true);
        HttpResult failed = post(
                "{\"jsonrpc\":\"2.0\",\"id\":601,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"phone_status\",\"arguments\":{}}}",
                authorizedHeaders());

        assertEquals(200, failed.status);
        JSONObject result = new JSONObject(failed.body).getJSONObject("result");
        assertTrue(result.getBoolean("isError"));
        assertTrue(result.getJSONArray("content").toString().contains("SecurityException"));

        failPhoneStatus.set(false);
        HttpResult recovered = post(
                "{\"jsonrpc\":\"2.0\",\"id\":602,\"method\":\"ping\"}",
                authorizedHeaders());
        assertEquals(200, recovered.status);
    }

    @Test
    public void commandRunSchemaKeepsCapabilityIdsDynamic() throws Exception {
        HttpResult list = post(
                "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/list\"}",
                authorizedHeaders());
        assertEquals(200, list.status);

        JSONArray tools = new JSONObject(list.body)
                .getJSONObject("result")
                .getJSONArray("tools");
        JSONObject commandRun = null;
        for (int index = 0; index < tools.length(); index++) {
            JSONObject tool = tools.getJSONObject(index);
            if ("command_run".equals(tool.getString("name"))) {
                commandRun = tool;
                break;
            }
        }

        assertTrue(commandRun != null);
        JSONObject commandIdSchema = commandRun
                .getJSONObject("inputSchema")
                .getJSONObject("properties")
                .getJSONObject("commandId");
        assertEquals("string", commandIdSchema.getString("type"));
        assertTrue(!commandIdSchema.has("enum"));
    }

    @Test
    public void thinProfileExposesStableGatewayAndDiscoversHiddenCapabilities() throws Exception {
        HttpResult list = post(
                "{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/list\"}",
                thinHeaders());
        assertEquals(200, list.status);
        JSONObject listed = new JSONObject(list.body).getJSONObject("result");
        JSONArray tools = listed.getJSONArray("tools");
        assertEquals(10, tools.length());
        assertEquals("thin-v1", listed.getString("toolProfile"));
        String toolText = tools.toString();
        assertTrue(toolText.contains("capability_search"));
        assertTrue(toolText.contains("command_run"));
        assertTrue(toolText.contains("task_create"));
        assertTrue(toolText.contains("server_info"));
        assertTrue(!toolText.contains("camera_capture"));
        assertTrue(!toolText.contains("exec_command"));

        capabilityStateProbeCount.set(0);
        HttpResult search = post(
                "{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_search\",\"arguments\":{" +
                        "\"query\":\"take a screenshot of the current display\"}}}",
                thinHeaders());
        assertEquals(200, search.status);
        JSONObject discovery = new JSONObject(search.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertTrue(discovery.getJSONArray("matches").length() > 0);
        assertEquals("screen.capture", discovery.getJSONArray("matches").getJSONObject(0).getString("id"));
        assertTrue(discovery.getJSONArray("matches").getJSONObject(0).has("inputSchema"));
        assertTrue(discovery.getInt("totalCandidates") < 10);
        assertEquals(discovery.getInt("totalCandidates"), capabilityStateProbeCount.get());

        HttpResult chineseSearch = post(
                "{\"jsonrpc\":\"2.0\",\"id\":531,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_search\",\"arguments\":{" +
                        "\"query\":\"幫我截圖看看手機畫面\"}}}",
                thinHeaders());
        assertEquals(200, chineseSearch.status);
        JSONObject chineseDiscovery = new JSONObject(chineseSearch.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(
                "screen.capture",
                chineseDiscovery.getJSONArray("matches").getJSONObject(0).getString("id"));

        HttpResult contactsSearch = post(
                "{\"jsonrpc\":\"2.0\",\"id\":532,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_search\",\"arguments\":{" +
                        "\"query\":\"搜尋通訊錄聯絡人\"}}}",
                thinHeaders());
        JSONObject contactsDiscovery = new JSONObject(contactsSearch.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(
                "contacts.search",
                contactsDiscovery.getJSONArray("matches").getJSONObject(0).getString("id"));

        HttpResult shareSearch = post(
                "{\"jsonrpc\":\"2.0\",\"id\":533,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_search\",\"arguments\":{" +
                        "\"query\":\"把這個檔案分享到其他 app\"}}}",
                thinHeaders());
        JSONObject shareDiscovery = new JSONObject(shareSearch.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(
                "share.send",
                shareDiscovery.getJSONArray("matches").getJSONObject(0).getString("id"));

        HttpResult directLegacyTool = post(
                "{\"jsonrpc\":\"2.0\",\"id\":54,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"camera_capture\",\"arguments\":{}}}",
                thinHeaders());
        assertEquals(200, directLegacyTool.status);
        assertTrue(new JSONObject(directLegacyTool.body)
                .getJSONObject("result")
                .getBoolean("isError"));
    }

    @Test
    public void configuredRelayIsNotRedirected() {
        assertEquals("https://relay.example.test",
                RelayClient.migrateLegacyRelayIfNeeded(null, "https://relay.example.test/"));
    }

    @Test
    public void cameraCaptureReturnsNativeMcpImageContent() throws Exception {
        HttpResult response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"camera_capture\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, response.status);
        JSONObject result = new JSONObject(response.body).getJSONObject("result");
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"));
        assertEquals("image/jpeg", result.getJSONArray("content").getJSONObject(1).getString("mimeType"));
        assertTrue(result.getJSONObject("structuredContent").getBoolean("captured"));
        assertTrue(!result.getJSONObject("structuredContent").has("_mcpContent"));
    }

    @Test
    public void screenCaptureReturnsNativeMcpImageContent() throws Exception {
        HttpResult response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":62,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"screen_capture\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, response.status);
        JSONObject result = new JSONObject(response.body).getJSONObject("result");
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"));
        assertEquals("image/jpeg", result.getJSONArray("content").getJSONObject(1).getString("mimeType"));
        assertTrue(result.getJSONObject("structuredContent").getBoolean("captured"));
        assertTrue(!result.getJSONObject("structuredContent").has("_mcpContent"));
    }

    @Test
    public void commandRunCanReturnNativeMcpMediaWithoutPersistingItInHistory() throws Exception {
        HttpResult response = post(
                "{\"jsonrpc\":\"2.0\",\"id\":63,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"screen.capture\",\"arguments\":{}}}}",
                authorizedHeaders());
        assertEquals(200, response.status);
        JSONObject result = new JSONObject(response.body).getJSONObject("result");
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"));
        assertEquals("image/jpeg", result.getJSONArray("content").getJSONObject(1).getString("mimeType"));

        JSONObject execution = result.getJSONObject("structuredContent");
        assertEquals("screen.capture", execution.getString("commandId"));
        assertTrue(execution.getJSONObject("result").getBoolean("captured"));
        assertTrue(execution.getJSONObject("result").getBoolean("mediaContentDelivered"));
        assertTrue(!execution.has("_mcpContent"));

        HttpResult status = post(
                "{\"jsonrpc\":\"2.0\",\"id\":64,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_status\",\"arguments\":{" +
                        "\"executionId\":\"" + execution.getString("executionId") + "\"}}}",
                authorizedHeaders());
        JSONObject history = new JSONObject(status.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertTrue(!history.has("_mcpContent"));
        assertTrue(!history.toString().contains("aW1hZ2U="));
    }

    @Test
    public void phoneExecOnlyAcceptsAllowlistedCommandIds() throws Exception {
        HttpResult accepted = post(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"phone_exec\",\"arguments\":{\"command\":\"identity\"}}}",
                authorizedHeaders());
        assertEquals(200, accepted.status);
        assertEquals("identity", new JSONObject(accepted.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getString("command"));

        HttpResult rejected = post(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"phone_exec\",\"arguments\":{\"command\":\"rm_everything\"}}}",
                authorizedHeaders());
        assertEquals(200, rejected.status);
        assertTrue(new JSONObject(rejected.body)
                .getJSONObject("result")
                .getBoolean("isError"));
    }

    @Test
    public void commandRuntimeListsRunsAndTracksExecutions() throws Exception {
        HttpResult list = post(
                "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_list\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, list.status);
        JSONObject listed = new JSONObject(list.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(59, listed.getInt("count"));
        assertTrue(listed.getJSONArray("commands").toString().contains("capability.list"));
        assertTrue(listed.getJSONArray("commands").toString().contains("capability.status"));
        assertTrue(listed.getJSONArray("commands").toString().contains("policy.status"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.ring"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.lock"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.wake"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.home"));
        assertTrue(listed.getJSONArray("commands").toString().contains("camera.capture"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.notify"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.speak"));
        assertTrue(listed.getJSONArray("commands").toString().contains("audio.status"));
        assertTrue(listed.getJSONArray("commands").toString().contains("audio.set"));
        assertTrue(listed.getJSONArray("commands").toString().contains("microphone.record"));
        assertTrue(listed.getJSONArray("commands").toString().contains("human.help"));
        assertTrue(listed.getJSONArray("commands").toString().contains("human.help.status"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.list"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.get"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.dismiss"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.actions"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.invoke_action"));
        assertTrue(listed.getJSONArray("commands").toString().contains("notification.reply"));
        assertTrue(listed.getJSONArray("commands").toString().contains("ui.inspect"));
        assertTrue(listed.getJSONArray("commands").toString().contains("ui.action"));
        assertTrue(listed.getJSONArray("commands").toString().contains("ui.type"));
        assertTrue(listed.getJSONArray("commands").toString().contains("ui.scroll"));
        assertTrue(listed.getJSONArray("commands").toString().contains("screen.capture"));
        assertTrue(listed.getJSONArray("commands").toString().contains("app.list"));
        assertTrue(listed.getJSONArray("commands").toString().contains("app.launch"));
        assertTrue(listed.getJSONArray("commands").toString().contains("url.open"));
        assertTrue(listed.getJSONArray("commands").toString().contains("location.get"));
        assertTrue(listed.getJSONArray("commands").toString().contains("clipboard.get"));
        assertTrue(listed.getJSONArray("commands").toString().contains("clipboard.set"));
        assertTrue(listed.getJSONArray("commands").toString().contains("contacts.search"));
        assertTrue(listed.getJSONArray("commands").toString().contains("contacts.get"));
        assertTrue(listed.getJSONArray("commands").toString().contains("calendar.list"));
        assertTrue(listed.getJSONArray("commands").toString().contains("calendar.get"));
        assertTrue(listed.getJSONArray("commands").toString().contains("calendar.create"));
        assertTrue(listed.getJSONArray("commands").toString().contains("calendar.update"));
        assertTrue(listed.getJSONArray("commands").toString().contains("calendar.delete"));
        assertTrue(listed.getJSONArray("commands").toString().contains("file.pick"));
        assertTrue(listed.getJSONArray("commands").toString().contains("media.pick"));
        assertTrue(listed.getJSONArray("commands").toString().contains("share.send"));
        assertTrue(listed.getJSONArray("commands").toString().contains("workspace.write"));
        assertTrue(listed.getJSONArray("commands").toString().contains("node.start"));
        assertTrue(listed.getJSONArray("commands").toString().contains("app.update"));
        assertTrue(listed.getJSONArray("commands").toString().contains("app.update_check"));
        assertTrue(listed.getJSONArray("commands").toString().contains("app.update_latest"));
        assertTrue(listed.getJSONArray("commands").toString().contains("process.run"));
        assertTrue(listed.getJSONArray("commands").toString().contains("process.exec"));
        assertTrue(listed.getJSONArray("commands").toString().contains("process.output"));

        HttpResult run = post(
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"phone.ring\",\"arguments\":{\"durationSeconds\":10}}}}",
                authorizedHeaders());
        assertEquals(200, run.status);
        JSONObject execution = new JSONObject(run.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("phone.ring", execution.getString("commandId"));
        assertEquals("completed", execution.getString("status"));
        assertEquals("start", execution.getJSONObject("result").getString("action"));
        String executionId = execution.getString("executionId");

        HttpResult status = post(
                "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_status\",\"arguments\":{" +
                        "\"executionId\":\"" + executionId + "\"}}}",
                authorizedHeaders());
        assertEquals(200, status.status);
        JSONObject tracked = new JSONObject(status.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(executionId, tracked.getString("executionId"));
        assertEquals("completed", tracked.getString("status"));
    }

    @Test
    public void humanHelpUsesRenewableIdleTimeoutChoices() throws Exception {
        JSONObject schema = CommandRuntime.humanHelpSchema();
        JSONObject properties = schema.getJSONObject("properties");
        assertTrue(!properties.has("waitSeconds"));
        JSONObject idleTimeout = properties.getJSONObject("idleTimeoutSeconds");
        assertEquals(180, idleTimeout.getInt("default"));
        assertEquals("[120,180,360]", idleTimeout.getJSONArray("enum").toString());
        assertTrue(idleTimeout.getString("description").contains("Human activity resets this timer"));
    }

    @Test
    public void capabilityAndPolicyToolsExposeRuntimeState() throws Exception {
        HttpResult list = post(
                "{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_list\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, list.status);
        JSONObject capabilityList = new JSONObject(list.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals(59, capabilityList.getInt("count"));
        assertTrue(capabilityList.getJSONArray("capabilities").toString().contains("phone.home"));
        assertTrue(capabilityList.getJSONArray("capabilities").toString().contains("ui.inspect"));
        assertTrue(capabilityList.getJSONArray("capabilities").toString().contains("screen.capture"));
        assertTrue(capabilityList.getJSONArray("capabilities").toString().contains("audio.status"));
        assertTrue(capabilityList.getJSONArray("capabilities").toString().contains("audio.set"));

        HttpResult status = post(
                "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"capability_status\",\"arguments\":{" +
                        "\"id\":\"notification.reply\"}}}",
                authorizedHeaders());
        assertEquals(200, status.status);
        JSONObject capability = new JSONObject(status.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("notification.reply", capability.getString("id"));
        assertTrue(capability.getBoolean("available"));

        HttpResult policy = post(
                "{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"policy_status\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, policy.status);
        assertEquals("yolo", new JSONObject(policy.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getJSONObject("approvalMode")
                .getString("value"));
    }

    @Test
    public void commandRuntimeRejectsUnknownCommands() throws Exception {
        HttpResult rejected = post(
                "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"phone.explode\",\"arguments\":{}}}}",
                authorizedHeaders());
        assertEquals(200, rejected.status);
        assertTrue(new JSONObject(rejected.body)
                .getJSONObject("result")
                .getBoolean("isError"));
    }

    @Test
    public void commandRuntimeCanInvokeWakeCapabilityThroughDynamicCommandId() throws Exception {
        HttpResult wake = post(
                "{\"jsonrpc\":\"2.0\",\"id\":231,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"phone.wake\",\"arguments\":{}}}}",
                authorizedHeaders());
        assertEquals(200, wake.status);
        JSONObject execution = new JSONObject(wake.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("phone.wake", execution.getString("commandId"));
        assertEquals("completed", execution.getString("status"));
        assertTrue(execution.getJSONObject("result").getBoolean("woke"));
    }

    @Test
    public void commandRuntimeCanInvokeHomeCapabilityThroughDynamicCommandId() throws Exception {
        HttpResult home = post(
                "{\"jsonrpc\":\"2.0\",\"id\":232,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"phone.home\",\"arguments\":{}}}}",
                authorizedHeaders());
        assertEquals(200, home.status);
        JSONObject execution = new JSONObject(home.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("phone.home", execution.getString("commandId"));
        assertEquals("completed", execution.getString("status"));
        assertTrue(execution.getJSONObject("result").getBoolean("atHome"));
    }

    @Test
    public void commandRuntimeCanInvokeRemoteLockCapability() throws Exception {
        HttpResult lock = post(
                "{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"phone.lock\",\"arguments\":{}}}}",
                authorizedHeaders());
        assertEquals(200, lock.status);
        JSONObject execution = new JSONObject(lock.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("phone.lock", execution.getString("commandId"));
        assertTrue(execution.getJSONObject("result").getBoolean("locked"));
    }

    @Test
    public void execCommandToolDelegatesToGeneralProcessExec() throws Exception {
        HttpResult exec = post(
                "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"exec_command\",\"arguments\":{" +
                        "\"command\":\"printf hello\",\"timeoutMs\":5000," +
                        "\"env\":{\"MCP_TEST\":\"yes\"}}}}",
                authorizedHeaders());
        assertEquals(200, exec.status);
        JSONObject result = new JSONObject(exec.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent");
        assertEquals("printf hello", result.getString("command"));
        assertEquals("stub-output", result.getString("stdout"));
        assertEquals(0, result.getInt("exitCode"));
    }

    @Test
    public void workspaceToolsExposePrivateProjectStorage() throws Exception {
        HttpResult info = post(
                "{\"jsonrpc\":\"2.0\",\"id\":27,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"workspace_info\",\"arguments\":{}}}",
                authorizedHeaders());
        assertEquals(200, info.status);
        assertEquals("/data/test/workspaces", new JSONObject(info.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getString("root"));

        HttpResult write = post(
                "{\"jsonrpc\":\"2.0\",\"id\":28,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"workspace_write_file\",\"arguments\":{" +
                        "\"path\":\"hello/index.html\",\"content\":\"hello\"}}}",
                authorizedHeaders());
        assertEquals(200, write.status);
        assertEquals("hello/index.html", new JSONObject(write.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getString("path"));
    }

    @Test
    public void backgroundProcessToolsExposeSessionLifecycle() throws Exception {
        HttpResult exec = post(
                "{\"jsonrpc\":\"2.0\",\"id\":29,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"exec_command\",\"arguments\":{" +
                        "\"command\":\"sleep 60\",\"cwd\":\"hello\",\"background\":true}}}",
                authorizedHeaders());
        assertEquals(200, exec.status);
        assertTrue(new JSONObject(exec.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getBoolean("background"));

        HttpResult output = post(
                "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"read_output\",\"arguments\":{" +
                        "\"sessionId\":\"proc-test\"}}}",
                authorizedHeaders());
        assertEquals(200, output.status);
        assertEquals("running", new JSONObject(output.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getString("status"));

        HttpResult stop = post(
                "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"kill_session\",\"arguments\":{" +
                        "\"sessionId\":\"proc-test\"}}}",
                authorizedHeaders());
        assertEquals(200, stop.status);
        assertTrue(new JSONObject(stop.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getBoolean("stopRequested"));
    }

    @Test
    public void processExecRejectsInvalidExecutionLimits() throws Exception {
        HttpResult rejected = post(
                "{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"command_run\",\"arguments\":{" +
                        "\"commandId\":\"process.exec\",\"arguments\":{" +
                        "\"command\":\"echo nope\",\"timeoutMs\":999999}}}}",
                authorizedHeaders());
        assertEquals(200, rejected.status);
        assertTrue(new JSONObject(rejected.body)
                .getJSONObject("result")
                .getBoolean("isError"));
    }

    @Test
    public void enforcesModernRoutingHeadersAndDiscoversServer() throws Exception {
        Map<String, String> missingMethod = authorizedHeaders();
        missingMethod.put("MCP-Protocol-Version", "2026-07-28");
        HttpResult rejected = post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"server/discover\"," +
                        "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}",
                missingMethod);
        assertEquals(400, rejected.status);

        Map<String, String> modern = authorizedHeaders();
        modern.put("MCP-Protocol-Version", "2026-07-28");
        modern.put("Mcp-Method", "server/discover");
        HttpResult discovery = post(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"server/discover\"," +
                        "\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}",
                modern);
        assertEquals(200, discovery.status);
        JSONObject result = new JSONObject(discovery.body).getJSONObject("result");
        assertEquals("complete", result.getString("resultType"));
        assertTrue(result.getJSONArray("supportedVersions").toString().contains("2026-07-28"));
        assertEquals("PickPico", result.getJSONObject("_meta")
                .getJSONObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    public void returnsJsonRpcParseErrorForMalformedJson() throws Exception {
        HttpResult response = post("{not-json", authorizedHeaders());
        assertEquals(400, response.status);
        assertEquals(-32700, new JSONObject(response.body).getJSONObject("error").getInt("code"));
    }

    @Test
    public void acceptsWorkspaceWritesLargerThanLegacy64KiBLimit() throws Exception {
        String content = "x".repeat(70 * 1024);
        String body = new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 801)
                .put("method", "tools/call")
                .put("params", new JSONObject()
                        .put("name", "workspace_write_file")
                        .put("arguments", new JSONObject()
                                .put("path", "large.txt")
                                .put("content", content)))
                .toString();

        HttpResult response = post(body, authorizedHeaders());
        assertEquals(200, response.status);
        assertEquals(content.length(), new JSONObject(response.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getInt("bytesWritten"));
    }

    private Map<String, String> authorizedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + TOKEN);
        return headers;
    }

    private Map<String, String> thinHeaders() {
        Map<String, String> headers = authorizedHeaders();
        headers.put("X-PickPico-Tool-Profile", "thin-v1");
        return headers;
    }

    private HttpResult post(String body, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/mcp").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = stream == null ? "" : readAll(stream);
        connection.disconnect();
        return new HttpResult(status, responseBody);
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
