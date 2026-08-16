package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    static final class CommandInputException extends RuntimeException {
        CommandInputException(String message) {
            super(message);
        }
    }
}
