package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class McpNotificationListenerService extends NotificationListenerService {
    private static volatile McpNotificationListenerService activeInstance;

    @Override
    public void onListenerConnected() {
        activeInstance = this;
        super.onListenerConnected();
    }

    @Override
    public void onListenerDisconnected() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onListenerDisconnected();
    }

    static boolean hasAccess(Context context) {
        Set<String> enabled = NotificationManagerCompat.getEnabledListenerPackages(context);
        return enabled.contains(context.getPackageName());
    }

    static void requestReconnect(Context context) {
        try {
            requestRebind(new ComponentName(context, McpNotificationListenerService.class));
        } catch (Throwable ignored) {
        }
    }

    static JSONObject list(Context context, int limit, boolean includeOwn, long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        List<StatusBarNotification> items = activeNotifications();
        items.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        JSONArray notifications = new JSONArray();
        for (StatusBarNotification sbn : items) {
            if (!includeOwn && context.getPackageName().equals(sbn.getPackageName())) {
                continue;
            }
            notifications.put(toJson(context, sbn));
            if (notifications.length() >= limit) {
                break;
            }
        }
        return new JSONObject()
                .put("available", true)
                .put("accessGranted", true)
                .put("listenerConnected", activeInstance != null)
                .put("notifications", notifications)
                .put("count", notifications.length())
                .put("toolCallCount", callCount);
    }

    static JSONObject get(Context context, String key, long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        for (StatusBarNotification sbn : activeNotifications()) {
            if (key.equals(sbn.getKey())) {
                return new JSONObject()
                        .put("available", true)
                        .put("notification", toJson(context, sbn))
                        .put("toolCallCount", callCount);
            }
        }
        return new JSONObject()
                .put("available", true)
                .put("found", false)
                .put("key", key)
                .put("toolCallCount", callCount);
    }

    static JSONObject dismiss(Context context, String key, long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        McpNotificationListenerService instance = activeInstance;
        if (instance == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("accessGranted", true)
                    .put("listenerConnected", false)
                    .put("requiresSetup", true)
                    .put("message", "Notification access is granted but Android has not connected the listener yet")
                    .put("toolCallCount", callCount);
        }
        boolean found = false;
        boolean clearable = false;
        for (StatusBarNotification sbn : activeNotifications()) {
            if (key.equals(sbn.getKey())) {
                found = true;
                clearable = sbn.isClearable();
                break;
            }
        }
        if (found && clearable) {
            instance.cancelNotification(key);
        }
        return new JSONObject()
                .put("available", true)
                .put("found", found)
                .put("clearable", clearable)
                .put("dismissed", found && clearable)
                .put("key", key)
                .put("toolCallCount", callCount);
    }

    private static JSONObject availability(Context context, long callCount) throws JSONException {
        boolean granted = hasAccess(context);
        if (!granted) {
            return new JSONObject()
                    .put("available", false)
                    .put("accessGranted", false)
                    .put("listenerConnected", false)
                    .put("requiresSetup", true)
                    .put("setupAction", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .put("message", "Enable Notification access for MCPocket in Android settings")
                    .put("toolCallCount", callCount);
        }
        if (activeInstance == null) {
            requestReconnect(context);
            return new JSONObject()
                    .put("available", false)
                    .put("accessGranted", true)
                    .put("listenerConnected", false)
                    .put("requiresSetup", true)
                    .put("message", "Notification access is enabled; waiting for Android to bind the listener")
                    .put("toolCallCount", callCount);
        }
        return new JSONObject()
                .put("available", true)
                .put("accessGranted", true)
                .put("listenerConnected", true)
                .put("toolCallCount", callCount);
    }

    private static List<StatusBarNotification> activeNotifications() {
        McpNotificationListenerService instance = activeInstance;
        List<StatusBarNotification> result = new ArrayList<>();
        if (instance == null) {
            return result;
        }
        StatusBarNotification[] active = instance.getActiveNotifications();
        if (active != null) {
            java.util.Collections.addAll(result, active);
        }
        return result;
    }

    private static JSONObject toJson(Context context, StatusBarNotification sbn) throws JSONException {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        String title = extras == null ? "" : chars(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = extras == null ? "" : chars(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = extras == null ? "" : chars(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String subText = extras == null ? "" : chars(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String appLabel = sbn.getPackageName();
        try {
            appLabel = context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager().getApplicationInfo(sbn.getPackageName(), 0))
                    .toString();
        } catch (Exception ignored) {
        }
        return new JSONObject()
                .put("key", sbn.getKey())
                .put("packageName", sbn.getPackageName())
                .put("appLabel", appLabel)
                .put("id", sbn.getId())
                .put("tag", sbn.getTag() == null ? JSONObject.NULL : sbn.getTag())
                .put("postTime", sbn.getPostTime())
                .put("title", title)
                .put("text", text)
                .put("bigText", bigText)
                .put("subText", subText)
                .put("clearable", sbn.isClearable())
                .put("ongoing", notification != null && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .put("category", notification == null || notification.category == null
                        ? JSONObject.NULL
                        : notification.category);
    }

    private static String chars(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
