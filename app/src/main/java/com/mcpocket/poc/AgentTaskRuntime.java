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
    static final long ACTIVE_PROJECTION_LEASE_MS = 120_000L;
    /**
     * An unfinished task with no task update and no command from its Agent for
     * this long is closed as {@code expired}. Agents that crash or forget to
     * finish must not keep counting as active forever.
     */
    static final long TASK_IDLE_EXPIRY_MS = 600_000L;
    private static final String[] VALID_STATES = new String[] {
            "created", "running", "blocked", "waiting_human", "completed", "failed", "cancelled", "expired"
    };

    private final LinkedHashMap<String, JSONObject> tasks = new LinkedHashMap<>();
    private final java.util.function.IntConsumer awakeTasksChanged;

    AgentTaskRuntime() { this(count -> {}); }
    AgentTaskRuntime(java.util.function.IntConsumer listener) { awakeTasksChanged = listener; }

    synchronized int awakeTaskCount() {
        int count = 0;
        for (JSONObject task : tasks.values()) if (!isTerminal(task.optString("status"))) count++;
        return count;
    }

    /**
     * Record activity from {@code agent} and close idle tasks. Called for every
     * dispatched command so an Agent that keeps working keeps its task alive
     * even without explicit task updates.
     */
    synchronized void touch(String agent, long nowMs) throws JSONException {
        int before = awakeTaskCount();
        expireIdle(nowMs);
        if (agent != null && !agent.isEmpty()) {
            String at = Instant.ofEpochMilli(nowMs).toString();
            for (JSONObject task : tasks.values())
                if (!isTerminal(task.optString("status")) && agent.equals(task.optString("agent")))
                    task.put("lastActivityAt", at);
        }
        int after = awakeTaskCount();
        if (after != before) awakeTasksChanged.accept(after);
    }

    /** Close unfinished tasks idle for longer than {@link #TASK_IDLE_EXPIRY_MS}. */
    synchronized boolean expireIdle(long nowMs) throws JSONException {
        boolean changed = false;
        for (JSONObject task : tasks.values()) {
            if (isTerminal(task.optString("status"))) continue;
            long last = lastActivityMs(task);
            if (last > 0L && nowMs - last < TASK_IDLE_EXPIRY_MS) continue;
            String at = Instant.ofEpochMilli(nowMs).toString();
            task.put("status", "expired")
                    .put("completedAt", at)
                    .put("updatedAt", at)
                    .put("leaseExpiresAt", JSONObject.NULL)
                    .put("screenAwakeUntilTerminal", false);
            task.getJSONArray("notes").put(new JSONObject()
                    .put("at", at)
                    .put("text", "Auto-expired: no task update or Agent activity for "
                            + (TASK_IDLE_EXPIRY_MS / 60_000L) + " minutes"));
            HomePulse.task(task);
            changed = true;
        }
        return changed;
    }

    private void expireAndNotify(long nowMs) throws JSONException {
        if (expireIdle(nowMs)) awakeTasksChanged.accept(awakeTaskCount());
    }

    private static long lastActivityMs(JSONObject task) {
        long last = 0L;
        for (String key : new String[] {"updatedAt", "lastActivityAt"}) {
            try {
                last = Math.max(last, Instant.parse(task.optString(key, "")).toEpochMilli());
            } catch (Exception ignored) {
            }
        }
        return last;
    }

    synchronized JSONObject info() throws JSONException {
        long now = System.currentTimeMillis();
        expireAndNotify(now);
        int active = 0;
        int stale = 0;
        for (JSONObject task : tasks.values()) {
            String status = task.optString("status", "");
            if (holdsProjectionLease(task, now)) {
                active++;
            } else if (!isTerminal(status)) {
                stale++;
            }
        }
        return new JSONObject()
                .put("product", "PickPico")
                .put("role", "mobile_agent_node")
                .put("taskLifecycle", true)
                .put("taskStates", new JSONArray(VALID_STATES))
                .put("activeProjectionLeaseMs", ACTIVE_PROJECTION_LEASE_MS)
                .put("taskIdleExpiryMs", TASK_IDLE_EXPIRY_MS)
                .put("retainedTasks", tasks.size())
                .put("activeTasks", active)
                .put("screenAwakeTasks", awakeTaskCount())
                .put("screenAwakePolicy", "Open task keeps the display on for 3 minutes after the last screen-driving call (ui.*, screen.capture, app.launch, url.open); background calls never wake it")
                .put("staleTasks", stale);
    }

    synchronized JSONObject create(JSONObject arguments) throws JSONException {
        expireAndNotify(System.currentTimeMillis());
        String objective = requireText(arguments, "objective", 1, 4096);
        String title = optionalText(arguments, "title", 160);
        String agent = AgentIdentity.require(arguments);

        String taskId = "task-" + UUID.randomUUID();
        String now = Instant.now().toString();
        JSONObject task = new JSONObject()
                .put("taskId", taskId)
                .put("title", title.isEmpty() ? JSONObject.NULL : title)
                .put("objective", objective)
                .put("agent", agent)
                .put("status", "created")
                .put("createdAt", now)
                .put("updatedAt", now)
                .put("leaseExpiresAt", Instant.now().plusMillis(ACTIVE_PROJECTION_LEASE_MS).toString())
                .put("notes", new JSONArray());

        JSONObject context = arguments.optJSONObject("context");
        if (context != null) {
            task.put("context", new JSONObject(context.toString()));
        }

        if (awakeTaskCount() >= MAX_TASKS)
            throw new CommandRuntime.CommandInputException("Finish or cancel an existing task before creating another");
        task.put("screenAwakeUntilTerminal", true).put("screenAwakeIdleTimeoutMs", AgentScreenLease.IDLE_MS);
        tasks.put(taskId, task);
        HomePulse.task(task);
        trim();
        awakeTasksChanged.accept(awakeTaskCount());
        return copy(task);
    }

    synchronized JSONObject update(JSONObject arguments) throws JSONException {
        expireAndNotify(System.currentTimeMillis());
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
                task.put("leaseExpiresAt", JSONObject.NULL);
            }
        }

        String note = optionalText(arguments, "note", 4096);
        if (!note.isEmpty()) {
            task.getJSONArray("notes").put(new JSONObject()
                    .put("at", Instant.now().toString())
                    .put("text", note));
        }

        Instant now = Instant.now();
        task.put("updatedAt", now.toString());
        if (!isTerminal(task.optString("status", ""))) {
            task.put("leaseExpiresAt", now.plusMillis(ACTIVE_PROJECTION_LEASE_MS).toString());
        }
        HomePulse.task(task);
        task.put("screenAwakeUntilTerminal", !isTerminal(task.optString("status")));
        awakeTasksChanged.accept(awakeTaskCount());
        return copy(task);
    }

    synchronized JSONObject status(JSONObject arguments) throws JSONException {
        expireAndNotify(System.currentTimeMillis());
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
            JSONObject summary = new JSONObject();
            JSONObject task = entry.getValue();
            for (String key : new String[]{"taskId", "objective", "agent", "status", "createdAt", "updatedAt", "step", "progress", "blocker"})
                if (task.has(key)) summary.put(key, task.opt(key));
            recent.put(summary);
        }
        return new JSONObject().put("tasks", recent).put("count", recent.length());
    }

    private void trim() {
        while (tasks.size() > MAX_TASKS) {
            String oldest = null;
            for (Map.Entry<String, JSONObject> entry : tasks.entrySet())
                if (isTerminal(entry.getValue().optString("status"))) { oldest = entry.getKey(); break; }
            if (oldest == null) break;
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
        return "completed".equals(state) || "failed".equals(state)
                || "cancelled".equals(state) || "expired".equals(state);
    }

    /**
     * Non-terminal task state is a presentation lease, not an eternal truth.
     * If an Agent disappears without a terminal update, the task remains in history
     * but stops controlling Home/Pico presence after the lease expires.
     */
    static boolean holdsProjectionLease(JSONObject task, long now) {
        if (task == null || isTerminal(task.optString("status", ""))) return false;
        long updatedAt = lastActivityMs(task);
        if (updatedAt <= 0L) return false;
        long age = Math.max(0L, now - updatedAt);
        return age <= ACTIVE_PROJECTION_LEASE_MS;
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
