package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

final class McpProtocol {
    static final String MODERN_VERSION = "2026-07-28";
    private static final String DEFAULT_LEGACY_VERSION = "2025-11-25";
    private static final String SERVER_NAME = "PickPico";
    private static final String SERVER_INFO_META = "io.modelcontextprotocol/serverInfo";
    private static final Set<String> LEGACY_VERSIONS = new HashSet<>(Arrays.asList(
            "2025-11-25", "2025-06-18", "2025-03-26"));

    private final McpToolRegistry tools;
    private final AtomicLong callCount;

    McpProtocol(McpToolActions actions, AtomicLong callCount) throws JSONException {
        this.tools = new McpToolRegistry(actions);
        this.callCount = callCount;
    }

    Response handle(JSONObject request, String headerProtocolVersion, String requestedToolProfile) throws JSONException {
        Object id = request.has("id") ? request.opt("id") : JSONObject.NULL;
        String toolProfile = McpToolRegistry.PROFILE_THIN.equals(requestedToolProfile)
                ? McpToolRegistry.PROFILE_THIN
                : "full";
        boolean notification = !request.has("id");
        if (!"2.0".equals(request.optString("jsonrpc"))) {
            return new Response(400, error(id, -32600, "Invalid JSON-RPC request"), selectedVersion(headerProtocolVersion));
        }

        String method = request.optString("method", "");
        boolean modern = MODERN_VERSION.equals(headerProtocolVersion)
                || "server/discover".equals(method)
                || MODERN_VERSION.equals(protocolVersionFromMeta(request));
        String selectedVersion = modern ? MODERN_VERSION : selectedVersion(headerProtocolVersion);

        if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) {
            return new Response(202, null, selectedVersion);
        }
        if (notification) {
            return new Response(202, null, selectedVersion);
        }

        JSONObject result;
        switch (method) {
            case "initialize":
                result = initialize(request.optJSONObject("params"), toolProfile);
                selectedVersion = result.getString("protocolVersion");
                break;
            case "server/discover":
                result = discover(toolProfile);
                modern = true;
                selectedVersion = MODERN_VERSION;
                break;
            case "ping":
                result = new JSONObject();
                break;
            case "tools/list":
                result = listTools(modern, toolProfile);
                break;
            case "tools/call":
                result = callTool(request.optJSONObject("params"), modern, toolProfile);
                break;
            default:
                return new Response(200, error(id, -32601, "Method not found: " + method), selectedVersion);
        }
        if (modern) {
            decorateModern(result);
        }
        return new Response(200, success(id, result), selectedVersion);
    }

    private JSONObject initialize(JSONObject params, String toolProfile) throws JSONException {
        String requested = params == null ? "" : params.optString("protocolVersion", "");
        String selected = LEGACY_VERSIONS.contains(requested) ? requested : DEFAULT_LEGACY_VERSION;
        return new JSONObject()
                .put("protocolVersion", selected)
                .put("capabilities", capabilities())
                .put("serverInfo", implementation())
                .put("instructions", instructions(toolProfile));
    }

    private JSONObject discover(String toolProfile) throws JSONException {
        return new JSONObject()
                .put("supportedVersions", new JSONArray().put(MODERN_VERSION).put(DEFAULT_LEGACY_VERSION))
                .put("capabilities", capabilities())
                .put("instructions", instructions(toolProfile))
                .put("toolProfile", toolProfile)
                .put("ttlMs", 0)
                .put("cacheScope", "private");
    }

    private JSONObject listTools(boolean modern, String toolProfile) throws JSONException {
        return tools.list(modern, toolProfile);
    }

    private JSONObject callTool(JSONObject params, boolean modern, String toolProfile) throws JSONException {
        long currentCall = callCount.incrementAndGet();
        return tools.call(params, modern, toolProfile, currentCall);
    }

    private static String instructions(String toolProfile) {
        if (McpToolRegistry.PROFILE_THIN.equals(toolProfile)) {
            return "PickPico is a dynamic real-world Mobile Agent Node. The small top-level MCP tool list is a stable gateway, "
                    + "not the complete device capability set. Before saying that a phone, app, screen, sensor, file, "
                    + "contact, calendar, notification, physical-world, or human-assisted action cannot be performed, "
                    + "call capability_search first. Execute discovered abilities with command_run. Representative "
                    + "capabilities include screen.capture, camera.capture, ui.inspect, ui.action, human.help, "
                    + "notification.reply, app.launch, location.get, workspace.read, and process.exec; examples are not exhaustive. "
                    + "Capability results report available/setup-required/disabled state, so guide required human setup rather than refusing prematurely.";
        }
        return "PickPico is a user-started real-world Mobile Agent Node for Android. It lets an external Agent execute work, "
                + "sense through phone hardware, operate apps, and interact with people nearby. The full compatibility profile "
                + "exposes direct tools plus the dynamic capability runtime.";
    }

    private static JSONObject capabilities() throws JSONException {
        return new JSONObject().put("tools", new JSONObject().put("listChanged", false));
    }

    private static JSONObject implementation() throws JSONException {
        return new JSONObject().put("name", SERVER_NAME).put("version", BuildConfig.VERSION_NAME);
    }

    static void decorateModern(JSONObject result) throws JSONException {
        if (!result.has("resultType")) {
            result.put("resultType", "complete");
        }
        JSONObject meta = result.optJSONObject("_meta");
        if (meta == null) {
            meta = new JSONObject();
            result.put("_meta", meta);
        }
        meta.put(SERVER_INFO_META, implementation());
    }

    private static String protocolVersionFromMeta(JSONObject request) {
        JSONObject params = request.optJSONObject("params");
        JSONObject meta = params == null ? null : params.optJSONObject("_meta");
        return meta == null ? "" : meta.optString("io.modelcontextprotocol/protocolVersion", "");
    }

    private static String selectedVersion(String headerVersion) {
        return LEGACY_VERSIONS.contains(headerVersion) ? headerVersion : DEFAULT_LEGACY_VERSION;
    }

    private static JSONObject success(Object id, JSONObject result) throws JSONException {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("result", result);
    }

    static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }

    static JSONObject parseError() throws JSONException {
        return error(JSONObject.NULL, -32700, "Parse error");
    }

    static final class Response {
        final int httpStatus;
        final JSONObject body;
        final String protocolVersion;

        Response(int httpStatus, JSONObject body, String protocolVersion) {
            this.httpStatus = httpStatus;
            this.body = body;
            this.protocolVersion = protocolVersion;
        }
    }
}
