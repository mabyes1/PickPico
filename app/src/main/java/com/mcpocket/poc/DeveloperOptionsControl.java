package com.mcpocket.poc;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import org.json.JSONException;
import org.json.JSONObject;

/** Narrow system-setting control that survives turning the ADB transport off. */
final class DeveloperOptionsControl {
    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    static final String WIFI_ADB = "adb_wifi_enabled";

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONObject status(Context context) throws JSONException {
        return new JSONObject()
                .put("permissionGranted", hasPermission(context))
                .put("developerOptionsEnabled", read(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
                .put("wirelessDebuggingEnabled", read(context, WIFI_ADB))
                .put("usbDebuggingEnabled", read(context, Settings.Global.ADB_ENABLED))
                .put("grantCommand", "adb shell pm grant " + context.getPackageName() + " " + PERMISSION);
    }

    static synchronized JSONObject setEnabled(Context context, boolean enabled) throws JSONException {
        if (!hasPermission(context)) throw new CommandRuntime.CommandInputException(
                "WRITE_SECURE_SETTINGS_REQUIRED: run picoadb.authorize_control once while ADB is connected, or use the grant script");
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (enabled && keyguard != null && keyguard.isKeyguardLocked())
            throw new CommandRuntime.CommandInputException("UNLOCK_REQUIRED: unlock the phone before enabling debugging");
        if (enabled) {
            write(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, true);
            write(context, WIFI_ADB, true);
        } else {
            write(context, WIFI_ADB, false);
            write(context, Settings.Global.ADB_ENABLED, false);
            write(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, false);
        }
        JSONObject state = status(context);
        boolean verified = enabled
                ? state.getBoolean("developerOptionsEnabled") && state.getBoolean("wirelessDebuggingEnabled")
                : !state.getBoolean("developerOptionsEnabled") && !state.getBoolean("wirelessDebuggingEnabled")
                        && !state.getBoolean("usbDebuggingEnabled");
        return state.put("requestedEnabled", enabled).put("verified", verified)
                .put("pendingUserAction", enabled && !state.getBoolean("wirelessDebuggingEnabled"));
    }

    static JSONObject authorize(Context context) throws JSONException {
        if (!hasPermission(context)) {
            JSONObject result = PicoAdbManager.get(context).execute(new JSONObject()
                    .put("command", "pm grant " + context.getPackageName() + " " + PERMISSION)
                    .put("timeoutMs", 30000).put("maxOutputBytes", 2048), true, 0L);
            if (!result.optBoolean("success")) return result.put("permissionGranted", false);
        }
        return status(context).put("success", hasPermission(context));
    }

    private static boolean read(Context context, String key) {
        return Settings.Global.getInt(context.getContentResolver(), key, 0) != 0;
    }

    private static void write(Context context, String key, boolean enabled) {
        if (!Settings.Global.putInt(context.getContentResolver(), key, enabled ? 1 : 0))
            throw new IllegalStateException("Android rejected setting " + key);
    }
}
