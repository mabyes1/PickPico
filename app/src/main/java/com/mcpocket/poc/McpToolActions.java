package com.mcpocket.poc;

import org.json.JSONException;
import org.json.JSONObject;

/** Android-facing actions that can be exposed as MCP tools. */
interface McpToolActions {
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

    JSONObject phoneEcho(String text, long callCount) throws JSONException;

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
}
