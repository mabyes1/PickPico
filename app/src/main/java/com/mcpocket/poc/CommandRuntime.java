package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Capability-oriented command runtime shared by MCP tools and future transports.
 *
 * Transport code should only know command IDs and JSON arguments. Android-specific
 * execution remains behind McpToolActions.
 */
final class CommandRuntime {
    private static final int MAX_HISTORY = 20;

    private interface Handler {
        JSONObject call(JSONObject arguments, long callCount) throws JSONException;
    }

    private static final class Command {
        final String id;
        final String description;
        final String category;
        final String risk;
        final boolean sideEffect;
        final JSONObject inputSchema;
        final Handler handler;

        Command(
                String id,
                String description,
                String category,
                String risk,
                boolean sideEffect,
                JSONObject inputSchema,
                Handler handler) {
            this.id = id;
            this.description = description;
            this.category = category;
            this.risk = risk;
            this.sideEffect = sideEffect;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }

        JSONObject describe() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("description", description)
                    .put("category", category)
                    .put("risk", risk)
                    .put("sideEffect", sideEffect)
                    .put("execution", "synchronous")
                    .put("inputSchema", inputSchema);
        }
    }

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> history = new LinkedHashMap<>();

    CommandRuntime(McpToolActions actions) throws JSONException {
        register(
                "node.info",
                "Return MCPocket node and Android device information.",
                "node",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.serverInfo(callCount));

        register(
                "phone.status",
                "Return live battery, network, storage, and node state.",
                "phone",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.phoneStatus(callCount));

        register(
                "phone.ring",
                "Play an audible find-my-phone alert or stop it early.",
                "phone",
                "physical_action",
                true,
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
                    String action = arguments.optString("action", "start");
                    if (!"start".equals(action) && !"stop".equals(action)) {
                        throw new CommandInputException("phone.ring action must be start or stop");
                    }
                    int durationSeconds = arguments.optInt("durationSeconds", 20);
                    if (durationSeconds < 5 || durationSeconds > 60) {
                        throw new CommandInputException("phone.ring durationSeconds must be between 5 and 60");
                    }
                    return actions.phoneRing(action, durationSeconds, callCount);
                });

        register(
                "phone.lock",
                "Immediately lock the phone when MCPocket has been explicitly enabled as a device administrator.",
                "phone",
                "security_action",
                true,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.phoneLock(callCount));

        register(
                "phone.echo",
                "Show an observable message through vibration and the foreground notification.",
                "phone",
                "physical_action",
                true,
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("text", new JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 1)
                                        .put("maxLength", 512)))
                        .put("required", new JSONArray().put("text"))
                        .put("additionalProperties", false),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    if (text.isEmpty()) {
                        throw new CommandInputException("phone.echo requires a non-empty text argument");
                    }
                    if (text.length() > 512) {
                        throw new CommandInputException("phone.echo text is limited to 512 characters");
                    }
                    return actions.phoneEcho(text, callCount);
                });

        register(
                "workspace.info",
                "Return the MCPocket workspace root, storage state, and available command-line runtimes.",
                "workspace",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.workspaceInfo(callCount));

        register(
                "workspace.list",
                "List files and directories under MCPocket's private workspace root.",
                "workspace",
                "read_only",
                false,
                workspaceListSchema(),
                (arguments, callCount) -> {
                    validateWorkspacePath(arguments.optString("path", "."));
                    int maxDepth = arguments.optInt("maxDepth", 2);
                    int maxEntries = arguments.optInt("maxEntries", 500);
                    if (maxDepth < 0 || maxDepth > 5) {
                        throw new CommandInputException("workspace.list maxDepth must be between 0 and 5");
                    }
                    if (maxEntries < 1 || maxEntries > 2000) {
                        throw new CommandInputException("workspace.list maxEntries must be between 1 and 2000");
                    }
                    return actions.workspaceList(arguments, callCount);
                });

        register(
                "workspace.read",
                "Read one UTF-8 text file from MCPocket's private workspace root.",
                "workspace",
                "read_only",
                false,
                workspaceReadSchema(),
                (arguments, callCount) -> {
                    validateWorkspacePath(arguments.optString("path", ""));
                    int maxBytes = arguments.optInt("maxBytes", 262144);
                    if (maxBytes < 1 || maxBytes > 1048576) {
                        throw new CommandInputException("workspace.read maxBytes must be between 1 and 1048576");
                    }
                    return actions.workspaceReadFile(arguments, callCount);
                });

        register(
                "workspace.write",
                "Write one UTF-8 text file below MCPocket's private workspace root, creating parent directories by default.",
                "workspace",
                "filesystem_write",
                true,
                workspaceWriteSchema(),
                (arguments, callCount) -> {
                    validateWorkspacePath(arguments.optString("path", ""));
                    String content = arguments.optString("content", "");
                    if (content.length() > 1048576) {
                        throw new CommandInputException("workspace.write content is limited to 1048576 characters");
                    }
                    return actions.workspaceWriteFile(arguments, callCount);
                });

        register(
                "node.start",
                "Start one Node.js workspace entry point in MCPocket's isolated :node runtime process.",
                "runtime",
                "process_start",
                true,
                nodeStartSchema(),
                (arguments, callCount) -> {
                    validateWorkspacePath(arguments.optString("entry", ""));
                    return actions.nodeStart(arguments, callCount);
                });

        register(
                "node.status",
                "Return the current MCPocket Node.js runtime process state.",
                "runtime",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.nodeStatus(callCount));

        register(
                "node.stop",
                "Stop the isolated MCPocket Node.js runtime process.",
                "runtime",
                "process_control",
                true,
                nodeStopSchema(),
                (arguments, callCount) -> actions.nodeStop(arguments, callCount));

        register(
                "process.run",
                "Run one predefined diagnostic process. Arbitrary shell strings and arguments are rejected.",
                "process",
                "restricted_process",
                true,
                new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("command", new JSONObject()
                                        .put("type", "string")
                                        .put("enum", new JSONArray()
                                                .put("identity")
                                                .put("kernel")
                                                .put("model_property")
                                                .put("data_disk"))))
                        .put("required", new JSONArray().put("command"))
                        .put("additionalProperties", false),
                (arguments, callCount) -> {
                    String command = arguments.optString("command", "");
                    if (!"identity".equals(command)
                            && !"kernel".equals(command)
                            && !"model_property".equals(command)
                            && !"data_disk".equals(command)) {
                        throw new CommandInputException("process.run command is not allowlisted: " + command);
                    }
                    return actions.phoneExec(command, callCount);
                });

        register(
                "process.exec",
                "Execute a Linux shell command inside MCPocket's Android app sandbox.",
                "process",
                "arbitrary_process",
                true,
                execCommandSchema(),
                (arguments, callCount) -> {
                    validateExecArguments(arguments);
                    return actions.execCommand(arguments, callCount);
                });

        register(
                "process.output",
                "Read captured stdout/stderr and status for a background process session.",
                "process",
                "read_only",
                false,
                processSessionSchema(false),
                (arguments, callCount) -> actions.readProcessOutput(arguments, callCount));

        register(
                "process.stop",
                "Stop a background process session started by process.exec with background=true.",
                "process",
                "process_control",
                true,
                processSessionSchema(true),
                (arguments, callCount) -> actions.killProcessSession(arguments, callCount));
    }

    JSONObject list() throws JSONException {
        JSONArray result = new JSONArray();
        for (Command command : commands.values()) {
            result.put(command.describe());
        }
        return new JSONObject()
                .put("commands", result)
                .put("count", result.length());
    }

    JSONArray commandIds() {
        JSONArray ids = new JSONArray();
        for (String id : commands.keySet()) {
            ids.put(id);
        }
        return ids;
    }

    JSONObject execute(String commandId, JSONObject arguments, long callCount) throws JSONException {
        Command command = requireCommand(commandId);
        return command.handler.call(arguments == null ? new JSONObject() : arguments, callCount);
    }

    JSONObject run(String commandId, JSONObject arguments, long callCount) throws JSONException {
        Command command = requireCommand(commandId);
        String executionId = "cmd-" + UUID.randomUUID();
        String startedAt = Instant.now().toString();

        try {
            JSONObject result = command.handler.call(
                    arguments == null ? new JSONObject() : arguments,
                    callCount);
            JSONObject execution = new JSONObject()
                    .put("executionId", executionId)
                    .put("commandId", commandId)
                    .put("status", "completed")
                    .put("startedAt", startedAt)
                    .put("completedAt", Instant.now().toString())
                    .put("result", result);
            remember(executionId, execution);
            return execution;
        } catch (CommandInputException error) {
            JSONObject execution = new JSONObject()
                    .put("executionId", executionId)
                    .put("commandId", commandId)
                    .put("status", "rejected")
                    .put("startedAt", startedAt)
                    .put("completedAt", Instant.now().toString())
                    .put("error", error.getMessage());
            remember(executionId, execution);
            throw error;
        }
    }

    synchronized JSONObject status(String executionId) throws JSONException {
        if (executionId != null && !executionId.isEmpty()) {
            JSONObject execution = history.get(executionId);
            if (execution == null) {
                throw new CommandInputException("Unknown executionId: " + executionId);
            }
            return new JSONObject(execution.toString());
        }

        JSONArray recent = new JSONArray();
        for (JSONObject execution : history.values()) {
            recent.put(new JSONObject(execution.toString()));
        }
        return new JSONObject()
                .put("recent", recent)
                .put("count", recent.length());
    }

    private Command requireCommand(String commandId) {
        Command command = commands.get(commandId);
        if (command == null) {
            throw new CommandInputException("Unknown command: " + commandId);
        }
        return command;
    }

    private void register(
            String id,
            String description,
            String category,
            String risk,
            boolean sideEffect,
            JSONObject inputSchema,
            Handler handler) {
        if (commands.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate command: " + id);
        }
        commands.put(id, new Command(
                id, description, category, risk, sideEffect, inputSchema, handler));
    }

    private synchronized void remember(String executionId, JSONObject execution) throws JSONException {
        history.put(executionId, new JSONObject(execution.toString()));
        while (history.size() > MAX_HISTORY) {
            String oldest = history.keySet().iterator().next();
            history.remove(oldest);
        }
    }

    private static JSONObject noArgumentsSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject())
                .put("additionalProperties", false);
    }

    static JSONObject execCommandSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("command", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 8192)
                                .put("description", "Shell command executed by /system/bin/sh -c."))
                        .put("cwd", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1024)
                                .put("description", "Optional working directory visible to the MCPocket app sandbox."))
                        .put("env", new JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", new JSONObject().put("type", "string"))
                                .put("description", "Optional environment variables added or overridden for the process."))
                        .put("stdin", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 65536)
                                .put("description", "Optional UTF-8 stdin payload."))
                        .put("timeoutMs", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 100)
                                .put("maximum", 120000)
                                .put("default", 30000))
                        .put("maxOutputBytes", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1024)
                                .put("maximum", 262144)
                                .put("default", 65536))
                        .put("background", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)
                                .put("description", "Keep the process alive as a managed session and return immediately.")))
                .put("required", new JSONArray().put("command"))
                .put("additionalProperties", false);
    }

    static JSONObject workspaceListSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("path", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1024)
                                .put("default", "."))
                        .put("maxDepth", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", 5)
                                .put("default", 2))
                        .put("maxEntries", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 2000)
                                .put("default", 500)))
                .put("additionalProperties", false);
    }

    static JSONObject workspaceReadSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("path", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 1024))
                        .put("maxBytes", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 1048576)
                                .put("default", 262144)))
                .put("required", new JSONArray().put("path"))
                .put("additionalProperties", false);
    }

    static JSONObject workspaceWriteSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("path", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 1024))
                        .put("content", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1048576))
                        .put("append", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false))
                        .put("createParents", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("required", new JSONArray().put("path").put("content"))
                .put("additionalProperties", false);
    }

    static JSONObject processSessionSchema(boolean includeForce) throws JSONException {
        JSONObject properties = new JSONObject()
                .put("sessionId", new JSONObject()
                        .put("type", "string")
                        .put("minLength", 1)
                        .put("maxLength", 128));
        if (includeForce) {
            properties.put("force", new JSONObject()
                    .put("type", "boolean")
                    .put("default", false));
        }
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("sessionId"))
                .put("additionalProperties", false);
    }

    static JSONObject nodeStartSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("entry", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 1024)
                                .put("description", "Workspace-relative JavaScript entry point.")))
                .put("required", new JSONArray().put("entry"))
                .put("additionalProperties", false);
    }

    static JSONObject nodeStopSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("force", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)))
                .put("additionalProperties", false);
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new CommandInputException("workspace path must not be empty");
        }
        if (path.length() > 1024) {
            throw new CommandInputException("workspace path is limited to 1024 characters");
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new CommandInputException("workspace paths must be relative to the MCPocket workspace root");
        }
    }

    private static void validateExecArguments(JSONObject arguments) {
        String command = arguments.optString("command", "");
        if (command.isEmpty()) {
            throw new CommandInputException("process.exec requires a non-empty command");
        }
        if (command.length() > 8192) {
            throw new CommandInputException("process.exec command is limited to 8192 characters");
        }

        String cwd = arguments.optString("cwd", "");
        if (cwd.length() > 1024) {
            throw new CommandInputException("process.exec cwd is limited to 1024 characters");
        }

        String stdin = arguments.optString("stdin", "");
        if (stdin.length() > 65536) {
            throw new CommandInputException("process.exec stdin is limited to 65536 characters");
        }

        int timeoutMs = arguments.optInt("timeoutMs", 30000);
        if (timeoutMs < 100 || timeoutMs > 120000) {
            throw new CommandInputException("process.exec timeoutMs must be between 100 and 120000");
        }

        int maxOutputBytes = arguments.optInt("maxOutputBytes", 65536);
        if (maxOutputBytes < 1024 || maxOutputBytes > 262144) {
            throw new CommandInputException("process.exec maxOutputBytes must be between 1024 and 262144");
        }

        JSONObject env = arguments.optJSONObject("env");
        if (env != null) {
            if (env.length() > 64) {
                throw new CommandInputException("process.exec env is limited to 64 variables");
            }
            Iterator<String> keys = env.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = env.opt(key);
                if (key.isEmpty() || key.length() > 128) {
                    throw new CommandInputException("process.exec env keys must be 1-128 characters");
                }
                if (!(value instanceof String)) {
                    throw new CommandInputException("process.exec env values must be strings");
                }
                if (((String) value).length() > 4096) {
                    throw new CommandInputException("process.exec env values are limited to 4096 characters");
                }
            }
        }
    }

    static final class CommandInputException extends RuntimeException {
        CommandInputException(String message) {
            super(message);
        }
    }
}
