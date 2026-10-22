package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.time.Instant;

/** Process-local, bounded observation of real work. Never infers execution from inbox history. */
final class HomePulse {
    private static final LinkedHashMap<Long, String> commands = new LinkedHashMap<>();
    private static final LinkedHashMap<Long, String> commandAgents = new LinkedHashMap<>();
    private static final LinkedHashMap<String, JSONObject> tasks = new LinkedHashMap<>();
    private static final LinkedHashSet<String> capabilityIds = new LinkedHashSet<>();
    private static final LinkedHashMap<Long, JSONObject> events = new LinkedHashMap<>();
    static synchronized void registerCapability(String id) { capabilityIds.add(id); }
    static synchronized String[] capabilities() { return capabilityIds.toArray(new String[0]); }
    private static long sequence;
    private static String previous = "", last = "";
    private static String lastAgent = "";
    private static long lastFinished;
    private static boolean lastFailed;

    static synchronized long begin(String command) {
        return begin(command, "");
    }

    static synchronized long begin(String command, String agent) {
        long id = ++sequence;
        commands.put(id, command);
        commandAgents.put(id, agent == null ? "" : agent);
        return id;
    }

    static synchronized void finish(long id, boolean failed) {
        String command = commands.remove(id);
        if (command == null) return;
        String agent = commandAgents.remove(id);
        lastAgent = agent == null ? "" : agent;
        previous = last;
        last = command;
        lastFailed = failed;
        lastFinished = System.currentTimeMillis();
        try {
            events.put(id, new JSONObject().put("command", command).put("failed", failed).put("agent", lastAgent)
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
        commandAgents.clear(); lastAgent = "";
    }

    static synchronized Snapshot snapshot(boolean node, boolean configured, String relay, JSONObject pending, long now) {
        return snapshot(null, node, configured, relay, pending, now);
    }

    static synchronized Snapshot snapshot(android.content.Context context, boolean node, boolean configured, String relay, JSONObject pending, long now) {
        Snapshot s = new Snapshot();
        s.label = copy(context, R.string.ui_ready_fc554d, "READY");
        s.title = copy(context, R.string.ui_no_active_agent_tasks, "No active agent tasks");
        JSONObject active = null;
        JSONObject recentTask = null;
        long recentTaskAgeMs = Long.MAX_VALUE;
        String latestTaskUpdate = "";
        int activeTasks = 0;
        for (JSONObject task : tasks.values()) {
            String status = task.optString("status");
            String taskUpdatedAt = task.optString("updatedAt", "");
            if (taskUpdatedAt.compareTo(latestTaskUpdate) > 0) latestTaskUpdate = taskUpdatedAt;
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
            } else if (!AgentTaskRuntime.holdsProjectionLease(task, now)) {
                continue;
            }
            activeTasks++;
            if (active == null || task.optString("updatedAt").compareTo(active.optString("updatedAt")) > 0) active = task;
        }
        s.activeTasks = activeTasks;
        // A command carries its own model identity, never borrowed from a concurrent task.
        String current = "";
        String currentAgent = "";
        for (Long id : commands.keySet()) {
            current = commands.get(id);
            currentAgent = commandAgents.get(id);
        }
        s.agent = active == null || activeTasks > 1 ? "Agent" : value(active, "agent", "Agent");
        s.identifiedWork = !current.isEmpty() && !currentAgent.isEmpty();
        if (!current.isEmpty() && !currentAgent.isEmpty()) s.agent = currentAgent;
        s.flow = !current.isEmpty() ? current : (now - lastFinished < 12000 && !last.isEmpty() ? copy(context, R.string.ui_last, "Last · ") + last : copy(context, R.string.ui_waiting_for_an_agent_task, "Waiting for an agent task"));
        s.recent = current.isEmpty() && now - lastFinished < 12000 && !last.isEmpty();
        if (current.isEmpty() && active == null && now - lastFinished < 15000 && !lastAgent.isEmpty()) {
            s.agent = lastAgent;
        }
        if (s.recent && !previous.isEmpty()) s.flow = copy(context, R.string.ui_recent_94045a, "Recent · ") + previous + " → " + last;
        s.connection = !node ? copy(context, R.string.ui_node_is_stopped, "Node is stopped") : !configured ? copy(context, R.string.ui_local_connection, "Local connection") : relay.equals("connected") ? copy(context, R.string.ui_connected_through_relay, "Connected through Relay") : copy(context, R.string.ui_remote_access_unavailable, "Remote access unavailable");
        if (pending != null) {
            s.mode = "waiting"; s.label = copy(context, R.string.ui_waiting_c271b6, "WAITING"); s.title = copy(context, R.string.ui_waiting_for_your_help, "Waiting for your help");
            String activeAgent = active == null || activeTasks > 1 ? "Agent" : value(active, "agent", "Agent");
            s.agent = value(pending, "agent", activeAgent); s.flow = "human.help";
            s.identifiedWork = !value(pending, "agent", "").isEmpty();
            s.action = "help";
            s.actionTitle = pending.optString("requestType").equals("approval") ? copy(context, R.string.ui_approval_required, "Approval required") : copy(context, R.string.ui_your_help_is_needed, "Your help is needed");
            s.actionDetail = value(pending, "title", copy(context, R.string.ui_an_agent_is_waiting_for_your_response, "An agent is waiting for your response."));
            s.button = pending.optString("requestType").equals("approval") ? copy(context, R.string.ui_review, "Review") : copy(context, R.string.ui_respond, "Respond");
        } else if (!node) {
            s.mode = "blocked"; s.label = copy(context, R.string.ui_offline_c68993, "OFFLINE"); s.title = copy(context, R.string.ui_pickpico_is_resting, "PickPico is resting");
            s.agent = "PickPico"; s.flow = copy(context, R.string.ui_start_the_node_to_welcome_agents, "Start the node to welcome agents");
            s.action = "start"; s.actionTitle = copy(context, R.string.ui_ready_when_you_are, "Ready when you are"); s.actionDetail = copy(context, R.string.ui_start_pickpico_to_make_this_phone_available, "Start PickPico to make this phone available."); s.button = copy(context, R.string.ui_start_952f37, "Start");
        } else if (active != null && (active.optString("status").equals("blocked") || active.optString("status").equals("failed"))) {
            s.mode = "blocked"; s.label = copy(context, R.string.ui_blocked_fcc4cc, "BLOCKED"); s.title = copy(context, R.string.ui_task_needs_attention, "Task needs attention");
            s.flow = value(active, "title", value(active, "objective", copy(context, R.string.ui_task_blocked, "Task blocked")));
            s.action = "task"; s.actionTitle = copy(context, R.string.ui_task_blocked, "Task blocked"); s.actionDetail = s.flow; s.button = copy(context, R.string.ui_review_issue, "Review issue");
        } else if (!current.isEmpty() || active != null) {
            boolean waiting = active != null && active.optString("status").equals("waiting_human");
            s.mode = waiting ? "waiting" : "running"; s.label = waiting ? copy(context, R.string.ui_waiting_c271b6, "WAITING") : copy(context, R.string.ui_active, "ACTIVE");
            s.title = waiting ? copy(context, R.string.ui_waiting_for_your_help, "Waiting for your help") : !current.isEmpty() ? commandTitle(context, current) : value(active, "title", value(active, "objective", copy(context, R.string.ui_agent_task_in_progress, "Agent task in progress")));
            if (active != null && current.isEmpty() && !s.recent) s.flow = copy(context, R.string.ui_waiting_for_the_agent_s_next_step, "Waiting for the agent’s next step");
            if (waiting) { s.action = "task"; s.actionTitle = copy(context, R.string.ui_waiting_on_a_task, "Waiting on a task"); s.actionDetail = copy(context, R.string.ui_review_the_task_for_the_next_step, "Review the task for the next step."); s.button = copy(context, R.string.ui_open_task_a1f175, "Open task"); }
            else { s.action = "task"; s.actionTitle = copy(context, R.string.ui_open_current_task, "Open current task"); s.actionDetail = copy(context, R.string.ui_view_details_and_live_progress, "View details and live progress"); s.button = copy(context, R.string.ui_open_task_a1f175, "Open task"); }
        } else if (configured && !relay.equals("connected")) {
            boolean connecting = relay.equals("connecting") || relay.equals("starting") || relay.equals("reconnecting");
            s.mode = connecting ? "connecting" : "blocked"; s.label = connecting ? copy(context, R.string.ui_connecting_48b998, "CONNECTING") : copy(context, R.string.ui_blocked_fcc4cc, "BLOCKED");
            s.title = connecting ? copy(context, R.string.ui_connecting_pickpico, "Connecting PickPico") : copy(context, R.string.ui_relay_connection_lost, "Relay connection lost");
            s.agent = "PickPico"; s.flow = connecting ? copy(context, R.string.ui_establishing_a_relay_session, "Establishing a Relay session") : copy(context, R.string.ui_local_access_is_still_available, "Local access is still available");
            if (!connecting) { s.action = "connection"; s.actionTitle = copy(context, R.string.ui_relay_disconnected, "Relay disconnected"); s.actionDetail = copy(context, R.string.ui_remote_access_needs_attention, "Remote access needs attention."); s.button = copy(context, R.string.ui_review_connection, "Review connection"); }
        } else if (lastFailed && now - lastFinished < 15000) {
            s.mode = "blocked"; s.label = copy(context, R.string.ui_blocked_fcc4cc, "BLOCKED"); s.title = copy(context, R.string.ui_an_action_needs_attention, "An action needs attention");
            s.flow = last + copy(context, R.string.ui_failed_f8d53c, " → failed"); s.action = "task"; s.actionTitle = copy(context, R.string.ui_action_did_not_finish, "Action did not finish");
            s.actionDetail = copy(context, R.string.ui_check_activity_for_the_latest_context, "Check Activity for the latest context."); s.button = copy(context, R.string.ui_review_issue, "Review issue");
        } else if (recentTask != null) {
            s.mode = "idle"; s.label = copy(context, R.string.ui_ready_fc554d, "READY");
            s.title = value(recentTask, "title", value(recentTask, "objective", copy(context, R.string.ui_task_completed, "Task completed")));
            s.agent = value(recentTask, "agent", "Agent");
            s.flow = copy(context, R.string.ui_task_completed, "Task completed");
            s.recent = true;
        }
        if (s.mode.equals("idle") && !s.recent) s.agent = "PickPico";
        s.agentState = s.agent + " · " + (s.mode.equals("running") ? copy(context, R.string.ui_running, "Running") : s.mode.equals("waiting") ? copy(context, R.string.ui_waiting, "Waiting") : s.mode.equals("connecting") ? copy(context, R.string.ui_starting, "Starting") : s.mode.equals("blocked") ? copy(context, R.string.ui_blocked, "Blocked") : copy(context, R.string.ui_ready_20c7c5, "Ready"));
        if (node && pending == null && activeTasks > 1 && !s.identifiedWork) s.agentState = activeTasks + copy(context, R.string.ui_agent_tasks, " agent tasks · ") + (s.mode.equals("blocked") ? copy(context, R.string.ui_blocked, "Blocked") : s.mode.equals("waiting") ? copy(context, R.string.ui_waiting, "Waiting") : copy(context, R.string.ui_active_a733b8, "Active"));
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
        s.wakeKey = sequence + "|" + latestTaskUpdate + "|" + s.pendingRequestId;
        // Standalone commands and help requests are not associated with a task.
        // Never inherit another concurrent task's return destination.
        if (node && pending == null && current.isEmpty() && activeTasks <= 1) {
            s.caller = CallerReturnTarget.fromTask(active != null ? active : recentTask);
        }
        return s;
    }

    private static String value(JSONObject object, String key, String fallback) {
        if (object == null || object.isNull(key)) return fallback;
        String value = object.optString(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    static String commandTitle(String id) { return commandTitle(null, id); }

    static String commandTitle(android.content.Context context, String id) {
        if (id.equals("camera.capture")) return copy(context, R.string.ui_checking_the_camera_feed, "Checking the camera feed");
        if (id.equals("screen.capture") || id.equals("ui.inspect")) return copy(context, R.string.ui_reviewing_the_current_screen, "Reviewing the current screen");
        if (id.equals("app.launch")) return copy(context, R.string.ui_opening_an_app, "Opening an app");
        if (id.equals("app.list")) return copy(context, R.string.ui_finding_an_app, "Finding an app");
        if (id.equals("ui.type")) return copy(context, R.string.command_typing, "Filling in the details");
        if (id.startsWith("ui.")) return copy(context, R.string.command_ui, "Working on the current screen");
        if (id.equals("human.help")) return copy(context, R.string.ui_waiting_for_your_help, "Waiting for your help");
        if (id.equals("microphone.record")) return copy(context, R.string.command_listen, "Listening to the surroundings");
        if (id.equals("notification.reply")) return copy(context, R.string.command_reply, "Replying to a notification");
        if (id.startsWith("notification.")) return copy(context, R.string.ui_checking_notifications, "Checking notifications");
        if (id.startsWith("process.")) return copy(context, R.string.ui_working_on_the_device, "Working on the device");
        if (id.startsWith("workspace.")) return copy(context, R.string.ui_working_with_files, "Working with files");
        if (id.equals("guide.get")) return copy(context, R.string.ui_preparing_the_next_steps, "Preparing the next steps");
        if (id.startsWith("app.update")) return copy(context, R.string.ui_checking_the_device_update, "Checking the device update");
        return copy(context, R.string.ui_helping_with_an_agent_task, "Helping with an agent task");
    }

    static String eventTitle(String id) { return eventTitle(null, id); }

    static String eventTitle(android.content.Context context, String id) {
        switch (id) {
            case "camera.capture": return copy(context, R.string.ui_captured_camera, "Captured camera");
            case "screen.capture": return copy(context, R.string.ui_captured_screen, "Captured screen");
            case "ui.inspect": return copy(context, R.string.ui_reviewed_current_screen, "Reviewed current screen");
            case "app.launch": return copy(context, R.string.ui_app_launched, "App launched");
            case "app.list": return copy(context, R.string.ui_found_installed_apps, "Found installed apps");
            case "human.help": return copy(context, R.string.ui_human_help_finished, "Human Help finished");
            case "ui.type": return copy(context, R.string.ui_entered_text, "Entered text");
            case "ui.action": return copy(context, R.string.ui_interacted_with_screen, "Interacted with screen");
            case "ui.scroll": return copy(context, R.string.ui_scrolled_current_screen, "Scrolled current screen");
            case "notification.reply": return copy(context, R.string.ui_replied_to_notification, "Replied to notification");
            case "guide.get": return copy(context, R.string.ui_read_operation_guide, "Read operation guide");
            default: return id;
        }
    }

    private static String copy(android.content.Context context, int id, String fallback) {
        return context == null ? fallback : context.getString(id);
    }

    static final class Snapshot {
        String mode = "idle", label = "READY", title = "No active agent tasks";
        String orbMode = PicoOrbState.READY;
        String pendingRequestId = "";
        String wakeKey = "";
        CallerReturnTarget caller = CallerReturnTarget.unknown();
        String agent, agentState, flow, connection;
        String action = "", actionTitle = "", actionDetail = "", button = "";
        int activeTasks;
        boolean recent;
        boolean identifiedWork;
        String agentLabel() { return activeTasks > 1 && !identifiedWork ? activeTasks + " agents" : agent; }
    }
}
