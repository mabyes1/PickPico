package com.mcpocket.poc;

import android.content.Context;

/** Translate display labels only; transport and task state identifiers stay unchanged. */
final class UiStatus {
    static String label(Context context, String state) {
        int id;
        switch (state) {
            case "connected": id = R.string.ui_connected; break;
            case "connecting": case "reconnecting": id = R.string.ui_connecting; break;
            case "starting": id = R.string.ui_starting; break;
            case "disconnected": id = R.string.ui_not_connected; break;
            case "offline": case "stopped": id = R.string.ui_offline; break;
            case "running": id = R.string.ui_running; break;
            case "waiting_human": id = R.string.ui_waiting; break;
            case "blocked": id = R.string.ui_blocked; break;
            case "completed": id = R.string.ui_task_completed; break;
            case "cancelled": id = R.string.status_cancelled; break;
            case "created": id = R.string.status_created; break;
            case "failed": case "error": id = R.string.status_failed; break;
            case "disabled": case "not_configured": id = R.string.ui_not_configured; break;
            default: return state;
        }
        return context.getString(id);
    }
}
