package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HumanHelpStore {
    static final String EXTRA_REQUEST_ID = "humanHelpRequestId";
    private static final String PREFS = "mcpocket_human_help";
    private static final String KEY_PREFIX = "request:";
    private static final String CHANNEL_ID = "mcpocket_human_help_v2";
    private static final int MAX_INLINE_ATTACHMENT_BYTES = 2_500_000;
    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 180;
    private static final long MAX_STORAGE_BYTES = 100L * 1024L * 1024L;
    private static final long TARGET_STORAGE_BYTES = 80L * 1024L * 1024L;

    private HumanHelpStore() {
    }

    /** Creates a non-blocking Human Help sample used only by the preview build UI. */
    static synchronized String createPreviewRequest(Context context) throws JSONException {
        cleanupStorageIfNeeded(context);
        String requestId = "preview-" + UUID.randomUUID();
        long createdAtEpochMs = System.currentTimeMillis();
        int idleTimeoutSeconds = 360;
        JSONObject request = new JSONObject()
                .put("requestId", requestId)
                .put("requestType", "approval")
                .put("status", "waiting_human")
                .put("title", "Physical Inspection Required")
                .put("instruction", "Codex needs a live camera view to identify the device in front of you and continue.")
                .put("actions", new JSONArray().put("Approve").put("Reject").put("Voice"))
                .put("allowTextReply", true)
                .put("allowImages", true)
                .put("maxImages", 3)
                .put("attachments", new JSONArray())
                .put("createdAt", Instant.now().toString())
                .put("createdAtEpochMs", createdAtEpochMs)
                .put("idleTimeoutSeconds", idleTimeoutSeconds)
                .put("expiresAtEpochMs", createdAtEpochMs + idleTimeoutSeconds * 1000L)
                .put("openGraceUsed", false)
                .put("preview", true);
        save(context, request);
        return requestId;
    }

    static JSONObject createAndWait(Context context, JSONObject arguments, long callCount) throws JSONException {
        cleanupStorageIfNeeded(context);
        String requestId = "hh-" + UUID.randomUUID();
        String requestType = "approval".equals(arguments.optString("requestType", "help"))
                ? "approval"
                : "help";
        JSONArray actions = sanitizedActions(arguments.optJSONArray("actions"));
        int idleTimeoutSeconds = arguments.optInt("idleTimeoutSeconds", DEFAULT_IDLE_TIMEOUT_SECONDS);
        long createdAtEpochMs = System.currentTimeMillis();
        JSONObject request = new JSONObject()
                .put("requestId", requestId)
                .put("requestType", requestType)
                .put("status", "waiting_human")
                .put("title", arguments.optString("title", "AI needs your help"))
                .put("instruction", arguments.optString("instruction", ""))
                .put("actions", actions)
                .put("allowTextReply", arguments.optBoolean("allowTextReply", true))
                .put("allowImages", arguments.optBoolean("allowImages", true))
                .put("maxImages", arguments.optInt("maxImages", 3))
                .put("attachments", new JSONArray())
                .put("createdAt", Instant.now().toString())
                .put("createdAtEpochMs", createdAtEpochMs)
                .put("idleTimeoutSeconds", idleTimeoutSeconds)
                .put("expiresAtEpochMs", createdAtEpochMs + idleTimeoutSeconds * 1000L)
                .put("openGraceUsed", false);
        AgentInboxStore.add(
                context,
                "approval".equals(requestType) ? "human.approval" : "human.help",
                request.optString("title", "AI needs your help"),
                request.optString("instruction", ""));
        save(context, request);
        postRequestNotification(context, request);

        JSONObject current = request;
        while ("waiting_human".equals(current.optString("status"))) {
            SystemClock.sleep(250L);
            current = load(context, requestId);
            if (current == null) {
                break;
            }
        }

        if (current == null) {
            return new JSONObject()
                    .put("requestId", requestId)
                    .put("status", "failed")
                    .put("error", "human-help request disappeared")
                    .put("toolCallCount", callCount);
        }
        return publicResult(context, current, true, callCount);
    }

    static JSONObject status(Context context, String requestId, boolean includeAttachmentData, long callCount)
            throws JSONException {
        JSONObject request = load(context, requestId);
        if (request == null) {
            return new JSONObject()
                    .put("requestId", requestId)
                    .put("status", "not_found")
                    .put("toolCallCount", callCount);
        }
        return publicResult(context, request, includeAttachmentData, callCount);
    }

    static synchronized JSONObject load(Context context, String requestId) throws JSONException {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + requestId, "");
        if (raw.isEmpty()) {
            return null;
        }
        JSONObject request = new JSONObject(raw);
        expireIfNeeded(context, request);
        return request;
    }

    static synchronized void save(Context context, JSONObject request) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREFIX + request.optString("requestId"), request.toString())
                .apply();
    }

    static synchronized JSONObject addJpegAttachment(
            Context context,
            String requestId,
            InputStream jpegInput,
            String source) throws IOException, JSONException {
        cleanupStorageIfNeeded(context);
        JSONObject request = requireWaiting(context, requestId);
        JSONArray attachments = request.optJSONArray("attachments");
        if (attachments == null) {
            attachments = new JSONArray();
            request.put("attachments", attachments);
        }
        int maxImages = request.optInt("maxImages", 3);
        if (attachments.length() >= maxImages) {
            throw new IOException("Maximum image count reached");
        }

        File dir = requestDirectory(context, requestId);
        String filename = String.format("image-%02d.jpg", attachments.length() + 1);
        File output = new File(dir, filename);
        long bytes = copy(jpegInput, output);
        cleanupStorageIfNeeded(context);
        if (storageBytes(context) > MAX_STORAGE_BYTES) {
            output.delete();
            throw new IOException("Human-help storage limit reached; finish older requests before adding more images");
        }
        String relativePath = "human-help/" + requestId + "/" + filename;
        JSONObject attachment = new JSONObject()
                .put("type", "image")
                .put("mimeType", "image/jpeg")
                .put("source", source == null ? "unknown" : source)
                .put("path", relativePath)
                .put("sizeBytes", bytes);
        attachments.put(attachment);
        save(context, request);
        return new JSONObject(attachment.toString());
    }

    static synchronized JSONObject addAudioAttachment(
            Context context,
            String requestId,
            byte[] audioBytes,
            String extension,
            String mimeType,
            String source) throws IOException, JSONException {
        cleanupStorageIfNeeded(context);
        JSONObject request = requireWaiting(context, requestId);
        JSONArray attachments = request.optJSONArray("attachments");
        if (attachments == null) {
            attachments = new JSONArray();
            request.put("attachments", attachments);
        }

        int audioIndex = 1;
        for (int index = 0; index < attachments.length(); index++) {
            if ("audio".equals(attachments.optJSONObject(index) == null
                    ? ""
                    : attachments.optJSONObject(index).optString("type", ""))) {
                audioIndex++;
            }
        }
        File dir = requestDirectory(context, requestId);
        String safeExtension = extension == null || extension.isEmpty() ? "m4a" : extension;
        String safeMimeType = mimeType == null || mimeType.isEmpty() ? "audio/mp4" : mimeType;
        String filename = String.format("voice-%02d.%s", audioIndex, safeExtension);
        File output = new File(dir, filename);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(audioBytes);
        }
        cleanupStorageIfNeeded(context);
        if (storageBytes(context) > MAX_STORAGE_BYTES) {
            output.delete();
            throw new IOException("Human-help storage limit reached; finish older requests before adding audio");
        }
        String relativePath = "human-help/" + requestId + "/" + filename;
        JSONObject attachment = new JSONObject()
                .put("type", "audio")
                .put("mimeType", safeMimeType)
                .put("source", source == null ? "unknown" : source)
                .put("path", relativePath)
                .put("sizeBytes", audioBytes.length);
        attachments.put(attachment);
        save(context, request);
        return new JSONObject(attachment.toString());
    }

    static synchronized boolean removeAttachment(Context context, String requestId, String relativePath)
            throws JSONException {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        JSONObject request = requireWaiting(context, requestId);
        JSONArray attachments = request.optJSONArray("attachments");
        if (attachments == null || attachments.length() == 0) {
            return false;
        }
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < attachments.length(); index++) {
            JSONObject attachment = attachments.optJSONObject(index);
            if (!removed && attachment != null && relativePath.equals(attachment.optString("path", ""))) {
                removed = true;
                continue;
            }
            if (attachment != null) {
                kept.put(attachment);
            }
        }
        if (!removed) {
            return false;
        }
        request.put("attachments", kept);
        save(context, request);
        File file = workspaceFile(context, relativePath);
        if (file.isFile()) {
            file.delete();
        }
        return true;
    }

    static synchronized JSONObject latestWaiting(Context context) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject latest = null;
        long latestCreatedAt = Long.MIN_VALUE;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX)) {
                continue;
            }
            String requestId = entry.getKey().substring(KEY_PREFIX.length());
            JSONObject request = load(context, requestId);
            if (request == null || !"waiting_human".equals(request.optString("status", ""))) {
                continue;
            }
            long createdAt = request.optLong("createdAtEpochMs", 0L);
            if (latest == null || createdAt > latestCreatedAt) {
                latest = request;
                latestCreatedAt = createdAt;
            }
        }
        return latest == null ? null : new JSONObject(latest.toString());
    }

    static synchronized boolean renewHumanActivity(Context context, String requestId, String activity, boolean openGraceOnly)
            throws JSONException {
        JSONObject request = load(context, requestId);
        if (request == null || !"waiting_human".equals(request.optString("status"))) {
            return false;
        }
        if (openGraceOnly && request.optBoolean("openGraceUsed", false)) {
            return false;
        }
        if (openGraceOnly) {
            request.put("openGraceUsed", true);
        }
        long now = System.currentTimeMillis();
        int idleTimeoutSeconds = request.optInt("idleTimeoutSeconds", DEFAULT_IDLE_TIMEOUT_SECONDS);
        request.put("lastHumanActivityAt", Instant.now().toString())
                .put("lastHumanActivityAtEpochMs", now)
                .put("lastHumanActivity", activity == null ? "interaction" : activity)
                .put("expiresAtEpochMs", now + idleTimeoutSeconds * 1000L);
        save(context, request);
        return true;
    }

    static synchronized void complete(Context context, String requestId, String action, String text)
            throws JSONException {
        JSONObject request = requireWaiting(context, requestId);
        JSONObject response = new JSONObject()
                .put("action", action == null ? "" : action)
                .put("text", text == null ? "" : text)
                .put("attachments", request.optJSONArray("attachments") == null
                        ? new JSONArray()
                        : new JSONArray(request.getJSONArray("attachments").toString()));
        request.put("status", "completed")
                .put("completedAt", Instant.now().toString())
                .put("completedAtEpochMs", System.currentTimeMillis())
                .put("response", response);
        save(context, request);
        boolean approval = "approval".equals(request.optString("requestType", "help"));
        postTerminalNotification(
                context,
                request,
                approval ? "核准已回覆" : "HUMAN_HELP 已完成",
                approval ? "已收到你的核准決定，Agent 將依結果繼續處理。" : "已收到你的回覆，AI 可以繼續處理。",
                android.R.drawable.checkbox_on_background);
    }

    private static JSONObject publicResult(
            Context context,
            JSONObject request,
            boolean includeAttachmentData,
            long callCount) throws JSONException {
        JSONObject result = new JSONObject(request.toString());
        JSONObject response = result.optJSONObject("response");
        if (response != null && includeAttachmentData) {
            JSONArray attachments = response.optJSONArray("attachments");
            if (attachments != null) {
                JSONArray enriched = new JSONArray();
                JSONArray nativeContent = new JSONArray();
                for (int index = 0; index < attachments.length(); index++) {
                    JSONObject attachment = new JSONObject(attachments.getJSONObject(index).toString());
                    String path = attachment.optString("path", "");
                    File file = workspaceFile(context, path);
                    if (file.isFile() && file.length() <= MAX_INLINE_ATTACHMENT_BYTES) {
                        try {
                            byte[] data = readAll(file);
                            if ("audio".equals(attachment.optString("type", ""))) {
                                nativeContent.put(new JSONObject()
                                        .put("type", "text")
                                        .put("text", "Human voice reply: " + path));
                                nativeContent.put(new JSONObject()
                                        .put("type", "audio")
                                        .put("mimeType", attachment.optString("mimeType", "audio/wav"))
                                        .put("data", Base64.encodeToString(data, Base64.NO_WRAP)));
                                attachment.put("nativeContentDelivered", true);
                            } else {
                                attachment.put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP));
                            }
                        } catch (IOException error) {
                            attachment.put("dataError", error.getMessage());
                        }
                    } else if (file.isFile()) {
                        attachment.put("dataOmitted", true)
                                .put("dataOmittedReason", "attachment exceeds inline response limit");
                    }
                    enriched.put(attachment);
                }
                response.put("attachments", enriched);
                if (nativeContent.length() > 0) {
                    result.put("_mcpContent", nativeContent);
                }
            }
        }
        return result.put("toolCallCount", callCount);
    }

    private static JSONArray sanitizedActions(JSONArray supplied) throws JSONException {
        JSONArray result = new JSONArray();
        if (supplied != null) {
            for (int index = 0; index < supplied.length() && result.length() < 6; index++) {
                String action = supplied.optString(index, "").trim();
                if (!action.isEmpty()) {
                    result.put(action.length() > 80 ? action.substring(0, 80) : action);
                }
            }
        }
        if (result.length() == 0) {
            result.put("完成").put("做不到");
        }
        return result;
    }

    static String approveAction(JSONObject request) {
        return matchingAction(
                request,
                new String[]{"允許", "核准", "同意", "完成", "繼續", "allow", "approve", "yes", "ok"},
                new String[]{"不同意", "不允許", "不核准", "拒絕", "否決", "做不到", "取消", "deny", "reject", "no", "not", "cancel"},
                0,
                "確認繼續");
    }

    static String rejectAction(JSONObject request) {
        return matchingAction(
                request,
                new String[]{"不同意", "不允許", "不核准", "拒絕", "否決", "做不到", "取消", "deny", "reject", "no", "not", "cancel"},
                null,
                1,
                "拒絕");
    }

    private static String matchingAction(
            JSONObject request,
            String[] words,
            String[] excludedWords,
            int fallbackIndex,
            String fallbackValue) {
        JSONArray actions = request == null ? null : request.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            return fallbackValue;
        }
        for (int index = 0; index < actions.length(); index++) {
            String action = sanitizedAction(actions.optString(index, ""));
            String normalized = action.toLowerCase(Locale.ROOT);
            if (excludedWords != null && matchesAnyActionWord(normalized, excludedWords)) {
                continue;
            }
            if (matchesAnyActionWord(normalized, words)) {
                return action;
            }
        }
        return sanitizedAction(actions.optString(Math.min(fallbackIndex, actions.length() - 1), fallbackValue));
    }

    private static boolean matchesAnyActionWord(String normalizedAction, String[] words) {
        for (String word : words) {
            String normalizedWord = word.toLowerCase(Locale.ROOT);
            if (normalizedWord.matches("[a-z0-9]+")) {
                String[] tokens = normalizedAction.split("[^a-z0-9]+");
                for (String token : tokens) {
                    if (token.equals(normalizedWord)) {
                        return true;
                    }
                }
            } else if (normalizedAction.contains(normalizedWord)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizedAction(String action) {
        String value = action == null ? "" : action.trim();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static JSONObject requireWaiting(Context context, String requestId) throws JSONException {
        JSONObject request = load(context, requestId);
        if (request == null) {
            throw new JSONException("Unknown human-help request: " + requestId);
        }
        if (!"waiting_human".equals(request.optString("status"))) {
            throw new JSONException("Human-help request is no longer waiting: " + requestId);
        }
        return request;
    }

    private static void expireIfNeeded(Context context, JSONObject request) {
        if (!"waiting_human".equals(request.optString("status"))) {
            return;
        }
        long expiresAtEpochMs = request.optLong("expiresAtEpochMs", 0L);
        if (expiresAtEpochMs <= 0L || System.currentTimeMillis() < expiresAtEpochMs) {
            return;
        }
        try {
            request.put("status", "timed_out")
                    .put("timeoutReason", "human_idle_timeout")
                    .put("timedOutAt", Instant.now().toString())
                    .put("timedOutAtEpochMs", System.currentTimeMillis());
            save(context, request);
            boolean approval = "approval".equals(request.optString("requestType", "help"));
            postTerminalNotification(
                    context,
                    request,
                    approval ? "核准請求已逾時" : "HUMAN_HELP 已逾時",
                    approval ? "未取得你的核准，這項操作不會執行。" : "AI 已停止等待這項協助，將自行決定下一步。",
                    android.R.drawable.ic_dialog_alert);
        } catch (JSONException ignored) {
        }
    }

    private static File requestDirectory(Context context, String requestId) throws IOException {
        File dir = new File(new File(context.getFilesDir(), "workspaces"), "human-help/" + requestId);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Unable to create human-help attachment directory");
        }
        return dir;
    }

    static File createCameraTempFile(Context context, String requestId) throws IOException {
        File dir = new File(context.getCacheDir(), "human-help-camera");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Unable to create camera cache directory");
        }
        return File.createTempFile(requestId + "-", ".jpg", dir);
    }

    private static File workspaceFile(Context context, String relativePath) {
        return new File(new File(context.getFilesDir(), "workspaces"), relativePath);
    }

    private static synchronized void cleanupStorageIfNeeded(Context context) {
        if (storageBytes(context) < MAX_STORAGE_BYTES) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<CleanupCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                JSONObject request = new JSONObject((String) entry.getValue());
                String status = request.optString("status", "");
                if (!isFinished(status)) {
                    continue;
                }
                long endedAt = request.optLong("completedAtEpochMs",
                        request.optLong("timedOutAtEpochMs",
                                request.optLong("cancelledAtEpochMs", request.optLong("createdAtEpochMs", 0L))));
                candidates.add(new CleanupCandidate(entry.getKey(), request.optString("requestId", ""), endedAt));
            } catch (JSONException ignored) {
            }
        }
        candidates.sort(Comparator.comparingLong(candidate -> candidate.endedAtEpochMs));
        for (CleanupCandidate candidate : candidates) {
            if (storageBytes(context) <= TARGET_STORAGE_BYTES) {
                break;
            }
            if (!candidate.requestId.isEmpty()) {
                deleteRecursively(new File(new File(context.getFilesDir(), "workspaces/human-help"), candidate.requestId));
            }
            preferences.edit().remove(candidate.preferenceKey).commit();
        }
    }

    private static boolean isFinished(String status) {
        return "completed".equals(status)
                || "timed_out".equals(status)
                || "cancelled".equals(status)
                || "failed".equals(status);
    }

    private static long storageBytes(Context context) {
        long total = directoryBytes(new File(context.getFilesDir(), "workspaces/human-help"));
        for (Map.Entry<String, ?> entry : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX) && entry.getValue() instanceof String) {
                total += ((String) entry.getValue()).getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private static long directoryBytes(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directoryBytes(child);
            }
        }
        return total;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static final class CleanupCandidate {
        final String preferenceKey;
        final String requestId;
        final long endedAtEpochMs;

        CleanupCandidate(String preferenceKey, String requestId, long endedAtEpochMs) {
            this.preferenceKey = preferenceKey;
            this.requestId = requestId;
            this.endedAtEpochMs = endedAtEpochMs;
        }
    }

    private static long copy(InputStream input, File output) throws IOException {
        long total = 0L;
        try (InputStream source = input; FileOutputStream sink = new FileOutputStream(output)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                sink.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), Integer.MAX_VALUE))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static void postRequestNotification(Context context, JSONObject request) {
        String requestId = request.optString("requestId");
        Intent open = new Intent(context, HumanHelpActivity.class)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        postRequestNotification(context, request, open);
    }

    static void postRequestNotification(Context context, JSONObject request, Intent open) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Human interaction requests",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Help and approval requests from an Agent that need a nearby human response");
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);

        String requestId = request.optString("requestId");
        PendingIntent pending = PendingIntent.getActivity(
                context,
                requestId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(request.optString("title", "AI needs your help"))
                .setContentText(request.optString("instruction", ""))
                .setStyle(new Notification.BigTextStyle().bigText(request.optString("instruction", "")))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setContentIntent(pending);
        boolean launchedDirectly = AgentAttention.tryLaunchLockedHumanHelp(context, open);
        if (!launchedDirectly) {
            AgentAttention.applyHumanHelpBehavior(context, builder, requestId.hashCode(), open);
        } else {
            AgentAttention.applyPublicLockscreen(builder);
        }
        Notification notification = builder.build();
        manager.notify(requestId.hashCode(), notification);
        AgentAttention.alert(context);
    }

    private static void postTerminalNotification(
            Context context,
            JSONObject request,
            String title,
            String message,
            int icon) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Human help requests",
                NotificationManager.IMPORTANCE_HIGH);
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);
        String requestId = request.optString("requestId");
        Intent open = new Intent(context, HumanHelpActivity.class)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                requestId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(request.optString("title", "AI needs your help") + "\n\n" + message))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pending);
        AgentAttention.applyPublicLockscreen(builder);
        Notification notification = builder.build();
        manager.notify(requestId.hashCode(), notification);
    }
}
