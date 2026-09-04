package com.mcpocket.poc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent handoff between MCP command threads and the Android system picker UI. */
final class PickerRequestStore {
    static final String EXTRA_REQUEST_ID = "pickerRequestId";
    private static final String PREFS = "pickpico_picker_requests";
    private static final String KEY_PREFIX = "request:";
    private static final String CHANNEL_ID = "pickpico_picker_v2";
    private static final long MAX_NATIVE_MEDIA_TOTAL_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_NATIVE_MEDIA_ITEM_BYTES = 5L * 1024L * 1024L;
    private static final AtomicInteger NOTIFICATION_IDS = new AtomicInteger(9800);

    private PickerRequestStore() {
    }

    static JSONObject createAndWait(
            Context context,
            JSONObject arguments,
            boolean media,
            long callCount) throws JSONException {
        String requestId = "pick-" + UUID.randomUUID();
        int timeoutSeconds = Math.max(30, Math.min(300, arguments.optInt("timeoutSeconds", 120)));
        long createdAt = System.currentTimeMillis();
        JSONObject request = new JSONObject()
                .put("requestId", requestId)
                .put("kind", media ? "media" : "file")
                .put("status", "waiting_human")
                .put("arguments", new JSONObject(arguments.toString()))
                .put("createdAt", Instant.now().toString())
                .put("createdAtEpochMs", createdAt)
                .put("expiresAtEpochMs", createdAt + timeoutSeconds * 1000L);
        save(context, request);

        Intent launch = new Intent(context, PickerActivity.class)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean launched = false;
        if (MainActivity.isUiVisible()) {
            try {
                context.startActivity(launch);
                launched = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!launched) {
            AgentInboxStore.add(
                    context,
                    media ? "media.pick" : "file.pick",
                    media ? "Choose media" : "Choose a file",
                    "PickPico is waiting for your selection");
            postPickerNotification(context, launch, media);
        }

        JSONObject current = request;
        while (isWaiting(current)) {
            if (System.currentTimeMillis() >= current.optLong("expiresAtEpochMs", 0L)) {
                current.put("status", "timed_out")
                        .put("completedAt", Instant.now().toString());
                save(context, current);
                break;
            }
            SystemClock.sleep(250L);
            JSONObject loaded = load(context, requestId);
            if (loaded == null) {
                current = null;
                break;
            }
            current = loaded;
        }

        if (current == null) {
            return new JSONObject()
                    .put("requestId", requestId)
                    .put("status", "failed")
                    .put("error", "picker request disappeared")
                    .put("toolCallCount", callCount);
        }
        return publicResult(context, current, callCount);
    }

    static synchronized JSONObject load(Context context, String requestId) throws JSONException {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + requestId, "");
        return raw.isEmpty() ? null : new JSONObject(raw);
    }

    static synchronized void markPicking(Context context, String requestId) throws JSONException {
        JSONObject request = load(context, requestId);
        if (request == null || !"waiting_human".equals(request.optString("status"))) {
            return;
        }
        request.put("status", "picking")
                .put("openedAt", Instant.now().toString());
        save(context, request);
    }

