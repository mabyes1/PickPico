package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure-Java registry for MCP tool metadata, validation, and dispatch. */
final class McpToolRegistry {
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
        CommandRuntime runtime = new CommandRuntime(actions);

        register(
                "command_list",
                "List capability-oriented commands exposed by the MCPocket command runtime.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.list());

        register(
                "command_run",
                "Run one MCPocket command by capability ID with structured JSON arguments.",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("commandId", new JSONObject()
                                        .put("type", "string")
                                        .put("enum", runtime.commandIds()))
                                .put("arguments", new JSONObject()
                                        .put("type", "object")
                                        .put("default", new JSONObject())))
                        .put("required", new JSONArray().put("commandId"))
                        .put("additionalProperties", false),
                (arguments, callCount) -> runtime.run(
                        arguments.optString("commandId", ""),
                        arguments.optJSONObject("arguments"),
                        callCount));

        register(
                "command_status",
                "Return one command execution by ID, or the recent in-memory execution history.",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("executionId", new JSONObject().put("type", "string")))
                        .put("additionalProperties", false),
                (arguments, callCount) -> runtime.status(arguments.optString("executionId", "")));

        register(
                "exec_command",
                "Execute a Linux shell command inside MCPocket's Android app sandbox. Relative cwd values resolve below the private workspace root; background=true returns a managed process session.",
                CommandRuntime.execCommandSchema(),
                (arguments, callCount) -> runtime.execute("process.exec", arguments, callCount));

        register(
                "read_output",
                "Read captured stdout/stderr and current status for a background exec_command session.",
                CommandRuntime.processSessionSchema(false),
                (arguments, callCount) -> runtime.execute("process.output", arguments, callCount));

        register(
                "kill_session",
                "Stop a background exec_command process session.",
                CommandRuntime.processSessionSchema(true),
                (arguments, callCount) -> runtime.execute("process.stop", arguments, callCount));

        register(
                "workspace_info",
                "Return MCPocket's private workspace root, free storage, execution features, and detected command-line runtimes.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.execute("workspace.info", arguments, callCount));

        register(
                "workspace_list",
                "List files and directories below MCPocket's private workspace root.",
                CommandRuntime.workspaceListSchema(),
                (arguments, callCount) -> runtime.execute("workspace.list", arguments, callCount));

        register(
                "workspace_read_file",
                "Read one UTF-8 text file below MCPocket's private workspace root.",
                CommandRuntime.workspaceReadSchema(),
                (arguments, callCount) -> runtime.execute("workspace.read", arguments, callCount));

        register(
                "workspace_write_file",
                "Write one UTF-8 text file below MCPocket's private workspace root.",
                CommandRuntime.workspaceWriteSchema(),
                (arguments, callCount) -> runtime.execute("workspace.write", arguments, callCount));

        register(
                "node_start",
                "Start a workspace JavaScript entry point inside MCPocket's isolated Node.js runtime process.",
                CommandRuntime.nodeStartSchema(),
                (arguments, callCount) -> runtime.execute("node.start", arguments, callCount));

        register(
                "node_status",
                "Return the current MCPocket Node.js runtime state.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.execute("node.status", arguments, callCount));

        register(
                "node_stop",
                "Stop MCPocket's isolated Node.js runtime process.",
                CommandRuntime.nodeStopSchema(),
                (arguments, callCount) -> runtime.execute("node.stop", arguments, callCount));

        register(
                "app_update",
                "Download a signed MCPocket APK, verify its SHA-256/package/signing certificate/version, then invoke Android's package installer.",
                CommandRuntime.appUpdateSchema(),
                (arguments, callCount) -> runtime.execute("app.update", arguments, callCount));

        register(
                "app_update_status",
                "Return MCPocket self-update state and whether Android allows MCPocket to request package installs.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.execute("app.update_status", arguments, callCount));

        register(
                "server_info",
                "Return safe MCPocket node, Android device, endpoint, uptime, and call-count information.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.execute("node.info", arguments, callCount));

        register(
                "phone_status",
                "Return a live Android phone snapshot including battery, network, storage, and node state.",
                noArgumentsSchema(),
                (arguments, callCount) -> runtime.execute("phone.status", arguments, callCount));

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
                    return runtime.execute("process.run", arguments, callCount);
                });

        register(
                "phone_ring",
                "Play a temporary audible ring on the Android phone, or stop it early.",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("action", new JSONObject()
                                        .put("type", "string")
                                        .put("enum", new JSONArray().put("start").put("stop"))
                                        .put("default", "start"))
                                .put("durationSeconds", new JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 5)
                                        .put("maximum", 60)
                                        .put("default", 20)))
                        .put("additionalProperties", false),
                (arguments, callCount) -> {
                    return runtime.execute("phone.ring", arguments, callCount);
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
                    return runtime.execute("phone.echo", arguments, callCount);
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
        } catch (ToolInputException | CommandRuntime.CommandInputException error) {
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
