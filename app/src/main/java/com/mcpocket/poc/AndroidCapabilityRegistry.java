package com.mcpocket.poc;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

/** Android runtime availability for command capabilities. */
final class AndroidCapabilityRegistry {
    private AndroidCapabilityRegistry() {
    }

    static JSONObject state(Context context, String commandId) throws JSONException {
        boolean hyper = isHyperCommand(commandId);
        JSONObject result = new JSONObject()
                .put("platform", "android")
                .put("group", hyper ? "hyper" : "core")
                .put("supported", true)
                .put("enabled", !hyper || McpocketPolicySettings.isHyperModeEnabled(context))
                .put("requiresSetup", false)
                .put("userInteractionRequired", false)
                .put("setupType", JSONObject.NULL);

        if (hyper && !McpocketPolicySettings.isHyperModeEnabled(context)) {
            return result
                    .put("available", false)
                    .put("state", "disabled")
                    .put("reason", "Hyper Mode is off");
        }

        if ("camera.capture".equals(commandId)) {
            return permissionState(context, result, Manifest.permission.CAMERA, "runtime_permission");
        }
        if ("microphone.record".equals(commandId)) {
            return permissionState(context, result, Manifest.permission.RECORD_AUDIO, "runtime_permission");
        }
        if ("location.get".equals(commandId)) {
            boolean granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    || hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
            return setupState(result, granted, "runtime_permission", "Location permission is not granted");
        }
        if ("contacts.search".equals(commandId) || "contacts.get".equals(commandId)) {
            return permissionState(context, result, Manifest.permission.READ_CONTACTS, "runtime_permission");
        }
        if ("calendar.list".equals(commandId) || "calendar.get".equals(commandId)) {
            return permissionState(context, result, Manifest.permission.READ_CALENDAR, "runtime_permission");
        }
        if ("calendar.create".equals(commandId)
                || "calendar.update".equals(commandId)
                || "calendar.delete".equals(commandId)) {
            boolean granted = hasPermission(context, Manifest.permission.READ_CALENDAR)
                    && hasPermission(context, Manifest.permission.WRITE_CALENDAR);
            return setupState(
                    result,
                    granted,
                    "runtime_permission",
                    "Calendar read/write permissions are not granted");
        }
        if ("file.pick".equals(commandId) || "media.pick".equals(commandId) || "share.send".equals(commandId)) {
            return result
                    .put("available", true)
                    .put("state", "available")
                    .put("userInteractionRequired", true);
        }
        if ("phone.notify".equals(commandId) || "human.help".equals(commandId)) {
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS);
            return setupState(result, granted, "notification_permission", "Notification permission is not granted");
        }
        if ("notification.list".equals(commandId)
                || "notification.get".equals(commandId)
                || "notification.dismiss".equals(commandId)
                || "notification.actions".equals(commandId)
                || "notification.invoke_action".equals(commandId)
                || "notification.reply".equals(commandId)) {
            return setupState(
                    result,
                    McpNotificationListenerService.hasAccess(context),
                    "notification_listener",
                    "Android Notification Listener access is not enabled");
        }
        if ("ui.inspect".equals(commandId)
                || "ui.action".equals(commandId)
                || "ui.type".equals(commandId)
                || "ui.scroll".equals(commandId)) {
            return setupState(
                    result,
                    McpAccessibilityService.hasAccess(context),
                    "accessibility_service",
                    "PickPico Accessibility Service is not enabled");
        }
        if ("screen.capture".equals(commandId)) {
            return setupState(
                    result,
                    ScreenCaptureService.isActive(),
                    "media_projection",
                    "No user-authorized MediaProjection screen-capture session is active");
        }
        if ("phone.lock".equals(commandId)) {
            DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName receiver = new ComponentName(context, McpDeviceAdminReceiver.class);
            boolean granted = manager != null && manager.isAdminActive(receiver);
            return setupState(result, granted, "device_admin", "PickPico is not an active Device Admin");
        }

        return result
                .put("available", true)
                .put("state", "available");
    }

    static boolean isCommandExposed(Context context, String commandId) {
        try {
            return state(context, commandId).optBoolean("available", false);
        } catch (JSONException error) {
            return false;
        }
    }

    static boolean isHyperCommand(String commandId) {
        return "phone.lock".equals(commandId)
                || "phone.home".equals(commandId)
                || "notification.list".equals(commandId)
                || "notification.get".equals(commandId)
                || "notification.dismiss".equals(commandId)
                || "notification.actions".equals(commandId)
                || "notification.invoke_action".equals(commandId)
                || "notification.reply".equals(commandId)
                || "ui.inspect".equals(commandId)
                || "ui.action".equals(commandId)
                || "ui.type".equals(commandId)
                || "ui.scroll".equals(commandId)
                || "screen.capture".equals(commandId);
    }

    private static JSONObject permissionState(
            Context context,
            JSONObject result,
            String permission,
            String setupType) throws JSONException {
        return setupState(
                result,
                hasPermission(context, permission),
                setupType,
                permission + " is not granted");
    }

    private static JSONObject setupState(
            JSONObject result,
            boolean available,
            String setupType,
            String reason) throws JSONException {
        if (available) {
            return result
                    .put("available", true)
                    .put("state", "available");
        }
        return result
                .put("available", false)
                .put("state", "setup_required")
                .put("requiresSetup", true)
                .put("setupType", setupType)
                .put("userInteractionRequired", true)
                .put("reason", reason);
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