    static synchronized void complete(Context context, String requestId, Intent data)
            throws JSONException, IOException {
        JSONObject request = load(context, requestId);
        if (request == null || !isWaiting(request)) {
            return;
        }
        JSONObject arguments = request.optJSONObject("arguments");
        int maxItems = arguments == null ? 1 : Math.max(1, Math.min(10, arguments.optInt("maxItems", 5)));
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (data != null && data.getData() != null) {
            addUri(context, requestId, data.getData(), items, seen, maxItems);
        }
        ClipData clipData = data == null ? null : data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount() && items.length() < maxItems; index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null) {
                    addUri(context, requestId, uri, items, seen, maxItems);
                }
            }
        }
        request.put("status", items.length() == 0 ? "cancelled" : "completed")
                .put("items", items)
                .put("count", items.length())
                .put("completedAt", Instant.now().toString());
        save(context, request);
    }

    static synchronized void cancel(Context context, String requestId, String reason) throws JSONException {
        JSONObject request = load(context, requestId);
        if (request == null || !isWaiting(request)) {
            return;
        }
        request.put("status", "cancelled")
                .put("reason", reason == null ? "cancelled" : reason)
                .put("completedAt", Instant.now().toString());
        save(context, request);
    }

    private static void addUri(
            Context context,
            String requestId,
            Uri uri,
            JSONArray items,
            Set<String> seen,
            int maxItems) throws IOException, JSONException {
        if (items.length() >= maxItems || !seen.add(uri.toString())) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        String displayName = queryDisplayName(resolver, uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "selection-" + (items.length() + 1);
        }
        displayName = sanitizeFilename(displayName);

        File workspaceRoot = new File(context.getFilesDir(), "workspaces");
        File requestDir = new File(new File(workspaceRoot, "imports"), requestId);
        if (!requestDir.isDirectory() && !requestDir.mkdirs() && !requestDir.isDirectory()) {
            throw new IOException("Unable to create picker import directory");
        }
        File output = uniqueFile(requestDir, displayName);
        long bytes = 0L;
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream stream = new FileOutputStream(output)) {
            if (input == null) {
                throw new IOException("Android returned an unreadable picker URI");
            }
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                stream.write(buffer, 0, read);
                bytes += read;
            }
        }
        String mimeType = resolver.getType(uri);
        String relativePath = "imports/" + requestId + "/" + output.getName();
        items.put(new JSONObject()
                .put("name", output.getName())
                .put("path", relativePath)
                .put("mimeType", mimeType == null ? JSONObject.NULL : mimeType)
                .put("sizeBytes", bytes)
                .put("sourceUri", uri.toString()));
    }

    private static JSONObject publicResult(Context context, JSONObject request, long callCount) throws JSONException {
        JSONObject result = new JSONObject()
                .put("requestId", request.optString("requestId", ""))
                .put("kind", request.optString("kind", "file"))
                .put("status", request.optString("status", "unknown"))
                .put("toolCallCount", callCount);
        if (request.has("items")) {
            result.put("items", request.optJSONArray("items"));
            result.put("count", request.optInt("count", 0));
        }
        if (request.has("reason")) {
            result.put("reason", request.optString("reason", ""));
        }
        JSONObject arguments = request.optJSONObject("arguments");
        if ("media".equals(request.optString("kind", ""))
                && (arguments == null || arguments.optBoolean("returnContent", true))) {
            attachNativeImages(context, request.optJSONArray("items"), result);
        }
        return result;
    }

    private static void attachNativeImages(Context context, JSONArray items, JSONObject result) throws JSONException {
        if (items == null || items.length() == 0) {
            return;
        }
        JSONArray content = new JSONArray();
        JSONArray skipped = new JSONArray();
        long totalBytes = 0L;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String mimeType = item.optString("mimeType", "");
            if (!mimeType.startsWith("image/")) {
                continue;
            }
            long sizeBytes = item.optLong("sizeBytes", 0L);
            if (sizeBytes <= 0L
                    || sizeBytes > MAX_NATIVE_MEDIA_ITEM_BYTES
                    || totalBytes + sizeBytes > MAX_NATIVE_MEDIA_TOTAL_BYTES) {
                skipped.put(new JSONObject()
                        .put("path", item.optString("path", ""))
                        .put("reason", "image_too_large_for_inline_mcp_content")
                        .put("sizeBytes", sizeBytes));
                continue;
            }
            File file = new File(new File(context.getFilesDir(), "workspaces"), item.optString("path", ""));
            try {
                byte[] bytes = readBytes(file, (int) sizeBytes);
                content.put(new JSONObject()
                        .put("type", "image")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("mimeType", mimeType));
                totalBytes += bytes.length;
            } catch (IOException error) {
                skipped.put(new JSONObject()
                        .put("path", item.optString("path", ""))
                        .put("reason", "unable_to_read_imported_image"));
            }
        }
        if (content.length() > 0) {
            result.put("_mcpContent", content);
        }
        if (skipped.length() > 0) {
            result.put("nativeContentSkipped", skipped);
        }
    }

    private static byte[] readBytes(File file, int expectedBytes) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, expectedBytes))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_NATIVE_MEDIA_ITEM_BYTES) {
                    throw new IOException("Imported image exceeds inline MCP limit");
                }
            }
            return output.toByteArray();
        }
    }

    private static synchronized void save(Context context, JSONObject request) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(KEY_PREFIX + request.optString("requestId", ""), request.toString())
                .apply();
    }

    private static boolean isWaiting(JSONObject request) {
        if (request == null) {
            return false;
        }
        String status = request.optString("status", "");
        return "waiting_human".equals(status) || "picking".equals(status);
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    private static File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 1000; index++) {
            candidate = new File(directory, base + "-" + index + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, UUID.randomUUID() + extension);
    }

    private static String sanitizeFilename(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "selection";
        }
        return sanitized.length() > 180 ? sanitized.substring(0, 180) : sanitized;
    }

    private static void postPickerNotification(Context context, Intent target, boolean media) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PickPico picker requests",
                NotificationManager.IMPORTANCE_HIGH);
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);
        int notificationId = NOTIFICATION_IDS.incrementAndGet();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(media ? "PickPico needs media" : "PickPico needs a file")
                .setContentText("Tap to choose from Android")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        AgentAttention.applyUrgentBehavior(context, builder, notificationId);
        Notification notification = builder.build();
        manager.notify(notificationId, notification);
        AgentAttention.alert(context);
    }
}
