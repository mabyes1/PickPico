package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.time.Instant;

/** Process-local, bounded observation of real work. Never infers execution from inbox history. */
final class HomePulse {
    private static final LinkedHashMap<Long, String> commands = new LinkedHashMap<>();
    private static final LinkedHashMap<String, JSONObject> tasks = new LinkedHashMap<>();
    private static final LinkedHashSet<String> capabilityIds = new LinkedHashSet<>();
    private static final LinkedHashMap<Long, JSONObject> events = new LinkedHashMap<>();
    static synchronized void registerCapability(String id) { capabilityIds.add(id); }
    static synchronized String[] capabilities() { return capabilityIds.toArray(new String[0]); }
    private static long sequence;
    private static String previous = "", last = "";
    private static long lastFinished;
    private static boolean lastFailed;

    static synchronized long begin(String command) {
        long id = ++sequence;
        commands.put(id, command);
        return id;
    }

    static synchronized void finish(long id, boolean failed) {
        String command = commands.remove(id);
        if (command == null) return;
        previous = last;
        last = command;
        lastFailed = failed;
        lastFinished = System.currentTimeMillis();
        try {
            events.put(id, new JSONObject().put("command", command).put("failed", failed)
                    .put("at", Instant.ofEpochMilli(lastFinished).toString()));
            while (events.size() > 50) events.remove(events.keySet().iterator().next());
        } catch (Exception ignored) { }
    }

    static synchronized JSONArray commandHistory() {
        JSONArray result = new JSONArray();
        for (JSONObject event : events.values()) {
            try { result.put(new JSONObject(event.toString())); } catch (Exception ignored) { }
        }
        return result;
    }

    static synchronized void task(JSONObject value) {
        try {
            tasks.put(value.getString("taskId"), new JSONObject(value.toString()));
            while (tasks.size() > 50) tasks.remove(tasks.keySet().iterator().next());
        } catch (Exception ignored) { }
    }

    static synchronized JSONArray taskHistory() {
        JSONArray result = new JSONArray();
        for (JSONObject task : tasks.values()) {
            try { result.put(new JSONObject(task.toString())); } catch (Exception ignored) { }
        }
        return result;
    }

    static synchronized void reset() {
        commands.clear(); tasks.clear(); events.clear(); previous = ""; last = ""; lastFinished = 0; lastFailed = false;
    }

