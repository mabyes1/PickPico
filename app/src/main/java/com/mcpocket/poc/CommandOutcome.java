package com.mcpocket.poc;

import org.json.JSONObject;

/** Separates an operation's failure from a successful query about past state. */
final class CommandOutcome {
    private CommandOutcome() {
    }

    static String executionStatus(JSONObject result) {
        String status = result.optString("status");
        if ("unknown".equals(status)) return "unknown";
        if (result.optBoolean("isError")) return "failed";
        if ("waiting_human".equals(status)) return "waiting_human";
        if ("pending_user_action".equals(status) || result.optBoolean("requiresUserAction")) return "waiting_user";
        if (result.optBoolean("running") || "running".equals(status) || "queued".equals(status)
                || "downloading".equals(status) || "installing".equals(status)) return "running";
        return "completed";
    }

    static boolean isFailure(String commandId, JSONObject result) {
        if (result == null) return true;
        // These commands describe state; a stopped process or a previous error
        // is data, not a failure to retrieve that data. Exceptions still propagate.
        if (commandId.endsWith(".status") || "app.update_status".equals(commandId)
                || "process.output".equals(commandId)) {
            return false;
        }
        // stop returns a session snapshot. A past stdin timeout/error is not a
        // failure to stop an already-exited process; a still-live process is.
        if ("process.stop".equals(commandId) && result.has("sessionId")) {
            return result.optBoolean("running", false);
        }
        if (result.optBoolean("isError", false) || result.optBoolean("timedOut", false)) return true;
        Object error = result.opt("error");
        if (error != null && error != JSONObject.NULL && !Boolean.FALSE.equals(error)
                && !error.toString().trim().isEmpty()) return true;
        String status = result.optString("status", "");
        if ("failed".equals(status) || "error".equals(status) || "rejected".equals(status)
                || "timed_out".equals(status) || "cancelled".equals(status)) return true;
        if (Boolean.FALSE.equals(result.opt("success"))) return true;

        // Interpret only operation-specific flags. Generic false values such as
        // found/available/running/hasUpdate are often valid query results.
        switch (commandId) {
            case "camera.capture":
            case "screen.capture":
                return Boolean.FALSE.equals(result.opt("captured"));
            case "microphone.record":
                return Boolean.FALSE.equals(result.opt("recorded"));
            case "ui.action":
            case "ui.type":
            case "ui.scroll":
                return Boolean.FALSE.equals(result.opt("performed"));
            case "process.exec":
            case "process.run":
                Object exitCode = result.opt("exitCode");
                return Boolean.FALSE.equals(result.opt("executed"))
                        || !result.optString("stdinError", "").isEmpty()
                        || (exitCode instanceof Number && ((Number) exitCode).intValue() != 0);
            default:
                return false;
        }
    }
}
