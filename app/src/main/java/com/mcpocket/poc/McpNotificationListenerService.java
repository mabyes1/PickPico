package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

    static JSONObject actions(Context context, String key, long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        StatusBarNotification sbn = findNotification(key);
        if (sbn == null) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", false)
                    .put("key", key)
                    .put("actions", new JSONArray())
                    .put("count", 0)
                    .put("toolCallCount", callCount);
        }
        JSONArray items = actionDescriptors(sbn.getNotification());
        return new JSONObject()
                .put("available", true)
                .put("found", true)
                .put("key", key)
                .put("actions", items)
                .put("count", items.length())
                .put("toolCallCount", callCount);
    }

    static JSONObject invokeAction(Context context, String key, int actionIndex, long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        StatusBarNotification sbn = findNotification(key);
        Notification.Action action = actionAt(sbn, actionIndex);
        if (action == null) {
            return actionNotFound(key, actionIndex, callCount);
        }
        if (action.actionIntent == null) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("invoked", false)
                    .put("errorCode", "notification_action_has_no_intent")
                    .put("key", key)
                    .put("actionIndex", actionIndex)
                    .put("toolCallCount", callCount);
        }
        try {
            action.actionIntent.send();
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("invoked", true)
                    .put("key", key)
                    .put("actionIndex", actionIndex)
                    .put("title", chars(action.title))
                    .put("toolCallCount", callCount);
        } catch (PendingIntent.CanceledException error) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("invoked", false)
                    .put("errorCode", "notification_action_cancelled")
                    .put("message", error.getMessage())
                    .put("key", key)
                    .put("actionIndex", actionIndex)
                    .put("toolCallCount", callCount);
        }
    }

    static JSONObject reply(
            Context context,
            String key,
            Integer requestedActionIndex,
            String text,
            long callCount) throws JSONException {
        JSONObject availability = availability(context, callCount);
        if (!availability.optBoolean("available")) {
            return availability;
        }
        StatusBarNotification sbn = findNotification(key);
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().actions == null) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", false)
                    .put("supportsReply", false)
                    .put("replied", false)
                    .put("key", key)
                    .put("toolCallCount", callCount);
        }

        Notification.Action selected = null;
        int selectedIndex = -1;
        Notification.Action[] notificationActions = sbn.getNotification().actions;
        if (requestedActionIndex != null) {
            if (requestedActionIndex >= 0 && requestedActionIndex < notificationActions.length) {
                Notification.Action candidate = notificationActions[requestedActionIndex];
                RemoteInput[] inputs = candidate == null ? null : candidate.getRemoteInputs();
                if (inputs != null && inputs.length > 0) {
                    selected = candidate;
                    selectedIndex = requestedActionIndex;
                }
            }
        } else {
            for (int index = 0; index < notificationActions.length; index++) {
                Notification.Action candidate = notificationActions[index];
                RemoteInput[] inputs = candidate == null ? null : candidate.getRemoteInputs();
                if (inputs != null && inputs.length > 0) {
                    selected = candidate;
                    selectedIndex = index;
                    break;
                }
            }
        }

        if (selected == null || selected.actionIntent == null) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("supportsReply", false)
                    .put("replied", false)
                    .put("key", key)
                    .put("actionIndex", requestedActionIndex == null ? JSONObject.NULL : requestedActionIndex)
                    .put("toolCallCount", callCount);
        }

        RemoteInput[] remoteInputs = selected.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : remoteInputs) {
            results.putCharSequence(input.getResultKey(), text);
        }
        Intent fillIn = new Intent();
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
        }
        try {
            selected.actionIntent.send(context, 0, fillIn);
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("supportsReply", true)
                    .put("replied", true)
                    .put("key", key)
                    .put("actionIndex", selectedIndex)
                    .put("title", chars(selected.title))
                    .put("textCharacters", text.length())
                    .put("toolCallCount", callCount);
        } catch (PendingIntent.CanceledException error) {
            return new JSONObject()
                    .put("available", true)
                    .put("found", true)
                    .put("supportsReply", true)
                    .put("replied", false)
                    .put("errorCode", "notification_reply_cancelled")
                    .put("message", error.getMessage())
                    .put("key", key)
                    .put("actionIndex", selectedIndex)
                    .put("toolCallCount", callCount);
        }
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

    private static StatusBarNotification findNotification(String key) {
        for (StatusBarNotification sbn : activeNotifications()) {
            if (key.equals(sbn.getKey())) {
                return sbn;
            }
        }
        return null;
    }

    private static Notification.Action actionAt(StatusBarNotification sbn, int actionIndex) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().actions == null) {
            return null;
        }
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actionIndex < 0 || actionIndex >= actions.length) {
            return null;
        }
        return actions[actionIndex];
    }

    private static JSONArray actionDescriptors(Notification notification) throws JSONException {
        JSONArray result = new JSONArray();
        if (notification == null || notification.actions == null) {
            return result;
        }
        for (int index = 0; index < notification.actions.length; index++) {
            Notification.Action action = notification.actions[index];
            if (action == null) {
                continue;
            }
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            JSONArray inputs = new JSONArray();
            if (remoteInputs != null) {
                for (RemoteInput input : remoteInputs) {
                    JSONArray choices = new JSONArray();
                    CharSequence[] rawChoices = input.getChoices();
                    if (rawChoices != null) {
                        for (CharSequence choice : rawChoices) {
                            choices.put(chars(choice));
                        }
                    }
                    inputs.put(new JSONObject()
                            .put("resultKey", input.getResultKey())
                            .put("label", chars(input.getLabel()))
                            .put("allowFreeFormInput", input.getAllowFreeFormInput())
                            .put("choices", choices));
                }
            }
            result.put(new JSONObject()
                    .put("index", index)
                    .put("title", chars(action.title))
                    .put("semanticAction", action.getSemanticAction())
                    .put("allowsGeneratedReplies", action.getAllowGeneratedReplies())
                    .put("hasIntent", action.actionIntent != null)
                    .put("supportsReply", inputs.length() > 0)
                    .put("remoteInputs", inputs));
        }
        return result;
    }

    private static JSONObject actionNotFound(String key, int actionIndex, long callCount) throws JSONException {
        return new JSONObject()
                .put("available", true)
                .put("found", false)
                .put("invoked", false)
                .put("errorCode", "notification_action_not_found")
                .put("key", key)
                .put("actionIndex", actionIndex)
                .put("toolCallCount", callCount);
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
                .put("actionCount", notification == null || notification.actions == null ? 0 : notification.actions.length)
                .put("category", notification == null || notification.category == null
                        ? JSONObject.NULL
                        : notification.category);
    }

    private static String chars(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
