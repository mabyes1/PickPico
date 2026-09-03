package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory Agent task lifecycle for PickPico's Mobile Agent Node.
 *
 * A command execution is a single action. A task is the longer-lived unit of work
 * that can span many commands, device capabilities, and human interactions.
 */
final class AgentTaskRuntime {
    private static final int MAX_TASKS = 50;
    private static final String[] VALID_STATES = new String[] {
            "created", "running", "blocked", "waiting_human", "completed", "failed", "cancelled"
    };

    private final LinkedHashMap<String, JSONObject> tasks = new LinkedHashMap<>();

    synchronized JSONObject info() throws JSONException {
        int active = 0;
        for (JSONObject task : tasks.values()) {
            String status = task.optString("status", "");
            if (!isTerminal(status)) {
                active++;
            }
        }
        return new JSONObject()
                .put("product", "PickPico")
                .put("role", "mobile_agent_node")
                .put("taskLifecycle", true)
                .put("taskStates", new JSONArray(VALID_STATES))
                .put("retainedTasks", tasks.size())
                .put("activeTasks", active);
    }

    synchronized JSONObject create(JSONObject arguments) throws JSONException {
        String objective = requireText(arguments, "objective", 1, 4096);
        String title = optionalText(arguments, "title", 160);
        String agent = optionalText(arguments, "agent", 160);

        String taskId = "task-" + UUID.randomUUID();
        String now = Instant.now().toString();
        JSONObject task = new JSONObject()
                .put("taskId", taskId)
                .put("title", title.isEmpty() ? JSONObject.NULL : title)
                .put("objective", objective)
                .put("agent", agent.isEmpty() ? JSONObject.NULL : agent)
                .put("status", "created")
                .put("createdAt", now)
                .put("updatedAt", now)
                .put("notes", new JSONArray());

        JSONObject context = arguments.optJSONObject("context");
        if (context != null) {
            task.put("context", new JSONObject(context.toString()));
        }

        tasks.put(taskId, task);
        trim();
        return copy(task);
    }

    synchronized JSONObject update(JSONObject arguments) throws JSONException {
        String taskId = requireText(arguments, "taskId", 1, 128);
        JSONObject task = tasks.get(taskId);
        if (task == null) {
            throw new CommandRuntime.CommandInputException("Unknown taskId: " + taskId);
        }

        String status = optionalText(arguments, "status", 64);
        if (!status.isEmpty()) {
            requireState(status);
            String previous = task.optString("status", "created");
            if (isTerminal(previous) && !previous.equals(status)) {
                throw new CommandRuntime.CommandInputException(
                        "Task is already terminal with status: " + previous);
            }
            task.put("status", status);
            if (isTerminal(status)) {
                task.put("completedAt", Instant.now().toString());
            }
        }

        String note = optionalText(arguments, "note", 4096);
        if (!note.isEmpty()) {
            task.getJSONArray("notes").put(new JSONObject()
                    .put("at", Instant.now().toString())
                    .put("text", note));
        }

        task.put("updatedAt", Instant.now().toString());
        return copy(task);
    }

    synchronized JSONObject status(JSONObject arguments) throws JSONException {
        String taskId = optionalText(arguments, "taskId", 128);
        if (!taskId.isEmpty()) {
            JSONObject task = tasks.get(taskId);
            if (task == null) {
                throw new CommandRuntime.CommandInputException("Unknown taskId: " + taskId);
            }
            return copy(task);
        }

        JSONArray recent = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : tasks.entrySet()) {
            recent.put(copy(entry.getValue()));
        }
        return new JSONObject().put("tasks", recent).put("count", recent.length());
    }

    private void trim() {
        while (tasks.size() > MAX_TASKS) {
            String oldest = tasks.keySet().iterator().next();
            tasks.remove(oldest);
        }
    }

    private static void requireState(String state) {
        for (String valid : VALID_STATES) {
            if (valid.equals(state)) {
                return;
            }
        }
        throw new CommandRuntime.CommandInputException("Invalid task status: " + state);
    }

    private static boolean isTerminal(String state) {
        return "completed".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }

    private static String requireText(JSONObject arguments, String key, int min, int max) {
        String value = arguments.optString(key, "").trim();
        if (value.length() < min || value.length() > max) {
            throw new CommandRuntime.CommandInputException(
                    key + " length must be between " + min + " and " + max);
        }
        return value;
    }

    private static String optionalText(JSONObject arguments, String key, int max) {
        if (!arguments.has(key) || arguments.isNull(key)) {
            return "";
        }
        String value = arguments.optString(key, "").trim();
        if (value.length() > max) {
            throw new CommandRuntime.CommandInputException(key + " length must be <= " + max);
        }
        return value;
    }

    private static JSONObject copy(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }
}
