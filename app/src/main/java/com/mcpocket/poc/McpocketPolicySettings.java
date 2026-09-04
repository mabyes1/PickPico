package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Persistent user-owned policy settings for PickPico Agent control. */
final class McpocketPolicySettings {
    static final String PREFS = "mcpocket_policy";
    static final String KEY_HYPER_MODE = "hyper_mode";
    static final String KEY_APPROVAL_MODE = "approval_mode";

    static final String APPROVAL_ASK = "ask";
    static final String APPROVAL_AUTO = "auto_approve";
    static final String APPROVAL_YOLO = "yolo";

    private McpocketPolicySettings() {
    }

    static boolean isHyperModeEnabled(Context context) {
        return preferences(context).getBoolean(KEY_HYPER_MODE, false);
    }

    static void setHyperModeEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_HYPER_MODE, enabled).apply();
    }

    static String approvalMode(Context context) {
        String value = preferences(context).getString(KEY_APPROVAL_MODE, APPROVAL_AUTO);
        return isApprovalMode(value) ? value : APPROVAL_AUTO;
    }

    static void setApprovalMode(Context context, String mode) {
        if (!isApprovalMode(mode)) {
            throw new IllegalArgumentException("Unknown approval mode: " + mode);
        }
        preferences(context).edit().putString(KEY_APPROVAL_MODE, mode).apply();
    }

    static JSONObject status(Context context, long callCount) throws JSONException {
        String approval = approvalMode(context);
        return new JSONObject()
                .put("hyperMode", new JSONObject()
                        .put("enabled", isHyperModeEnabled(context))
                        .put("meaning", "Unlock Android special-access capability families and allow urgent Agent handoffs to request keyguard dismissal; secure authentication remains OS-owned."))
                .put("approvalMode", new JSONObject()
                        .put("value", approval)
                        .put("ask", APPROVAL_ASK.equals(approval))
                        .put("autoApprove", APPROVAL_AUTO.equals(approval))
                        .put("yolo", APPROVAL_YOLO.equals(approval))
                        .put("meaning", approvalMeaning(approval)))
                .put("osBoundariesStillApply", true)
                .put("toolCallCount", callCount);
    }

    static boolean isApprovalMode(String value) {
        return APPROVAL_ASK.equals(value)
                || APPROVAL_AUTO.equals(value)
                || APPROVAL_YOLO.equals(value);
    }

    private static String approvalMeaning(String value) {
        if (APPROVAL_ASK.equals(value)) {
            return "Ask the human before PickPico executes a command with side effects.";
        }
        if (APPROVAL_YOLO.equals(value)) {
            return "Do not add a PickPico approval prompt; Android, biometric, app, and sandbox boundaries still apply.";
        }
        return "Auto-approve ordinary operations, but ask the human before high-risk operations.";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
