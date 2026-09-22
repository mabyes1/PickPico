package com.mcpocket.poc;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Observations, not guesses: an idle snapshot alone does not prove why a call failed. */
final class ConnectionDiagnostics {
    static String reasonCode(String detail) {
        if (detail == null) return "unknown";
        if (detail.contains("network lost")) return "network_lost";
        if (detail.contains("network changed")) return "network_changed";
        if (detail.contains("heartbeat timed out")) return "heartbeat_timeout";
        if (detail.contains("heartbeat could not")) return "socket_write_failed";
        if (detail.contains("UnknownHostException")) return "dns_failure";
        if (detail.contains("SSL")) return "tls_failure";
        if (detail.contains("relay closing") || detail.contains("relay closed")) return "remote_closed";
        if (detail.contains("relay error")) return "transport_error";
        return "unknown";
    }

    static JSONObject power(Context context) {
        JSONObject out = new JSONObject();
        try {
            PowerManager p = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (p != null) out.put("interactive", p.isInteractive()).put("deviceIdle", p.isDeviceIdleMode())
                    .put("powerSaveMode", p.isPowerSaveMode())
                    .put("ignoringBatteryOptimizations", p.isIgnoringBatteryOptimizations(context.getPackageName()));
            KeyguardManager k = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (k != null) out.put("keyguardLocked", k.isKeyguardLocked());
            ConnectivityManager c = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (c != null) {
                out.put("restrictBackgroundStatus", c.getRestrictBackgroundStatus());
                NetworkCapabilities n = c.getNetworkCapabilities(c.getActiveNetwork());
                out.put("networkPresent", n != null).put("internetValidated", n != null && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
        } catch (Exception e) { try { out.put("observationError", e.getClass().getSimpleName()); } catch (Exception ignored) { } }
        return out;
    }

    static JSONObject snapshot(Context context) {
        JSONObject out = power(context);
        try {
            out.put("causeConfidence", "observations_only").put("processAlive", true);
            out.put("lastDisconnect", new JSONObject(context.getSharedPreferences("connection_diagnostics", 0).getString("lastDisconnect", "{}")));
            JSONArray exits = new JSONArray();
            ActivityManager a = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 30 && a != null) {
                for (ApplicationExitInfo e : a.getHistoricalProcessExitReasons(context.getPackageName(), 0, 3))
                    exits.put(new JSONObject().put("timestamp", e.getTimestamp()).put("reasonCode", e.getReason())
                            .put("processName", e.getProcessName()).put("importance", e.getImportance()));
            }
            out.put("historicalExits", exits).put("note", "Exit records are historical; do not attribute a current disconnection to an old exit. Samsung sleep-list membership is not inferred.");
        } catch (Exception e) { try { out.put("historyError", e.getClass().getSimpleName()); } catch (Exception ignored) { } }
        return out;
    }

    static void record(Context context, String detail) {
        try {
            JSONObject event = power(context).put("at", java.time.Instant.now().toString())
                    .put("reasonCode", reasonCode(detail)).put("detail", detail)
                    .put("processStartedAt", PickPicoApplication.processStartedAt());
            context.getSharedPreferences("connection_diagnostics", 0).edit().putString("lastDisconnect", event.toString()).apply();
        } catch (Exception ignored) { }
    }
}
