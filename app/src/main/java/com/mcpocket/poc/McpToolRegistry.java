package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pure-Java registry for MCP tool metadata, validation, and dispatch. */
final class McpToolRegistry {
    private static final Set<String> PHONE_EXEC_COMMANDS = new HashSet<>(Arrays.asList(
            "identity", "kernel", "model_property", "data_disk"));

    private interface Handler {
        JSONObject call(JSONObject arguments, long callCount) throws JSONException;
    }

    private static final class Tool {
        final String name;
        final String description;
        final JSONObject inputSchema;
        final Handler handler;

        Tool(String name, String description, JSONObject inputSchema, Handler handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }

        JSONObject describe() throws JSONException {
            return new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("inputSchema", inputSchema);
        }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    McpToolRegistry(McpToolActions actions) throws JSONException {
        register(
                "server_info",
                "Return safe MCPocket node, Android device, endpoint, uptime, and call-count information.",
                noArgumentsSchema(),
                (arguments, callCount) -> actions.serverInfo(callCount));

        register(
                "phone_status",
                "Return a live Android phone snapshot including battery, network, storage, and node state.",
                noArgumentsSchema(),
                (arguments, callCount) -> actions.phoneStatus(callCount));

        register(
                "phone_exec",
                "Run one predefined diagnostic process on the Android phone. Free-form shell and arguments are not accepted.",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("command", new JSONObject()
                                        .put("type", "string")
                                        .put("enum", new JSONArray()
                                                .put("identity")
                                                .put("kernel")
                                                .put("model_property")
                                                .put("data_disk"))
                                        .put("description", "Predefined diagnostic command to execute.")))
                        .put("required", new JSONArray().put("command"))
                        .put("additionalProperties", false),
                (arguments, callCount) -> {
                    String command = arguments.optString("command", "");
                    if (!PHONE_EXEC_COMMANDS.contains(command)) {
                        throw new ToolInputException("phone_exec command is not allowlisted: " + command);
                    }
                    return actions.phoneExec(command, callCount);
                });

        register(
                "phone_echo",
                "Echo text on the Android node, vibrate the phone briefly, and update its foreground notification.",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("text", new JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 1)
                                        .put("maxLength", 512)
                                        .put("description", "Text to echo on the phone.")))
                        .put("required", new JSONArray().put("text"))
                        .put("additionalProperties", false),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    if (text.isEmpty()) {
                        throw new ToolInputException("phone_echo requires a non-empty text argument");
                    }
                    if (text.length() > 512) {
                        throw new ToolInputException("phone_echo text is limited to 512 characters");
                    }
                    return actions.phoneEcho(text, callCount);
                });
    }

    JSONObject list(boolean modern) throws JSONException {
        JSONArray resultTools = new JSONArray();
        for (Tool tool : tools.values()) {
            resultTools.put(tool.describe());
        }
        JSONObject result = new JSONObject().put("tools", resultTools);
        if (modern) {
            result.put("ttlMs", 0).put("cacheScope", "private");
        }
        return result;
    }

    JSONObject call(JSONObject params, boolean modern, long callCount) throws JSONException {
        if (params == null) {
            return toolError("Missing tool parameters", modern);
        }
        String name = params.optString("name", "");
        Tool tool = tools.get(name);
        if (tool == null) {
            return toolError("Unknown tool: " + name, modern);
        }
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }
        try {
            JSONObject structured = tool.handler.call(arguments, callCount);
            return new JSONObject()
                    .put("content", new JSONArray().put(new JSONObject()
                            .put("type", "text")
                            .put("text", structured.toString(2))))
                    .put("structuredContent", structured)
                    .put("isError", false);
        } catch (ToolInputException error) {
            return toolError(error.getMessage(), modern);
        }
    }

    private void register(String name, String description, JSONObject inputSchema, Handler handler) {
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate MCP tool: " + name);
        }
        tools.put(name, new Tool(name, description, inputSchema, handler));
    }

    private static JSONObject noArgumentsSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject())
                .put("additionalProperties", false);
    }

    private static JSONObject toolError(String message, boolean modern) throws JSONException {
        JSONObject result = new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", message)))
                .put("isError", true);
        if (modern) {
            McpProtocol.decorateModern(result);
        }
        return result;
    }

    private static final class ToolInputException extends RuntimeException {
        ToolInputException(String message) {
            super(message);
        }
    }
}
