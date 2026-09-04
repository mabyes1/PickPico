package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

/** Android-facing actions that can be exposed as MCP tools. */
interface McpToolActions {
    default void onAgentCommandStarted(String commandId) {
        onAgentCommandActivity(commandId);
    }

    default void onAgentCommandFinished(String commandId) {
        onAgentCommandActivity(commandId);
    }

    default void onAgentCommandActivity(String commandId) {
    }

    default JSONObject capabilityState(String commandId) throws JSONException {
        return new JSONObject()
                .put("platform", "test")
                .put("group", "core")
                .put("supported", true)
                .put("enabled", true)
                .put("available", true)
                .put("state", "available")
                .put("requiresSetup", false)
                .put("userInteractionRequired", false);
    }

    default boolean isCommandExposed(String commandId) {
        return true;
    }

    default String approvalMode() {
        // Preserve current behavior for pure-Java tests and non-Android adapters.
        return McpocketPolicySettings.APPROVAL_YOLO;
    }

    default JSONObject policyStatus(long callCount) throws JSONException {
        return new JSONObject()
                .put("hyperMode", new JSONObject().put("enabled", false))
                .put("approvalMode", new JSONObject().put("value", approvalMode()))
                .put("osBoundariesStillApply", true)
                .put("toolCallCount", callCount);
    }

    default JSONObject requestApproval(
            String commandId,
            String description,
            String risk,
            JSONObject arguments,
            long callCount) throws JSONException {
        return new JSONObject()
                .put("approved", true)
                .put("source", "adapter_default")
                .put("toolCallCount", callCount);
    }

    JSONObject serverInfo(long callCount) throws JSONException;

    JSONObject phoneStatus(long callCount) throws JSONException;

    JSONObject phoneExec(String command, long callCount) throws JSONException;

    JSONObject execCommand(JSONObject arguments, long callCount) throws JSONException;

    JSONObject readProcessOutput(JSONObject arguments, long callCount) throws JSONException;

    JSONObject killProcessSession(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceInfo(long callCount) throws JSONException;

    JSONObject workspaceList(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceReadFile(JSONObject arguments, long callCount) throws JSONException;

    JSONObject workspaceWriteFile(JSONObject arguments, long callCount) throws JSONException;

    JSONObject nodeStart(JSONObject arguments, long callCount) throws JSONException;

    JSONObject nodeStatus(long callCount) throws JSONException;

    JSONObject nodeStop(JSONObject arguments, long callCount) throws JSONException;

    JSONObject appUpdate(JSONObject arguments, long callCount) throws JSONException;

    default JSONObject appUpdateCheck(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "app.update_check")
                .put("toolCallCount", callCount);
    }

    default JSONObject appUpdateLatest(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "app.update_latest")
                .put("toolCallCount", callCount);
    }

    JSONObject appUpdateStatus(long callCount) throws JSONException;

    JSONObject phoneRing(String action, int durationSeconds, long callCount) throws JSONException;

    JSONObject phoneLock(long callCount) throws JSONException;

    JSONObject phoneWake(long callCount) throws JSONException;

    default JSONObject cameraCapture(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "camera.capture")
                .put("toolCallCount", callCount);
    }

    default JSONObject phoneNotify(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "phone.notify")
                .put("toolCallCount", callCount);
    }

    default JSONObject phoneSpeak(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "phone.speak")
                .put("toolCallCount", callCount);
    }

    default JSONObject audioStatus(long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "audio.status")
                .put("toolCallCount", callCount);
    }

    default JSONObject audioSet(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "audio.set")
                .put("toolCallCount", callCount);
    }

    default JSONObject microphoneRecord(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "microphone.record")
                .put("toolCallCount", callCount);
    }

    default JSONObject humanHelp(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "human.help")
                .put("toolCallCount", callCount);
    }

    default JSONObject humanHelpStatus(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "human.help.status")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationList(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.list")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationGet(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.get")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationDismiss(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.dismiss")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationActions(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.actions")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationInvokeAction(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.invoke_action")
                .put("toolCallCount", callCount);
    }

    default JSONObject notificationReply(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "notification.reply")
                .put("toolCallCount", callCount);
    }

    default JSONObject uiInspect(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "ui.inspect")
                .put("toolCallCount", callCount);
    }

    default JSONObject uiAction(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "ui.action")
                .put("toolCallCount", callCount);
    }

    default JSONObject uiType(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "ui.type")
                .put("toolCallCount", callCount);
    }

    default JSONObject uiScroll(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "ui.scroll")
                .put("toolCallCount", callCount);
    }

    default JSONObject screenCapture(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "screen.capture")
                .put("toolCallCount", callCount);
    }

    default JSONObject appList(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "app.list")
                .put("toolCallCount", callCount);
    }

    default JSONObject appLaunch(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "app.launch")
                .put("toolCallCount", callCount);
    }

    default JSONObject urlOpen(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "url.open")
                .put("toolCallCount", callCount);
    }

    default JSONObject locationGet(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "location.get")
                .put("toolCallCount", callCount);
    }

    default JSONObject clipboardGet(long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "clipboard.get")
                .put("toolCallCount", callCount);
    }

    default JSONObject clipboardSet(JSONObject arguments, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", "clipboard.set")
                .put("toolCallCount", callCount);
    }

    default JSONObject contactsSearch(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("contacts.search", callCount);
    }

    default JSONObject contactsGet(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("contacts.get", callCount);
    }

    default JSONObject calendarList(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("calendar.list", callCount);
    }

    default JSONObject calendarGet(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("calendar.get", callCount);
    }

    default JSONObject calendarCreate(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("calendar.create", callCount);
    }

    default JSONObject calendarUpdate(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("calendar.update", callCount);
    }

    default JSONObject calendarDelete(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("calendar.delete", callCount);
    }

    default JSONObject filePick(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("file.pick", callCount);
    }

    default JSONObject mediaPick(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("media.pick", callCount);
    }

    default JSONObject shareSend(JSONObject arguments, long callCount) throws JSONException {
        return unsupported("share.send", callCount);
    }

    private static JSONObject unsupported(String capability, long callCount) throws JSONException {
        return new JSONObject()
                .put("supported", false)
                .put("capability", capability)
                .put("toolCallCount", callCount);
    }
}
