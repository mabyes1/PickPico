package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;

public final class McpHttpServerTest {
    private static final String TOKEN = "unit-test-token";

    private McpHttpServer server;
    private int port;
    private final AtomicReference<String> echoed = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        server = new McpHttpServer(port, TOKEN, new McpToolActions() {
            @Override
            public JSONObject serverInfo(long callCount) throws org.json.JSONException {
                return new JSONObject().put("name", "test-node").put("toolCallCount", callCount);
            }

            @Override
            public JSONObject phoneStatus(long callCount) throws org.json.JSONException {
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
            public JSONObject phoneEcho(String text, long callCount) throws org.json.JSONException {
                echoed.set(text);
                return new JSONObject().put("echo", text).put("toolCallCount", callCount);
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
    public void handlesLegacyInitializeAndPhoneEcho() throws Exception {
        HttpResult initialize = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                        "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," +
                        "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                authorizedHeaders());
        assertEquals(200, initialize.status);
        assertEquals("2025-11-25",
                new JSONObject(initialize.body).getJSONObject("result").getString("protocolVersion"));

        HttpResult echo = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"phone_echo\",\"arguments\":{\"text\":\"hello phone\"}}}",
                authorizedHeaders());
        assertEquals(200, echo.status);
        assertEquals("hello phone", echoed.get());
        assertEquals("hello phone", new JSONObject(echo.body)
                .getJSONObject("result")
                .getJSONObject("structuredContent")
                .getString("echo"));
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
        assertEquals(13, listed.getInt("count"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.ring"));
        assertTrue(listed.getJSONArray("commands").toString().contains("phone.lock"));
        assertTrue(listed.getJSONArray("commands").toString().contains("workspace.write"));
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
        assertEquals("MCPocket", result.getJSONObject("_meta")
                .getJSONObject("io.modelcontextprotocol/serverInfo").getString("name"));
    }

    @Test
    public void returnsJsonRpcParseErrorForMalformedJson() throws Exception {
        HttpResult response = post("{not-json", authorizedHeaders());
        assertEquals(400, response.status);
        assertEquals(-32700, new JSONObject(response.body).getJSONObject("error").getInt("code"));
    }

    private Map<String, String> authorizedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + TOKEN);
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
