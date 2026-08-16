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
            public JSONObject phoneRing(String action, int durationSeconds, long callCount) throws org.json.JSONException {
                return new JSONObject()
                        .put("action", action)
                        .put("durationSeconds", durationSeconds)
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