    static synchronized Snapshot snapshot(boolean node, boolean configured, String relay, JSONObject pending, long now) {
        Snapshot s = new Snapshot();
        JSONObject active = null;
        JSONObject recentTask = null;
        long recentTaskAgeMs = Long.MAX_VALUE;
        int activeTasks = 0;
        for (JSONObject task : tasks.values()) {
            String status = task.optString("status");
            if (status.equals("completed") || status.equals("cancelled")) {
                try {
                    long age = now - Instant.parse(task.optString("updatedAt")).toEpochMilli();
                    if (age <= 30000
                            && (recentTask == null || task.optString("updatedAt").compareTo(recentTask.optString("updatedAt")) > 0)) {
                        recentTask = task;
                        recentTaskAgeMs = age;
                    }
                } catch (Exception ignored) { }
                continue;
            }
            if (status.equals("failed")) {
                try { if (now - Instant.parse(task.optString("updatedAt")).toEpochMilli() > 15000) continue; }
                catch (Exception ignored) { continue; }
            }
            activeTasks++;
            if (active == null || task.optString("updatedAt").compareTo(active.optString("updatedAt")) > 0) active = task;
        }
        s.activeTasks = activeTasks;
        // Commands have no task/agent association in the protocol. Do not invent one.
        String current = "";
        for (String command : commands.values()) current = command;
        s.agent = active == null || activeTasks > 1 ? "Agent" : value(active, "agent", "Agent");
        s.flow = !current.isEmpty() ? current : (now - lastFinished < 12000 && !last.isEmpty() ? "Last · " + last : "Waiting for an agent task");
        s.recent = current.isEmpty() && now - lastFinished < 12000 && !last.isEmpty();
        if (s.recent && !previous.isEmpty()) s.flow = "Recent · " + previous + " → " + last;
        s.connection = !node ? "Node is stopped" : !configured ? "Local connection" : relay.equals("connected") ? "Connected through Relay" : "Remote access unavailable";
        if (pending != null) {
            s.mode = "waiting"; s.label = "WAITING"; s.title = "Waiting for your help";
            String activeAgent = active == null || activeTasks > 1 ? "Agent" : value(active, "agent", "Agent");
            s.agent = value(pending, "agent", activeAgent); s.flow = "human.help";
            s.action = "help";
            s.actionTitle = pending.optString("requestType").equals("approval") ? "Approval required" : "Your help is needed";
            s.actionDetail = value(pending, "title", "An agent is waiting for your response.");
            s.button = pending.optString("requestType").equals("approval") ? "Review" : "Respond";
        } else if (!node) {
            s.mode = "blocked"; s.label = "OFFLINE"; s.title = "PickPico is resting";
            s.agent = "PickPico"; s.flow = "Start the node to welcome agents";
            s.action = "start"; s.actionTitle = "Ready when you are"; s.actionDetail = "Start PickPico to make this phone available."; s.button = "Start";
        } else if (active != null && (active.optString("status").equals("blocked") || active.optString("status").equals("failed"))) {
            s.mode = "blocked"; s.label = "BLOCKED"; s.title = "Task needs attention";
            s.flow = value(active, "title", value(active, "objective", "Task blocked"));
            s.action = "task"; s.actionTitle = "Task blocked"; s.actionDetail = s.flow; s.button = "Review issue";
        } else if (!current.isEmpty() || active != null) {
            boolean waiting = active != null && active.optString("status").equals("waiting_human");
            s.mode = waiting ? "waiting" : "running"; s.label = waiting ? "WAITING" : "ACTIVE";
            s.title = waiting ? "Waiting for your help" : !current.isEmpty() ? commandTitle(current) : value(active, "title", value(active, "objective", "Agent task in progress"));
            if (active != null && current.isEmpty() && !s.recent) s.flow = "Waiting for the agent’s next step";
            if (waiting) { s.action = "task"; s.actionTitle = "Waiting on a task"; s.actionDetail = "Review the task for the next step."; s.button = "Open task"; }
            else { s.action = "task"; s.actionTitle = "Open current task"; s.actionDetail = "View details and live progress"; s.button = "Open task"; }
        } else if (configured && !relay.equals("connected")) {
            boolean connecting = relay.equals("connecting") || relay.equals("starting") || relay.equals("reconnecting");
            s.mode = connecting ? "connecting" : "blocked"; s.label = connecting ? "CONNECTING" : "BLOCKED";
            s.title = connecting ? "Connecting PickPico" : "Relay connection lost";
            s.agent = "PickPico"; s.flow = connecting ? "Establishing a Relay session" : "Local access is still available";
            if (!connecting) { s.action = "connection"; s.actionTitle = "Relay disconnected"; s.actionDetail = "Remote access needs attention."; s.button = "Review connection"; }
        } else if (lastFailed && now - lastFinished < 15000) {
            s.mode = "blocked"; s.label = "BLOCKED"; s.title = "An action needs attention";
            s.flow = last + " → failed"; s.action = "task"; s.actionTitle = "Action did not finish";
            s.actionDetail = "Check Activity for the latest context."; s.button = "Review issue";
        } else if (recentTask != null) {
            s.mode = "idle"; s.label = "READY";
            s.title = value(recentTask, "title", value(recentTask, "objective", "Task completed"));
            s.agent = value(recentTask, "agent", "Agent");
            s.flow = "Task completed";
            s.recent = true;
        }
        if (s.mode.equals("idle") && !s.recent) s.agent = "PickPico";
        s.agentState = s.agent + " · " + (s.mode.equals("running") ? "Running" : s.mode.equals("waiting") ? "Waiting" : s.mode.equals("connecting") ? "Starting" : s.mode.equals("blocked") ? "Blocked" : "Ready");
        if (node && pending == null && activeTasks > 1) s.agentState = activeTasks + " agent tasks · " + (s.mode.equals("blocked") ? "Blocked" : s.mode.equals("waiting") ? "Waiting" : "Active");
        boolean activeBlocked = active != null && (active.optString("status").equals("blocked")
                || active.optString("status").equals("failed"));
        boolean activeWaiting = active != null && active.optString("status").equals("waiting_human");
        boolean recentFailure = lastFailed && now - lastFinished < 15000;
        boolean connecting = configured && (relay.equals("connecting") || relay.equals("starting") || relay.equals("reconnecting"));
        boolean completedRecently = (!lastFailed && lastFinished > 0 && now - lastFinished <= PicoOrbState.COMPLETED_VISIBLE_MS)
                || (recentTask != null
                && recentTask.optString("status").equals("completed")
                && recentTaskAgeMs <= PicoOrbState.COMPLETED_VISIBLE_MS);
        boolean connectionAttention = configured && !relay.equals("connected") && !connecting;
        s.orbMode = PicoOrbState.resolve(
                node,
                pending != null || activeWaiting,
                activeBlocked || recentFailure,
                !current.isEmpty() || (active != null && !activeWaiting && !activeBlocked),
                connecting,
                completedRecently,
                connectionAttention);
        s.pendingRequestId = pending == null ? "" : pending.optString("requestId", "");
        return s;
    }

    private static String value(JSONObject object, String key, String fallback) {
        if (object == null || object.isNull(key)) return fallback;
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    static String commandTitle(String id) {
        if (id.equals("camera.capture")) return "Checking the camera feed";
        if (id.equals("screen.capture") || id.equals("ui.inspect")) return "Reviewing the current screen";
        if (id.equals("app.launch")) return "Opening an app";
        if (id.equals("app.list")) return "Finding an app";
        if (id.equals("ui.type")) return "Filling in the details";
        if (id.startsWith("ui.")) return "Working on the current screen";
        if (id.equals("human.help")) return "Waiting for your help";
        if (id.equals("microphone.record")) return "Listening to the surroundings";
        if (id.equals("notification.reply")) return "Replying to a notification";
        if (id.startsWith("notification.")) return "Checking notifications";
        if (id.startsWith("process.")) return "Working on the device";
        if (id.startsWith("workspace.")) return "Working with files";
        if (id.equals("guide.get")) return "Preparing the next steps";
        if (id.startsWith("app.update")) return "Checking the device update";
        return "Helping with an agent task";
    }

    static String eventTitle(String id) {
        switch (id) {
            case "camera.capture": return "Captured camera";
            case "screen.capture": return "Captured screen";
            case "ui.inspect": return "Reviewed current screen";
            case "app.launch": return "App launched";
            case "app.list": return "Found installed apps";
            case "human.help": return "Human Help finished";
            case "ui.type": return "Entered text";
            case "ui.action": return "Interacted with screen";
            case "ui.scroll": return "Scrolled current screen";
            case "notification.reply": return "Replied to notification";
            case "guide.get": return "Read operation guide";
            default: return id;
        }
    }

    static final class Snapshot {
        String mode = "idle", label = "READY", title = "No active agent tasks";
        String orbMode = PicoOrbState.READY;
        String pendingRequestId = "";
        String agent, agentState, flow, connection;
        String action = "", actionTitle = "", actionDetail = "", button = "";
        int activeTasks;
        boolean recent;
    }
}
