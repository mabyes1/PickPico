package com.mcpocket.poc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AndroidAgentActions {
    private static final String ACTION_CHANNEL_ID = "mcpocket_agent_actions_v2";
    private static final AtomicInteger ACTION_NOTIFICATION_IDS = new AtomicInteger(9400);

    private AndroidAgentActions() {
    }

    static JSONObject appList(Context context, JSONObject arguments, long callCount) throws JSONException {
        PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcher, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String query = arguments.optString("query", "").trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(300, arguments.optInt("limit", 100)));
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (!seen.add(packageName)) {
                continue;
            }
            String label = info.loadLabel(packageManager).toString();
            if (!query.isEmpty()
                    && !label.toLowerCase(Locale.ROOT).contains(query)
                    && !packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            apps.add(new AppEntry(label, packageName));
        }
        apps.sort(Comparator.comparing(entry -> entry.label.toLowerCase(Locale.ROOT)));
        JSONArray result = new JSONArray();
        for (AppEntry app : apps) {
            result.put(new JSONObject()
                    .put("label", app.label)
                    .put("packageName", app.packageName));
            if (result.length() >= limit) {
                break;
            }
        }
        return new JSONObject()
                .put("apps", result)
                .put("count", result.length())
                .put("query", query)
                .put("toolCallCount", callCount);
    }

    static JSONObject appLaunch(Context context, JSONObject arguments, long callCount) throws JSONException {
        String packageName = arguments.optString("packageName", "");
        PackageManager packageManager = context.getPackageManager();
        Intent launch = packageManager.getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : packageManager.queryIntentActivities(launcher, 0)) {
                if (info.activityInfo != null && packageName.equals(info.activityInfo.packageName)) {
                    launch = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(packageName, info.activityInfo.name));
                    break;
                }
            }
        }
        if (launch == null) {
            return new JSONObject()
                    .put("launched", false)
                    .put("packageName", packageName)
                    .put("error", "No launchable activity found")
                    .put("toolCallCount", callCount);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!AgentAttention.canStartActivityNow(context)) {
            AgentInboxStore.add(context, "app.launch", "Open app", packageName);
            int notificationId = postActionNotification(
                    context,
                    launch,
                    "Open app",
                    packageName);
            return new JSONObject()
                    .put("launched", false)
                    .put("packageName", packageName)
                    .put("requiresUserAction", true)
                    .put("delivery", notificationId > 0 ? "notification+inbox" : "inbox")
                    .put("notificationId", notificationId > 0 ? notificationId : JSONObject.NULL)
                    .put("toolCallCount", callCount);
        }
        try {
            context.startActivity(launch);
            return new JSONObject()
                    .put("launched", true)
                    .put("packageName", packageName)
                    .put("toolCallCount", callCount);
        } catch (RuntimeException error) {
            return new JSONObject()
                    .put("launched", false)
                    .put("packageName", packageName)
                    .put("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                    .put("toolCallCount", callCount);
        }
    }

    static JSONObject urlOpen(Context context, JSONObject arguments, long callCount) throws JSONException {
        String raw = arguments.optString("url", "").trim();
        Uri uri = Uri.parse(raw);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            return new JSONObject()
                    .put("opened", false)
                    .put("url", raw)
                    .put("error", "URI scheme is required")
                    .put("toolCallCount", callCount);
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if ("file".equals(normalized)
                || "content".equals(normalized)
                || "javascript".equals(normalized)
                || "data".equals(normalized)
                || "intent".equals(normalized)) {
            return new JSONObject()
                    .put("opened", false)
                    .put("url", raw)
                    .put("scheme", normalized)
                    .put("error", "Unsafe URI scheme is blocked")
                    .put("toolCallCount", callCount);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!AgentAttention.canStartActivityNow(context)) {
            AgentInboxStore.add(context, "url.open", "Open URL", raw);
            int notificationId = postActionNotification(
                    context,
                    intent,
                    "Open link",
                    raw);
            return new JSONObject()
                    .put("opened", false)
                    .put("url", raw)
                    .put("scheme", normalized)
                    .put("requiresUserAction", true)
                    .put("delivery", notificationId > 0 ? "notification+inbox" : "inbox")
                    .put("notificationId", notificationId > 0 ? notificationId : JSONObject.NULL)
                    .put("toolCallCount", callCount);
        }
        try {
            context.startActivity(intent);
            return new JSONObject()
                    .put("opened", true)
                    .put("url", raw)
                    .put("scheme", normalized)
                    .put("toolCallCount", callCount);
        } catch (ActivityNotFoundException | SecurityException error) {
            return new JSONObject()
                    .put("opened", false)
                    .put("url", raw)
                    .put("scheme", normalized)
                    .put("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                    .put("toolCallCount", callCount);
        }
    }

    static JSONObject locationGet(Context context, JSONObject arguments, long callCount) throws JSONException {
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            return new JSONObject()
                    .put("available", false)
                    .put("requiresSetup", true)
                    .put("permission", "location")
                    .put("message", "Grant location permission to PickPico")
                    .put("toolCallCount", callCount);
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("error", "LocationManager unavailable")
                    .put("toolCallCount", callCount);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled()) {
            return new JSONObject()
                    .put("available", false)
                    .put("requiresSetup", true)
                    .put("locationEnabled", false)
                    .put("message", "Turn on Android location services")
                    .put("toolCallCount", callCount);
        }

        boolean highAccuracy = arguments.optBoolean("highAccuracy", true);
        int timeoutMs = arguments.optInt("timeoutMs", 7000);
        String provider = chooseProvider(manager, highAccuracy && fine);
        if (provider == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("error", "No enabled location provider")
                    .put("toolCallCount", callCount);
        }

        AtomicReference<Location> current = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        long started = SystemClock.elapsedRealtime();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CancellationSignal signal = new CancellationSignal();
                manager.getCurrentLocation(provider, signal, context.getMainExecutor(), location -> {
                    current.set(location);
                    latch.countDown();
                });
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    signal.cancel();
                }
            } else {
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        current.set(location);
                        latch.countDown();
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String provider) {
                    }

                    @Override
                    public void onProviderDisabled(String provider) {
                    }
                };
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                manager.removeUpdates(listener);
            }
        } catch (SecurityException error) {
            return new JSONObject()
                    .put("available", false)
                    .put("requiresSetup", true)
                    .put("error", "Location permission rejected by Android")
                    .put("toolCallCount", callCount);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        boolean fresh = current.get() != null;
        Location location = current.get();
        if (location == null) {
            location = bestLastKnown(manager);
        }
        if (location == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("timedOut", true)
                    .put("provider", provider)
                    .put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("toolCallCount", callCount);
        }

        JSONObject result = new JSONObject()
                .put("available", true)
                .put("fresh", fresh)
                .put("latitude", location.getLatitude())
                .put("longitude", location.getLongitude())
                .put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL)
                .put("provider", location.getProvider())
                .put("timestampMs", location.getTime())
                .put("ageMs", Math.max(0L, System.currentTimeMillis() - location.getTime()))
                .put("permission", fine ? "fine" : "coarse")
                .put("durationMs", SystemClock.elapsedRealtime() - started)
                .put("toolCallCount", callCount);
        if (location.hasAltitude()) {
            result.put("altitudeMeters", location.getAltitude());
        }
        if (location.hasSpeed()) {
            result.put("speedMetersPerSecond", location.getSpeed());
        }
        if (location.hasBearing()) {
            result.put("bearingDegrees", location.getBearing());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            result.put("mock", location.isFromMockProvider());
        }
        return result;
    }

    static JSONObject clipboardGet(Context context, long callCount) throws JSONException {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return new JSONObject()
                    .put("available", false)
                    .put("error", "ClipboardManager unavailable")
                    .put("toolCallCount", callCount);
        }
        try {
            if (!clipboard.hasPrimaryClip()) {
                return new JSONObject()
                        .put("available", true)
                        .put("hasClip", false)
                        .put("note", "Android 10+ may hide clipboard contents from apps that are not currently focused")
                        .put("toolCallCount", callCount);
            }
            ClipData clip = clipboard.getPrimaryClip();
            ClipDescription description = clipboard.getPrimaryClipDescription();
            if (clip == null || clip.getItemCount() == 0) {
                return new JSONObject()
                        .put("available", true)
                        .put("hasClip", false)
                        .put("toolCallCount", callCount);
            }
            CharSequence coerced = clip.getItemAt(0).coerceToText(context);
            JSONArray mimeTypes = new JSONArray();
            if (description != null) {
                for (int index = 0; index < description.getMimeTypeCount(); index++) {
                    mimeTypes.put(description.getMimeType(index));
                }
            }
            return new JSONObject()
                    .put("available", true)
                    .put("hasClip", true)
                    .put("text", coerced == null ? "" : coerced.toString())
                    .put("mimeTypes", mimeTypes)
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return new JSONObject()
                    .put("available", false)
                    .put("androidRestricted", true)
                    .put("error", error.getMessage())
                    .put("message", "Android only exposes clipboard reads to the focused app or approved system roles")
                    .put("toolCallCount", callCount);
        }
    }

    static JSONObject clipboardSet(Context context, JSONObject arguments, long callCount) throws JSONException {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return new JSONObject()
                    .put("set", false)
                    .put("error", "ClipboardManager unavailable")
                    .put("toolCallCount", callCount);
        }
        String text = arguments.optString("text", "");
        String label = arguments.optString("label", "PickPico Agent");
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            return new JSONObject()
                    .put("set", true)
                    .put("length", text.length())
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return new JSONObject()
                    .put("set", false)
                    .put("androidRestricted", true)
                    .put("error", error.getMessage())
                    .put("toolCallCount", callCount);
        }
    }

    private static int postActionNotification(
            Context context,
            Intent target,
            String title,
            String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return -1;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return -1;
        }
        NotificationChannel channel = new NotificationChannel(
                ACTION_CHANNEL_ID,
                "PickPico actions",
                NotificationManager.IMPORTANCE_HIGH);
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);

        int notificationId = ACTION_NOTIFICATION_IDS.incrementAndGet();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, ACTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        AgentAttention.applyUrgentBehavior(context, builder, notificationId, target);
        Notification notification = builder.build();
        manager.notify(notificationId, notification);
        AgentAttention.alert(context);
        return notificationId;
    }

    private static String chooseProvider(LocationManager manager, boolean highAccuracy) {
        if (highAccuracy && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        return null;
    }

    private static Location bestLastKnown(LocationManager manager) {
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) {
                    continue;
                }
                if (best == null
                        || candidate.getTime() > best.getTime()
                        || (candidate.getTime() == best.getTime()
                        && candidate.hasAccuracy()
                        && (!best.hasAccuracy() || candidate.getAccuracy() < best.getAccuracy()))) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
