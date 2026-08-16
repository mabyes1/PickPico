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
    private static final String SERVER_NAME = "MCPocket";
    private static final String SERVER_VERSION = "0.1.0";
    private static final String SERVER_INFO_META = "io.modelcontextprotocol/serverInfo";
    private static final Set<String> LEGACY_VERSIONS = new HashSet<>(Arrays.asList(
            "2025-11-25", "2025-06-18", "2025-03-26"));

    private final McpHttpServer.ToolActions actions;
    private final AtomicLong callCount;

    McpProtocol(McpHttpServer.ToolActions actions, AtomicLong callCount) {
        this.actions = actions;
        this.callCount = callCount;
    }

    Response handle(JSONObject request, String headerProtocolVersion) throws JSONException {
        Object id = request.has("id") ? request.opt("id") : JSONObject.NULL;
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
                result = initialize(request.optJSONObject("params"));
                selectedVersion = result.getString("protocolVersion");
                break;
            case "server/discover":
                result = discover();
                modern = true;
                selectedVersion = MODERN_VERSION;
                break;
            case "ping":
                result = new JSONObject();
                break;
            case "tools/list":
                result = listTools(modern);
                break;
            case "tools/call":
                result = callTool(request.optJSONObject("params"), modern);
                break;
            default:
                return new Response(200, error(id, -32601, "Method not found: " + method), selectedVersion);
        }
        if (modern) {
            decorateModern(result);
        }
        return new Response(200, success(id, result), selectedVersion);
    }

    private JSONObject initialize(JSONObject params) throws JSONException {
        String requested = params == null ? "" : params.optString("protocolVersion", "");
        String selected = LEGACY_VERSIONS.contains(requested) ? requested : DEFAULT_LEGACY_VERSION;
        return new JSONObject()
                .put("protocolVersion", selected)
                .put("capabilities", capabilities())
                .put("serverInfo", implementation())
                .put("instructions",
                        "MCPocket is a user-started Android execution node. Use server_info for status and " +
                                "phone_echo for an observable, allowlisted phone action.");
    }

    private JSONObject discover() throws JSONException {
        return new JSONObject()
                .put("supportedVersions", new JSONArray().put(MODERN_VERSION).put(DEFAULT_LEGACY_VERSION))
                .put("capabilities", capabilities())
                .put("instructions",
                        "MCPocket exposes only allowlisted phone tools; arbitrary shell execution is disabled.")
                .put("ttlMs", 0)
                .put("cacheScope", "private");
    }

    private JSONObject listTools(boolean modern) throws JSONException {
        JSONArray tools = new JSONArray()
                .put(new JSONObject()
                        .put("name", "server_info")
                        .put("description", "Return safe MCPocket node, Android device, endpoint, uptime, and call-count information.")
                        .put("inputSchema", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject())
                                .put("additionalProperties", false)))
                .put(new JSONObject()
                        .put("name", "phone_echo")
                        .put("description",
                                "Echo text on the Android node, vibrate the phone briefly, and update its foreground notification.")
                        .put("inputSchema", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("text", new JSONObject()
                                                .put("type", "string")
                                                .put("minLength", 1)
                                                .put("maxLength", 512)
                                                .put("description", "Text to echo on the phone.")))
                                .put("required", new JSONArray().put("text"))
                                .put("additionalProperties", false)));
        JSONObject result = new JSONObject().put("tools", tools);
        if (modern) {
            result.put("ttlMs", 0).put("cacheScope", "private");
        }
        return result;
    }

    private JSONObject callTool(JSONObject params, boolean modern) throws JSONException {
        if (params == null) {
            return toolError("Missing tool parameters", modern);
        }
        String name = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }
        long currentCall = callCount.incrementAndGet();
        JSONObject structured;
        if ("server_info".equals(name)) {
            structured = actions.serverInfo(currentCall);
        } else if ("phone_echo".equals(name)) {
            String text = arguments.optString("text", "");
            if (text.isEmpty()) {
                return toolError("phone_echo requires a non-empty text argument", modern);
            }
            if (text.length() > 512) {
                return toolError("phone_echo text is limited to 512 characters", modern);
            }
            structured = actions.phoneEcho(text, currentCall);
        } else {
            return toolError("Unknown tool: " + name, modern);
        }
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", structured.toString(2))))
                .put("structuredContent", structured)
                .put("isError", false);
    }

    private static JSONObject toolError(String message, boolean modern) throws JSONException {
        JSONObject result = new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", message)))
                .put("isError", true);
        if (modern) {
            decorateModern(result);
        }
        return result;
    }

    private static JSONObject capabilities() throws JSONException {
        return new JSONObject().put("tools", new JSONObject().put("listChanged", false));
    }

    private static JSONObject implementation() throws JSONException {
        return new JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION);
    }

    private static void decorateModern(JSONObject result) throws JSONException {
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
