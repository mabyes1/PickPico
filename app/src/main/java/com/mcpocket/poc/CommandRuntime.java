package com.mcpocket.poc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
    private final McpToolActions actions;

    CommandRuntime(McpToolActions actions) throws JSONException {
        this.actions = actions;
        register(
                "node.info",
                "Return PickPico node and Android device information.",
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
                "capability.list",
                "List all implemented PickPico capabilities, including disabled/setup-required capabilities and their runtime state.",
                "capability",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> capabilityList(callCount));

        register(
                "capability.status",
                "Return runtime availability, capability group, and setup requirements for one PickPico capability.",
                "capability",
                "read_only",
                false,
                capabilityStatusSchema(),
                (arguments, callCount) -> capabilityStatus(arguments.optString("id", ""), callCount));

        register(
                "policy.status",
                "Return the user-owned Hyper Mode and Agent approval policy. Policy changes are local UI actions, not remotely writable MCP commands.",
                "policy",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.policyStatus(callCount));

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
                "Immediately lock the phone when PickPico has been explicitly enabled as a device administrator.",
                "phone",
                "security_action",
                true,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.phoneLock(callCount));

        register(
                "phone.wake",
                "Turn on the phone display without dismissing the lock screen.",
                "phone",
                "physical_action",
                true,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.phoneWake(callCount));

        register(
                "camera.capture",
                "Capture one JPEG frame from the Android camera and persist it in the PickPico workspace.",
                "sensor",
                "sensor_read",
                true,
                cameraCaptureSchema(),
                (arguments, callCount) -> {
                    String lens = arguments.optString("lens", "back");
                    if (!"back".equals(lens) && !"front".equals(lens) && !"any".equals(lens)) {
                        throw new CommandInputException("camera.capture lens must be back, front, or any");
                    }
                    int maxWidth = arguments.optInt("maxWidth", 1280);
                    int maxHeight = arguments.optInt("maxHeight", 1280);
                    int quality = arguments.optInt("quality", 85);
                    if (maxWidth < 320 || maxWidth > 4096 || maxHeight < 320 || maxHeight > 4096) {
                        throw new CommandInputException("camera.capture maxWidth/maxHeight must be between 320 and 4096");
                    }
                    if (quality < 50 || quality > 100) {
                        throw new CommandInputException("camera.capture quality must be between 50 and 100");
                    }
                    return actions.cameraCapture(arguments, callCount);
                });

        register(
                "phone.notify",
                "Post a user-visible Android notification from the Agent.",
                "interaction",
                "physical_action",
                true,
                phoneNotifySchema(),
                (arguments, callCount) -> {
                    String title = arguments.optString("title", "PickPico Agent");
                    String body = arguments.optString("body", "");
                    if (title.isEmpty() || title.length() > 120) {
                        throw new CommandInputException("phone.notify title must be 1-120 characters");
                    }
                    if (body.isEmpty() || body.length() > 2000) {
                        throw new CommandInputException("phone.notify body must be 1-2000 characters");
                    }
                    return actions.phoneNotify(arguments, callCount);
                });

        register(
                "phone.speak",
                "Speak text through Android TextToSpeech. PickPico inspects media volume first and can raise very low volume to an audible level while respecting silent/DND state.",
                "interaction",
                "physical_action",
                true,
                phoneSpeakSchema(),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    if (text.isEmpty() || text.length() > 2000) {
                        throw new CommandInputException("phone.speak text must be 1-2000 characters");
                    }
                    double rate = arguments.optDouble("rate", 1.0);
                    double pitch = arguments.optDouble("pitch", 1.0);
                    if (rate < 0.5 || rate > 2.0 || pitch < 0.5 || pitch > 2.0) {
                        throw new CommandInputException("phone.speak rate and pitch must be between 0.5 and 2.0");
                    }
                    String queue = arguments.optString("queue", "flush");
                    if (!"flush".equals(queue) && !"add".equals(queue)) {
                        throw new CommandInputException("phone.speak queue must be flush or add");
                    }
                    return actions.phoneSpeak(arguments, callCount);
                });

        register(
                "audio.status",
                "Inspect Android media, notification, ringtone, and alarm volume separately, plus ringer mode and Do Not Disturb state.",
                "audio",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.audioStatus(callCount));

        register(
                "audio.set",
                "Set one Android audio stream by percentage without changing unrelated streams or Do Not Disturb state.",
                "audio",
                "physical_action",
                true,
                audioSetSchema(),
                (arguments, callCount) -> {
                    String stream = arguments.optString("stream", "");
                    if (!"media".equals(stream)
                            && !"notification".equals(stream)
                            && !"ring".equals(stream)
                            && !"alarm".equals(stream)) {
                        throw new CommandInputException(
                                "audio.set stream must be media, notification, ring, or alarm");
                    }
                    int percent = arguments.optInt("percent", -1);
                    if (percent < 0 || percent > 100) {
                        throw new CommandInputException("audio.set percent must be between 0 and 100");
                    }
                    return actions.audioSet(arguments, callCount);
                });

        register(
                "microphone.record",
                "Record mono 16 kHz WAV audio from the Android microphone and persist it in the MCPocket workspace.",
                "sensor",
                "sensor_read",
                true,
                microphoneRecordSchema(),
                (arguments, callCount) -> {
                    int durationMs = arguments.optInt("durationMs", 3000);
                    if (durationMs < 500 || durationMs > 10000) {
                        throw new CommandInputException("microphone.record durationMs must be between 500 and 10000");
                    }
                    return actions.microphoneRecord(arguments, callCount);
                });

        register(
                "human.help",
                "Ask the nearby human for help through PickPico and block until they respond or the renewable idle timeout expires. Human typing, image selection, or camera activity renews the idle timeout.",
                "interaction",
                "human_interaction",
                true,
                humanHelpSchema(),
                (arguments, callCount) -> {
                    String instruction = arguments.optString("instruction", "");
                    if (instruction.isEmpty() || instruction.length() > 4000) {
                        throw new CommandInputException("human.help instruction must be 1-4000 characters");
                    }
                    int idleTimeoutSeconds = arguments.optInt("idleTimeoutSeconds", 180);
                    if (idleTimeoutSeconds != 120 && idleTimeoutSeconds != 180 && idleTimeoutSeconds != 360) {
                        throw new CommandInputException("human.help idleTimeoutSeconds must be 120, 180, or 360");
                    }
                    int maxImages = arguments.optInt("maxImages", 3);
                    if (maxImages < 0 || maxImages > 3) {
                        throw new CommandInputException("human.help maxImages must be between 0 and 3");
                    }
                    JSONArray actionsList = arguments.optJSONArray("actions");
                    if (actionsList != null && actionsList.length() > 6) {
                        throw new CommandInputException("human.help supports at most 6 actions");
                    }
                    return actions.humanHelp(arguments, callCount);
                });

        register(
                "human.help.status",
                "Return the current human-help request state and, when completed, the human response and optional image attachments.",
                "interaction",
                "read_only",
                false,
                humanHelpStatusSchema(),
                (arguments, callCount) -> {
                    String requestId = arguments.optString("requestId", "");
                    if (requestId.isEmpty() || requestId.length() > 128) {
                        throw new CommandInputException("human.help.status requires a valid requestId");
                    }
                    return actions.humanHelpStatus(arguments, callCount);
                });

        register(
                "notification.list",
                "List currently active Android notifications visible to MCPocket's Notification Listener.",
                "notification",
                "personal_data_read",
                false,
                notificationListSchema(),
                (arguments, callCount) -> actions.notificationList(arguments, callCount));

        register(
                "notification.get",
                "Return one active Android notification by notification key.",
                "notification",
                "personal_data_read",
                false,
                notificationKeySchema(),
                (arguments, callCount) -> {
                    String key = arguments.optString("key", "");
                    if (key.isEmpty() || key.length() > 1024) {
                        throw new CommandInputException("notification.get requires a valid key");
                    }
                    return actions.notificationGet(arguments, callCount);
                });

        register(
                "notification.dismiss",
                "Dismiss one active Android notification by notification key.",
                "notification",
                "notification_write",
                true,
                notificationKeySchema(),
                (arguments, callCount) -> {
                    String key = arguments.optString("key", "");
                    if (key.isEmpty() || key.length() > 1024) {
                        throw new CommandInputException("notification.dismiss requires a valid key");
                    }
                    return actions.notificationDismiss(arguments, callCount);
                });

        register(
                "notification.actions",
                "List action buttons and RemoteInput reply capabilities exposed by one active Android notification.",
                "notification",
                "personal_data_read",
                false,
                notificationKeySchema(),
                (arguments, callCount) -> actions.notificationActions(arguments, callCount));

        register(
                "notification.invoke_action",
                "Invoke one action button exposed by an active Android notification.",
                "notification",
                "notification_write",
                true,
                notificationActionSchema(false),
                (arguments, callCount) -> {
                    int actionIndex = arguments.optInt("actionIndex", -1);
                    if (actionIndex < 0 || actionIndex > 32) {
                        throw new CommandInputException("notification.invoke_action actionIndex must be between 0 and 32");
                    }
                    return actions.notificationInvokeAction(arguments, callCount);
                });

        register(
                "notification.reply",
                "Reply through Android RemoteInput when an active notification exposes a compatible reply action.",
                "notification",
                "notification_write",
                true,
                notificationReplySchema(),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    if (text.isEmpty() || text.length() > 4000) {
                        throw new CommandInputException("notification.reply text must be 1-4000 characters");
                    }
                    if (arguments.has("actionIndex")) {
                        int actionIndex = arguments.optInt("actionIndex", -1);
                        if (actionIndex < 0 || actionIndex > 32) {
                            throw new CommandInputException("notification.reply actionIndex must be between 0 and 32");
                        }
                    }
                    return actions.notificationReply(arguments, callCount);
                });

        register(
                "ui.inspect",
                "Inspect the current Android accessibility UI tree and return semantic nodes with paths, text, IDs, bounds, and actions.",
                "ui",
                "personal_data_read",
                false,
                uiInspectSchema(),
                (arguments, callCount) -> actions.uiInspect(arguments, callCount));

        register(
                "ui.action",
                "Perform a semantic Accessibility action on the current Android UI, or a global back/home/recents action.",
                "ui",
                "ui_action",
                true,
                uiActionSchema(),
                (arguments, callCount) -> {
                    String action = arguments.optString("action", "");
                    if (!"click".equals(action)
                            && !"long_click".equals(action)
                            && !"focus".equals(action)
                            && !"accessibility_focus".equals(action)
                            && !"back".equals(action)
                            && !"home".equals(action)
                            && !"recents".equals(action)) {
                        throw new CommandInputException("ui.action has an unsupported action");
                    }
                    if (!"back".equals(action)
                            && !"home".equals(action)
                            && !"recents".equals(action)
                            && arguments.optJSONObject("selector") == null) {
                        throw new CommandInputException("ui.action requires selector for node actions");
                    }
                    return actions.uiAction(arguments, callCount);
                });

        register(
                "ui.type",
                "Set or append text in an editable Android accessibility node selected by path, view ID, text, or content description.",
                "ui",
                "ui_action",
                true,
                uiTypeSchema(),
                (arguments, callCount) -> {
                    if (arguments.optJSONObject("selector") == null) {
                        throw new CommandInputException("ui.type requires selector");
                    }
                    String text = arguments.optString("text", "");
                    if (text.length() > 8192) {
                        throw new CommandInputException("ui.type text is limited to 8192 characters");
                    }
                    return actions.uiType(arguments, callCount);
                });

        register(
                "ui.scroll",
                "Scroll an Android accessibility node forward/backward or by directional alias. When selector is omitted, use the first scrollable node.",
                "ui",
                "ui_action",
                true,
                uiScrollSchema(),
                (arguments, callCount) -> actions.uiScroll(arguments, callCount));

        register(
                "screen.capture",
                "Capture the current Android screen from a user-authorized MediaProjection Hyper session and persist it in the PickPico workspace.",
                "ui",
                "screen_read",
                false,
                screenCaptureSchema(),
                (arguments, callCount) -> {
                    int quality = arguments.optInt("quality", 82);
                    if (quality < 50 || quality > 100) {
                        throw new CommandInputException("screen.capture quality must be between 50 and 100");
                    }
                    return actions.screenCapture(arguments, callCount);
                });

        register(
                "app.list",
                "List launchable Android apps with labels and package names.",
                "app",
                "read_only",
                false,
                appListSchema(),
                (arguments, callCount) -> actions.appList(arguments, callCount));

        register(
                "app.launch",
                "Launch an installed Android app by package name.",
                "app",
                "physical_action",
                true,
                appLaunchSchema(),
                (arguments, callCount) -> {
                    String packageName = arguments.optString("packageName", "");
                    if (packageName.isEmpty() || packageName.length() > 255) {
                        throw new CommandInputException("app.launch requires a valid packageName");
                    }
                    return actions.appLaunch(arguments, callCount);
                });

        register(
                "url.open",
                "Open a web URL, Android deep link, geo URI, navigation URI, telephone URI, or another registered safe URI scheme.",
                "app",
                "physical_action",
                true,
                urlOpenSchema(),
                (arguments, callCount) -> {
                    String url = arguments.optString("url", "");
                    if (url.isEmpty() || url.length() > 4096) {
                        throw new CommandInputException("url.open url must be 1-4096 characters");
                    }
                    return actions.urlOpen(arguments, callCount);
                });

        register(
                "location.get",
                "Get the phone's current location with timestamp and accuracy. Returns setup guidance when location permission is unavailable.",
                "location",
                "sensitive_sensor_read",
                false,
                locationGetSchema(),
                (arguments, callCount) -> {
                    int timeoutMs = arguments.optInt("timeoutMs", 7000);
                    if (timeoutMs < 500 || timeoutMs > 15000) {
                        throw new CommandInputException("location.get timeoutMs must be between 500 and 15000");
                    }
                    return actions.locationGet(arguments, callCount);
                });

        register(
                "clipboard.get",
                "Read plain text from the Android clipboard when Android permits PickPico clipboard access.",
                "clipboard",
                "personal_data_read",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.clipboardGet(callCount));

        register(
                "clipboard.set",
                "Put plain text onto the Android clipboard.",
                "clipboard",
                "clipboard_write",
                true,
                clipboardSetSchema(),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    if (text.length() > 65536) {
                        throw new CommandInputException("clipboard.set text is limited to 65536 characters");
                    }
                    return actions.clipboardSet(arguments, callCount);
                });

        register(
                "contacts.search",
                "Search the owner's Android contacts by display name and return stable contact IDs.",
                "contacts",
                "personal_data_read",
                false,
                contactsSearchSchema(),
                (arguments, callCount) -> {
                    String query = arguments.optString("query", "");
                    if (query.length() > 200) {
                        throw new CommandInputException("contacts.search query is limited to 200 characters");
                    }
                    int limit = arguments.optInt("limit", 20);
                    if (limit < 1 || limit > 100) {
                        throw new CommandInputException("contacts.search limit must be between 1 and 100");
                    }
                    return actions.contactsSearch(arguments, callCount);
                });

        register(
                "contacts.get",
                "Read one Android contact including phone numbers and email addresses.",
                "contacts",
                "personal_data_read",
                false,
                contactGetSchema(),
                (arguments, callCount) -> {
                    validateNumericId(arguments.optString("id", ""), "contacts.get id");
                    return actions.contactsGet(arguments, callCount);
                });

        register(
                "calendar.list",
                "List Android calendar events in a time range and return available calendar IDs for future writes.",
                "calendar",
                "personal_data_read",
                false,
                calendarListSchema(),
                (arguments, callCount) -> {
                    long start = arguments.optLong("startEpochMs", System.currentTimeMillis());
                    long end = arguments.optLong("endEpochMs", start + 7L * 24L * 60L * 60L * 1000L);
                    if (end <= start) {
                        throw new CommandInputException("calendar.list endEpochMs must be after startEpochMs");
                    }
                    if (end - start > 370L * 24L * 60L * 60L * 1000L) {
                        throw new CommandInputException("calendar.list range is limited to 370 days");
                    }
                    int limit = arguments.optInt("limit", 50);
                    if (limit < 1 || limit > 200) {
                        throw new CommandInputException("calendar.list limit must be between 1 and 200");
                    }
                    return actions.calendarList(arguments, callCount);
                });

        register(
                "calendar.get",
                "Read one Android calendar event by event ID.",
                "calendar",
                "personal_data_read",
                false,
                calendarEventIdSchema(),
                (arguments, callCount) -> {
                    validateNumericId(arguments.optString("eventId", ""), "calendar.get eventId");
                    return actions.calendarGet(arguments, callCount);
                });

        register(
                "calendar.create",
                "Create an Android calendar event. The owner approval policy applies before the write.",
                "calendar",
                "calendar_write",
                true,
                calendarCreateSchema(),
                (arguments, callCount) -> {
                    validateCalendarTimes(arguments, true);
                    if (arguments.optString("title", "").trim().isEmpty()) {
                        throw new CommandInputException("calendar.create title must not be empty");
                    }
                    if (arguments.has("calendarId")) {
                        validateNumericId(arguments.optString("calendarId", ""), "calendar.create calendarId");
                    }
                    return actions.calendarCreate(arguments, callCount);
                });

        register(
                "calendar.update",
                "Update fields on one Android calendar event. The owner approval policy applies before the write.",
                "calendar",
                "calendar_write",
                true,
                calendarUpdateSchema(),
                (arguments, callCount) -> {
                    validateNumericId(arguments.optString("eventId", ""), "calendar.update eventId");
                    validateCalendarTimes(arguments, false);
                    return actions.calendarUpdate(arguments, callCount);
                });

        register(
                "calendar.delete",
                "Delete one Android calendar event. The owner approval policy applies before the write.",
                "calendar",
                "calendar_write",
                true,
                calendarEventIdSchema(),
                (arguments, callCount) -> {
                    validateNumericId(arguments.optString("eventId", ""), "calendar.delete eventId");
                    return actions.calendarDelete(arguments, callCount);
                });

        register(
                "file.pick",
                "Ask the owner to choose one or more files with Android's system document picker, then import the selections into the PickPico workspace.",
                "files",
                "human_interaction",
                false,
                filePickSchema(),
                (arguments, callCount) -> {
                    validatePickerArguments(arguments);
                    JSONArray mimeTypes = arguments.optJSONArray("mimeTypes");
                    if (mimeTypes != null && mimeTypes.length() > 16) {
                        throw new CommandInputException("file.pick supports at most 16 MIME types");
                    }
                    return actions.filePick(arguments, callCount);
                });

        register(
                "media.pick",
                "Ask the owner to choose photos or videos with Android's system media picker, then import the selections into the PickPico workspace.",
                "media",
                "human_interaction",
                false,
                mediaPickSchema(),
                (arguments, callCount) -> {
                    validatePickerArguments(arguments);
                    String mediaType = arguments.optString("mediaType", "image_or_video");
                    if (!"image".equals(mediaType)
                            && !"video".equals(mediaType)
                            && !"image_or_video".equals(mediaType)) {
                        throw new CommandInputException("media.pick mediaType must be image, video, or image_or_video");
                    }
                    return actions.mediaPick(arguments, callCount);
                });

        register(
                "share.send",
                "Open Android's share sheet for Agent-prepared text, a URL, or a PickPico workspace file. The human chooses the destination app/recipient.",
                "share",
                "external_communication",
                true,
                shareSendSchema(),
                (arguments, callCount) -> {
                    String text = arguments.optString("text", "");
                    String url = arguments.optString("url", "");
                    String workspacePath = arguments.optString("workspacePath", "");
                    if (text.isEmpty() && url.isEmpty() && workspacePath.isEmpty()) {
                        throw new CommandInputException("share.send requires text, url, or workspacePath");
                    }
                    if (text.length() > 65536 || url.length() > 4096) {
                        throw new CommandInputException("share.send text or URL is too long");
                    }
                    if (!workspacePath.isEmpty()) {
                        validateWorkspacePath(workspacePath);
                    }
                    return actions.shareSend(arguments, callCount);
                });

        register(
                "workspace.info",
                "Return the PickPico workspace root, storage state, and available command-line runtimes.",
                "workspace",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.workspaceInfo(callCount));

        register(
                "workspace.list",
                "List files and directories under PickPico's private workspace root.",
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
                "Read one UTF-8 text file from PickPico's private workspace root.",
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
                "Write one UTF-8 text file below PickPico's private workspace root, creating parent directories by default.",
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
                "Start one Node.js workspace entry point in PickPico's isolated :node runtime process.",
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
                "Return the current PickPico Node.js runtime process state.",
                "runtime",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.nodeStatus(callCount));

        register(
                "node.stop",
                "Stop the isolated PickPico Node.js runtime process.",
                "runtime",
                "process_control",
                true,
                nodeStopSchema(),
                (arguments, callCount) -> actions.nodeStop(arguments, callCount));

        register(
                "app.update",
                "Download, verify, and hand a newer PickPico APK to Android's package installer.",
                "app",
                "software_update",
                true,
                appUpdateSchema(),
                (arguments, callCount) -> actions.appUpdate(arguments, callCount));

        register(
                "app.update_check",
                "Check PickPico's configured update channel and report whether a newer signed APK is available.",
                "app",
                "read_only",
                false,
                appUpdateChannelSchema(),
                (arguments, callCount) -> actions.appUpdateCheck(arguments, callCount));

        register(
                "app.update_latest",
                "Resolve the latest PickPico release from the configured update channel and start the verified self-update flow.",
                "app",
                "software_update",
                true,
                appUpdateChannelSchema(),
                (arguments, callCount) -> actions.appUpdateLatest(arguments, callCount));

        register(
                "app.update_status",
                "Return PickPico self-update progress, setup requirements, and installer status.",
                "app",
                "read_only",
                false,
                noArgumentsSchema(),
                (arguments, callCount) -> actions.appUpdateStatus(callCount));

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
            if (!actions.isCommandExposed(command.id)) {
                continue;
            }
            JSONObject described = command.describe();
            described.put("group", AndroidCapabilityRegistry.isHyperCommand(command.id) ? "hyper" : "core");
            result.put(described);
        }
        return new JSONObject()
                .put("commands", result)
                .put("count", result.length());
    }

    JSONObject search(JSONObject arguments) throws JSONException {
        String query = arguments == null ? "" : arguments.optString("query", "").trim();
        String category = arguments == null ? "" : arguments.optString("category", "").trim();
        String group = arguments == null ? "" : arguments.optString("group", "").trim();
        boolean availableOnly = arguments != null && arguments.optBoolean("availableOnly", false);
        boolean includeSchema = arguments == null || arguments.optBoolean("includeSchema", true);
        int limit = arguments == null ? 8 : arguments.optInt("limit", 8);
        limit = Math.max(1, Math.min(20, limit));

        List<SearchMatch> matches = new ArrayList<>();
        for (Command command : commands.values()) {
            JSONObject state = actions.capabilityState(command.id);
            String commandGroup = state.optString(
                    "group",
                    AndroidCapabilityRegistry.isHyperCommand(command.id) ? "hyper" : "core");
            if (!category.isEmpty() && !category.equalsIgnoreCase(command.category)) {
                continue;
            }
            if (!group.isEmpty() && !group.equalsIgnoreCase(commandGroup)) {
                continue;
            }
            if (availableOnly && !state.optBoolean("available", false)) {
                continue;
            }

            int score = searchScore(query, command);
            if (!query.isEmpty() && score <= 0) {
                continue;
            }
            matches.add(new SearchMatch(command, state, score));
        }

        matches.sort(Comparator
                .comparingInt((SearchMatch match) -> match.score).reversed()
                .thenComparing(match -> match.command.id));

        JSONArray result = new JSONArray();
        for (SearchMatch match : matches) {
            if (result.length() >= limit) {
                break;
            }
            Command command = match.command;
            JSONObject state = match.state;
            JSONObject item = new JSONObject()
                    .put("id", command.id)
                    .put("description", command.description)
                    .put("category", command.category)
                    .put("risk", command.risk)
                    .put("sideEffect", command.sideEffect)
                    .put("group", state.optString("group", "core"))
                    .put("supported", state.optBoolean("supported", true))
                    .put("enabled", state.optBoolean("enabled", true))
                    .put("available", state.optBoolean("available", false))
                    .put("state", state.optString("state", "unknown"))
                    .put("requiresSetup", state.optBoolean("requiresSetup", false))
                    .put("userInteractionRequired", state.optBoolean("userInteractionRequired", false))
                    .put("score", match.score);
            if (state.has("setupType")) {
                item.put("setupType", state.opt("setupType"));
            }
            if (state.has("reason")) {
                item.put("reason", state.optString("reason", ""));
            }
            if (includeSchema) {
                item.put("inputSchema", command.inputSchema);
            }
            result.put(item);
        }

        return new JSONObject()
                .put("query", query)
                .put("matches", result)
                .put("count", result.length())
                .put("totalCandidates", matches.size())
                .put("discoveryHint",
                        "Capabilities are dynamic. Search before concluding that a requested device action is unsupported.");
    }

    JSONObject execute(String commandId, JSONObject arguments, long callCount) throws JSONException {
        Command command = requireCommand(commandId);
        return invoke(command, arguments == null ? new JSONObject() : arguments, callCount);
    }

    JSONObject run(String commandId, JSONObject arguments, long callCount) throws JSONException {
        Command command = requireCommand(commandId);
        String executionId = "cmd-" + UUID.randomUUID();
        String startedAt = Instant.now().toString();

        try {
            JSONObject result = invoke(
                    command,
                    arguments == null ? new JSONObject() : arguments,
                    callCount);
            JSONObject publicResult = new JSONObject(result.toString());
            JSONArray mediaContent = publicResult.optJSONArray("_mcpContent");
            publicResult.remove("_mcpContent");
            if (mediaContent != null) {
                publicResult.put("mediaContentDelivered", true);
            }
            JSONObject execution = new JSONObject()
                    .put("executionId", executionId)
                    .put("commandId", commandId)
                    .put("status", "completed")
                    .put("startedAt", startedAt)
                    .put("completedAt", Instant.now().toString())
                    .put("result", publicResult);
            JSONObject historyExecution = new JSONObject(execution.toString());
            remember(executionId, historyExecution);
            if (mediaContent != null) {
                execution.put("_mcpContent", mediaContent);
            }
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
        if (!actions.isCommandExposed(commandId)) {
            throw new CommandInputException(
                    "Command is not currently available: " + commandId
                            + ". Use capability.status to inspect setup or Hyper Mode requirements.");
        }
        return command;
    }

    private static int searchScore(String rawQuery, Command command) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return 1;
        }
        String query = normalizeSearchText(rawQuery);
        String id = normalizeSearchText(command.id);
        String description = normalizeSearchText(command.description);
        String aliases = normalizeSearchText(searchAliases(command.id));
        String haystack = id + " " + description + " " + normalizeSearchText(command.category)
                + " " + normalizeSearchText(command.risk) + " " + aliases;

        int score = 0;
        if (id.equals(query)) {
            score += 1000;
        } else if (id.contains(query)) {
            score += 220;
        }
        if (description.contains(query) || aliases.contains(query)) {
            score += 180;
        }

        // CJK queries commonly arrive without whitespace segmentation (for example
        // "幫我截圖看看手機畫面"). Match individual alias intents as substrings of
        // the full query so discovery does not depend on an English-style tokenizer.
        for (String alias : aliases.split("\\s+")) {
            if (alias.length() >= 2 && query.contains(alias)) {
                score += 70;
            }
        }

        String[] tokens = query.split("\\s+");
        for (String token : tokens) {
            if (token.length() < 2 || isSearchStopWord(token)) {
                continue;
            }
            if (id.contains(token)) {
                score += 80;
            }
            if (aliases.contains(token)) {
                score += 55;
            }
            if (description.contains(token)) {
                score += 35;
            }
            if (haystack.contains(token)) {
                score += 10;
            }
        }
        return score;
    }

    private static String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean isSearchStopWord(String token) {
        return "the".equals(token)
                || "a".equals(token)
                || "an".equals(token)
                || "to".equals(token)
                || "for".equals(token)
                || "of".equals(token)
                || "on".equals(token)
                || "in".equals(token)
                || "my".equals(token)
                || "current".equals(token)
                || "phone".equals(token)
                || "device".equals(token)
                || "need".equals(token)
                || "want".equals(token);
    }

    private static String searchAliases(String commandId) {
        switch (commandId) {
            case "screen.capture":
                return "screenshot display screen pixels see look observe visual app canvas image 截圖 螢幕 畫面 看 看看 視覺";
            case "camera.capture":
                return "camera photo picture photograph see physical world lens 相機 拍照 照片 攝影";
            case "ui.inspect":
                return "accessibility semantic ui tree inspect understand app screen controls text 介面 結構 元件 讀畫面";
            case "ui.action":
                return "click tap press back home recents control interact app ui 點 點擊 按 操作 返回 首頁";
            case "ui.type":
                return "type text enter input edit field keyboard form ui 輸入 打字 文字 表單";
            case "ui.scroll":
                return "scroll swipe move page feed list up down left right ui 捲動 滾動 下滑 上滑 左滑 右滑";
            case "human.help":
                return "human help ask person handoff physical action assistance nearby owner 人類 求助 幫忙 協助 請人";
            case "notification.list":
            case "notification.get":
                return "notification notifications alert message inbox read";
            case "notification.reply":
                return "notification reply respond message remoteinput chat";
            case "notification.invoke_action":
            case "notification.actions":
                return "notification action button interact approve dismiss open";
            case "app.list":
            case "app.launch":
                return "application apps installed launch open start package";
            case "location.get":
                return "gps location position where map coordinates latitude longitude 定位 位置 在哪裡 地圖 經緯度";
            case "clipboard.get":
            case "clipboard.set":
                return "clipboard copy paste text";
            case "contacts.search":
                return "contacts search find lookup address book person people name contact 通訊錄 聯絡人 搜尋 尋找 姓名";
            case "contacts.get":
                return "contact details phone email address person contact info 聯絡人 詳細 電話 email 地址";
            case "calendar.list":
            case "calendar.get":
                return "calendar event events schedule agenda appointment meeting read 日曆 行事曆 行程 會議 約會";
            case "calendar.create":
            case "calendar.update":
            case "calendar.delete":
                return "calendar event schedule appointment meeting create update edit delete add 日曆 行事曆 行程 新增 修改 刪除";
            case "file.pick":
                return "file picker choose select upload document pdf zip browse 檔案 選擇 上傳 文件";
            case "media.pick":
                return "photo video media picker gallery choose select image 相簿 圖片 照片 影片 選擇";
            case "share.send":
                return "share send handoff android app text url file line gmail messenger 分享 傳送 傳給";
            case "workspace.list":
            case "workspace.read":
            case "workspace.write":
                return "file files filesystem workspace storage read write";
            case "process.exec":
            case "process.run":
            case "process.output":
            case "process.stop":
                return "shell process command terminal execute linux script runtime";
            default:
                return "";
        }
    }

    private static final class SearchMatch {
        final Command command;
        final JSONObject state;
        final int score;

        SearchMatch(Command command, JSONObject state, int score) {
            this.command = command;
            this.state = state;
            this.score = score;
        }
    }

    private JSONObject invoke(Command command, JSONObject arguments, long callCount) throws JSONException {
        // Every capability funnels through here. Android uses this hook to renew
        // a short screen-awake lease while an Agent is actively operating the
        // phone, without changing the owner's system screen-timeout setting.
        actions.onAgentCommandActivity(command.id);
        if (requiresApproval(command)) {
            JSONObject approval = actions.requestApproval(
                    command.id,
                    command.description,
                    command.risk,
                    arguments,
                    callCount);
            if (!approval.optBoolean("approved", false)) {
                String status = approval.optString("status", "rejected");
                throw new CommandInputException(
                        "Human approval not granted for " + command.id + " (" + status + ")");
            }
        }
        return command.handler.call(arguments, callCount);
    }

    private boolean requiresApproval(Command command) {
        if (!command.sideEffect || "human.help".equals(command.id)) {
            return false;
        }
        String mode = actions.approvalMode();
        if (McpocketPolicySettings.APPROVAL_YOLO.equals(mode)) {
            return false;
        }
        if (McpocketPolicySettings.APPROVAL_ASK.equals(mode)) {
            return true;
        }
        return requiresApprovalInAutoMode(command);
    }

    private static boolean requiresApprovalInAutoMode(Command command) {
        return "security_action".equals(command.risk)
                || "software_update".equals(command.risk)
                || "arbitrary_process".equals(command.risk)
                || "filesystem_write".equals(command.risk)
                || "notification_write".equals(command.risk);
    }

    private JSONObject capabilityList(long callCount) throws JSONException {
        JSONArray capabilities = new JSONArray();
        for (Command command : commands.values()) {
            JSONObject item = capabilityDescriptor(command);
            merge(item, actions.capabilityState(command.id));
            capabilities.put(item);
        }
        return new JSONObject()
                .put("capabilities", capabilities)
                .put("count", capabilities.length())
                .put("toolCallCount", callCount);
    }

    private JSONObject capabilityStatus(String capabilityId, long callCount) throws JSONException {
        if (capabilityId == null || capabilityId.isEmpty()) {
            throw new CommandInputException("capability.status requires a non-empty id");
        }
        Command command = commands.get(capabilityId);
        if (command == null) {
            throw new CommandInputException("Unknown capability: " + capabilityId);
        }
        JSONObject result = capabilityDescriptor(command);
        merge(result, actions.capabilityState(command.id));
        return result.put("toolCallCount", callCount);
    }

    private static JSONObject capabilityDescriptor(Command command) throws JSONException {
        return new JSONObject()
                .put("id", command.id)
                .put("description", command.description)
                .put("category", command.category)
                .put("risk", command.risk)
                .put("sideEffect", command.sideEffect)
                .put("group", AndroidCapabilityRegistry.isHyperCommand(command.id) ? "hyper" : "core");
    }

    private static void merge(JSONObject target, JSONObject source) throws JSONException {
        if (source == null) {
            return;
        }
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, source.get(key));
        }
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

    static JSONObject noArgumentsSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject())
                .put("additionalProperties", false);
    }

    static JSONObject capabilityStatusSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("id", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 160)
                                .put("description", "Capability/command ID returned by capability.list.")))
                .put("required", new JSONArray().put("id"))
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

    static JSONObject cameraCaptureSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("lens", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray().put("back").put("front").put("any"))
                                .put("default", "back"))
                        .put("maxWidth", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 320)
                                .put("maximum", 4096)
                                .put("default", 1280))
                        .put("maxHeight", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 320)
                                .put("maximum", 4096)
                                .put("default", 1280))
                        .put("quality", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 50)
                                .put("maximum", 100)
                                .put("default", 85))
                        .put("returnContent", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("additionalProperties", false);
    }

    static JSONObject phoneNotifySchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("title", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 120)
                                .put("default", "PickPico Agent"))
                        .put("body", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 2000)))
                .put("required", new JSONArray().put("body"))
                .put("additionalProperties", false);
    }

    static JSONObject phoneSpeakSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 2000))
                        .put("language", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 64)
                                .put("description", "Optional BCP-47 language tag, for example zh-TW or en-US."))
                        .put("rate", new JSONObject()
                                .put("type", "number")
                                .put("minimum", 0.5)
                                .put("maximum", 2.0)
                                .put("default", 1.0))
                        .put("pitch", new JSONObject()
                                .put("type", "number")
                                .put("minimum", 0.5)
                                .put("maximum", 2.0)
                                .put("default", 1.0))
                        .put("queue", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray().put("flush").put("add"))
                                .put("default", "flush")))
                .put("required", new JSONArray().put("text"))
                .put("additionalProperties", false);
    }

    static JSONObject audioSetSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("stream", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray()
                                        .put("media")
                                        .put("notification")
                                        .put("ring")
                                        .put("alarm")))
                        .put("percent", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", 100)
                                .put("description", "Requested volume as a percentage of the stream maximum."))
                        .put("showUi", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)
                                .put("description", "Ask Android to briefly show its native volume UI.")))
                .put("required", new JSONArray().put("stream").put("percent"))
                .put("additionalProperties", false);
    }

    static JSONObject microphoneRecordSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("durationMs", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 500)
                                .put("maximum", 10000)
                                .put("default", 3000))
                        .put("returnContent", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("additionalProperties", false);
    }

    static JSONObject humanHelpSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("title", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 160)
                                .put("default", "AI needs your help"))
                        .put("instruction", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 4000))
                        .put("actions", new JSONObject()
                                .put("type", "array")
                                .put("maxItems", 6)
                                .put("items", new JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 1)
                                        .put("maxLength", 80)))
                        .put("allowTextReply", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true))
                        .put("allowImages", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true))
                        .put("maxImages", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", 3)
                                .put("default", 3))
                        .put("idleTimeoutSeconds", new JSONObject()
                                .put("type", "integer")
                                .put("enum", new JSONArray().put(120).put(180).put(360))
                                .put("description", "Renewable human-idle timeout. Choose 120s for a quick nearby action, 180s for normal help, or 360s for a task that may require moving, taking a photo, or finding something. Human activity resets this timer.")
                                .put("default", 180)))
                .put("required", new JSONArray().put("instruction"))
                .put("additionalProperties", false);
    }

    static JSONObject humanHelpStatusSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("requestId", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 128))
                        .put("includeAttachmentData", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("required", new JSONArray().put("requestId"))
                .put("additionalProperties", false);
    }

    static JSONObject notificationListSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("limit", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 200)
                                .put("default", 50))
                        .put("includeOwn", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)))
                .put("additionalProperties", false);
    }

    static JSONObject notificationKeySchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("key", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 1024)))
                .put("required", new JSONArray().put("key"))
                .put("additionalProperties", false);
    }

    static JSONObject notificationActionSchema(boolean actionIndexOptional) throws JSONException {
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("key", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 1024))
                        .put("actionIndex", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", 32)))
                .put("additionalProperties", false);
        JSONArray required = new JSONArray().put("key");
        if (!actionIndexOptional) {
            required.put("actionIndex");
        }
        return schema.put("required", required);
    }

    static JSONObject notificationReplySchema() throws JSONException {
        JSONObject properties = notificationActionSchema(true).getJSONObject("properties");
        properties.put("text", new JSONObject()
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", 4000));
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("key").put("text"))
                .put("additionalProperties", false);
    }

    static JSONObject uiInspectSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("maxNodes", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 1000)
                                .put("default", 200))
                        .put("maxDepth", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 30)
                                .put("default", 12))
                        .put("includeInvisible", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)))
                .put("additionalProperties", false);
    }

    static JSONObject uiActionSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("action", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray()
                                        .put("click")
                                        .put("long_click")
                                        .put("focus")
                                        .put("accessibility_focus")
                                        .put("back")
                                        .put("home")
                                        .put("recents")))
                        .put("selector", uiSelectorSchema()))
                .put("required", new JSONArray().put("action"))
                .put("additionalProperties", false);
    }

    static JSONObject uiTypeSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("selector", uiSelectorSchema())
                        .put("text", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 8192))
                        .put("append", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)))
                .put("required", new JSONArray().put("selector").put("text"))
                .put("additionalProperties", false);
    }

    static JSONObject uiScrollSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("selector", uiSelectorSchema())
                        .put("direction", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray()
                                        .put("forward")
                                        .put("backward")
                                        .put("up")
                                        .put("down")
                                        .put("left")
                                        .put("right"))
                                .put("default", "forward")))
                .put("additionalProperties", false);
    }

    static JSONObject screenCaptureSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("quality", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 50)
                                .put("maximum", 100)
                                .put("default", 82)
                                .put("description", "JPEG quality for the returned screen frame."))
                        .put("returnContent", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("additionalProperties", false);
    }

    private static JSONObject uiSelectorSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("path", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 512)
                                .put("description", "Tree path returned by ui.inspect, for example 0/2/1."))
                        .put("viewId", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 512))
                        .put("text", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 2048))
                        .put("contentDescription", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 2048))
                        .put("className", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 512))
                        .put("instance", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", 100)
                                .put("default", 0)))
                .put("additionalProperties", false);
    }

    static JSONObject appListSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("query", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 120))
                        .put("limit", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 300)
                                .put("default", 100)))
                .put("additionalProperties", false);
    }

    static JSONObject appLaunchSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("packageName", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 255)))
                .put("required", new JSONArray().put("packageName"))
                .put("additionalProperties", false);
    }

    static JSONObject urlOpenSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("url", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 4096)))
                .put("required", new JSONArray().put("url"))
                .put("additionalProperties", false);
    }

    static JSONObject locationGetSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("timeoutMs", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 500)
                                .put("maximum", 15000)
                                .put("default", 7000))
                        .put("highAccuracy", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)))
                .put("additionalProperties", false);
    }

    static JSONObject clipboardSetSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 65536))
                        .put("label", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 120)
                                .put("default", "PickPico Agent")))
                .put("required", new JSONArray().put("text"))
                .put("additionalProperties", false);
    }

    static JSONObject contactsSearchSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("query", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 200)
                                .put("description", "Display-name search. Empty lists contacts in name order."))
                        .put("limit", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 100)
                                .put("default", 20)))
                .put("additionalProperties", false);
    }

    static JSONObject contactGetSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("id", numericIdSchema("Contact ID returned by contacts.search.")))
                .put("required", new JSONArray().put("id"))
                .put("additionalProperties", false);
    }

    static JSONObject calendarListSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("startEpochMs", new JSONObject()
                                .put("type", "integer")
                                .put("description", "Inclusive start in Unix epoch milliseconds. Defaults to now."))
                        .put("endEpochMs", new JSONObject()
                                .put("type", "integer")
                                .put("description", "Exclusive end in Unix epoch milliseconds. Defaults to seven days after start."))
                        .put("calendarId", numericIdSchema("Optional calendar ID filter."))
                        .put("limit", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 200)
                                .put("default", 50)))
                .put("additionalProperties", false);
    }

    static JSONObject calendarEventIdSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("eventId", numericIdSchema("Calendar event ID.")))
                .put("required", new JSONArray().put("eventId"))
                .put("additionalProperties", false);
    }

    static JSONObject calendarCreateSchema() throws JSONException {
        JSONObject properties = calendarMutableProperties();
        properties.put("calendarId", numericIdSchema(
                "Target calendar ID. If omitted, PickPico chooses a writable visible calendar."));
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("title").put("startEpochMs").put("endEpochMs"))
                .put("additionalProperties", false);
    }

    static JSONObject calendarUpdateSchema() throws JSONException {
        JSONObject properties = calendarMutableProperties();
        properties.put("eventId", numericIdSchema("Calendar event ID to update."));
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("eventId"))
                .put("additionalProperties", false);
    }

    private static JSONObject calendarMutableProperties() throws JSONException {
        return new JSONObject()
                .put("title", new JSONObject().put("type", "string").put("maxLength", 500))
                .put("description", new JSONObject().put("type", "string").put("maxLength", 10000))
                .put("location", new JSONObject().put("type", "string").put("maxLength", 1000))
                .put("startEpochMs", new JSONObject().put("type", "integer"))
                .put("endEpochMs", new JSONObject().put("type", "integer"))
                .put("allDay", new JSONObject().put("type", "boolean").put("default", false))
                .put("timezone", new JSONObject().put("type", "string").put("maxLength", 120));
    }

    static JSONObject filePickSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("mimeTypes", new JSONObject()
                                .put("type", "array")
                                .put("maxItems", 16)
                                .put("items", new JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)))
                        .put("allowMultiple", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false))
                        .put("maxItems", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 10)
                                .put("default", 5))
                        .put("timeoutSeconds", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 30)
                                .put("maximum", 300)
                                .put("default", 120)))
                .put("additionalProperties", false);
    }

    static JSONObject mediaPickSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("mediaType", new JSONObject()
                                .put("type", "string")
                                .put("enum", new JSONArray()
                                        .put("image")
                                        .put("video")
                                        .put("image_or_video"))
                                .put("default", "image_or_video"))
                        .put("allowMultiple", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false))
                        .put("maxItems", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", 10)
                                .put("default", 5))
                        .put("returnContent", new JSONObject()
                                .put("type", "boolean")
                                .put("default", true)
                                .put("description", "Return selected images as native MCP image content when payload size allows."))
                        .put("timeoutSeconds", new JSONObject()
                                .put("type", "integer")
                                .put("minimum", 30)
                                .put("maximum", 300)
                                .put("default", 120)))
                .put("additionalProperties", false);
    }

    static JSONObject shareSendSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 65536))
                        .put("url", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 4096))
                        .put("workspacePath", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1024))
                        .put("mimeType", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 120))
                        .put("chooserTitle", new JSONObject()
                                .put("type", "string")
                                .put("maxLength", 120)
                                .put("default", "Share with")))
                .put("additionalProperties", false);
    }

    private static JSONObject numericIdSchema(String description) throws JSONException {
        return new JSONObject()
                .put("type", "string")
                .put("pattern", "^[0-9]+$")
                .put("maxLength", 32)
                .put("description", description);
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

    static JSONObject appUpdateSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("url", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 8)
                                .put("maxLength", 4096)
                                .put("description", "HTTP(S) URL for the candidate PickPico APK."))
                        .put("sha256", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 64)
                                .put("maxLength", 64)
                                .put("description", "Required SHA-256 of the exact APK bytes."))
                        .put("allowSameVersion", new JSONObject()
                                .put("type", "boolean")
                                .put("default", false)
                                .put("description", "Development-only override for reinstalling the same version.")))
                .put("required", new JSONArray().put("url").put("sha256"))
                .put("additionalProperties", false);
    }

    static JSONObject appUpdateChannelSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("manifestUrl", new JSONObject()
                                .put("type", "string")
                                .put("minLength", 8)
                                .put("maxLength", 4096)
                                .put("description", "Optional HTTP(S) update manifest URL. Defaults to <configured relay>/v1/update/latest.")))
                .put("additionalProperties", false);
    }

    private static void validateNumericId(String raw, String label) {
        if (raw == null || raw.isEmpty() || raw.length() > 32) {
            throw new CommandInputException(label + " must be a numeric Android provider ID");
        }
        for (int index = 0; index < raw.length(); index++) {
            if (!Character.isDigit(raw.charAt(index))) {
                throw new CommandInputException(label + " must contain digits only");
            }
        }
    }

    private static void validateCalendarTimes(JSONObject arguments, boolean requireBoth) {
        boolean hasStart = arguments.has("startEpochMs");
        boolean hasEnd = arguments.has("endEpochMs");
        if (requireBoth && (!hasStart || !hasEnd)) {
            throw new CommandInputException("calendar event requires startEpochMs and endEpochMs");
        }
        if (hasStart && hasEnd && arguments.optLong("endEpochMs") <= arguments.optLong("startEpochMs")) {
            throw new CommandInputException("calendar event endEpochMs must be after startEpochMs");
        }
        if (arguments.optString("title", "").length() > 500
                || arguments.optString("description", "").length() > 10000
                || arguments.optString("location", "").length() > 1000
                || arguments.optString("timezone", "").length() > 120) {
            throw new CommandInputException("calendar event field exceeds its maximum length");
        }
    }

    private static void validatePickerArguments(JSONObject arguments) {
        int timeoutSeconds = arguments.optInt("timeoutSeconds", 120);
        if (timeoutSeconds < 30 || timeoutSeconds > 300) {
            throw new CommandInputException("picker timeoutSeconds must be between 30 and 300");
        }
        int maxItems = arguments.optInt("maxItems", 5);
        if (maxItems < 1 || maxItems > 10) {
            throw new CommandInputException("picker maxItems must be between 1 and 10");
        }
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new CommandInputException("workspace path must not be empty");
        }
        if (path.length() > 1024) {
            throw new CommandInputException("workspace path is limited to 1024 characters");
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new CommandInputException("workspace paths must be relative to the PickPico workspace root");
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
