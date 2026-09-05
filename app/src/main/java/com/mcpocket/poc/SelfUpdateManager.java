package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class SelfUpdateManager {
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final String UPDATE_CHANNEL_ID = "mcpocket_self_update";
    private static final int UPDATE_NOTIFICATION_ID = 8768;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static Intent pendingConfirmationIntent;

    private SelfUpdateManager() {
    }

    static JSONObject status(Context context, long callCount) {
        JSONObject state = SelfUpdateState.read(context);
        if (state.optBoolean("running", false)
                && !ACTIVE.get()
                && isInFlightState(state)) {
            SelfUpdateState.put(state, "status", "failed");
            SelfUpdateState.put(state, "running", false);
            SelfUpdateState.put(state, "interrupted", true);
            SelfUpdateState.put(state, "error", "The previous update was interrupted. PickPico will retry automatically.");
            SelfUpdateState.put(state, "completedAt", Instant.now().toString());
            File interruptedCandidate = SelfUpdateState.candidateFile(context);
            if (interruptedCandidate.isFile()) {
                interruptedCandidate.delete();
            }
            SelfUpdateState.write(context, state);
        }
        PackageInfo current = currentPackage(context);
        long currentVersionCode = current == null ? -1L : versionCode(current);
        long candidateVersionCode = state.optLong("candidateVersionCode", -1L);
        if (shouldMarkInstalled(state, currentVersionCode, candidateVersionCode)) {
            SelfUpdateState.put(state, "status", "installed");
            SelfUpdateState.put(state, "running", false);
            SelfUpdateState.put(state, "completedAt", Instant.now().toString());
            ACTIVE.set(false);
            File candidate = SelfUpdateState.candidateFile(context);
            if (candidate.isFile()) {
                candidate.delete();
            }
            SelfUpdateState.write(context, state);
        }
        SelfUpdateState.put(state, "active", ACTIVE.get());
        SelfUpdateState.put(state, "canRequestPackageInstalls", canRequestPackageInstalls(context));
        SelfUpdateState.put(state, "currentVersionName", current == null ? "unknown" : current.versionName);
        SelfUpdateState.put(state, "currentVersionCode", currentVersionCode);
        SelfUpdateState.put(state, "toolCallCount", callCount);
        return state;
    }

    static JSONObject start(Context context, JSONObject arguments, long callCount) {
        String url = arguments.optString("url", "").trim();
        String expectedSha256 = arguments.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        boolean allowSameVersion = arguments.optBoolean("allowSameVersion", false);

        if (!isAllowedUrl(url)) {
            throw new CommandRuntime.CommandInputException("app.update url must use http:// or https://");
        }
        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            throw new CommandRuntime.CommandInputException("app.update sha256 must be exactly 64 hexadecimal characters");
        }
        if (ACTIVE.get()) {
            JSONObject current = status(context, callCount);
            SelfUpdateState.put(current, "started", false);
            SelfUpdateState.put(current, "alreadyRunning", true);
            return current;
        }

        if (!canRequestPackageInstalls(context)) {
            JSONObject setup = SelfUpdateState.json("requires_setup", false);
            SelfUpdateState.put(setup, "requiresSetup", true);
            SelfUpdateState.put(setup, "setupAction", "allow_install_unknown_apps");
            SelfUpdateState.put(setup, "url", url);
            SelfUpdateState.put(setup, "expectedSha256", expectedSha256);
            SelfUpdateState.put(setup, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, setup);
            launchInstallSourceSettings(context);
            SelfUpdateState.put(setup, "toolCallCount", callCount);
            return setup;
        }

        if (!ACTIVE.compareAndSet(false, true)) {
            JSONObject current = status(context, callCount);
            SelfUpdateState.put(current, "started", false);
            SelfUpdateState.put(current, "alreadyRunning", true);
            return current;
        }

        File candidate = SelfUpdateState.candidateFile(context);
        if (candidate.exists()) {
            candidate.delete();
        }

        JSONObject state = SelfUpdateState.json("downloading", true);
        SelfUpdateState.put(state, "started", true);
        SelfUpdateState.put(state, "alreadyRunning", false);
        SelfUpdateState.put(state, "url", url);
        SelfUpdateState.put(state, "expectedSha256", expectedSha256);
        SelfUpdateState.put(state, "bytesDownloaded", 0L);
        SelfUpdateState.put(state, "startedAt", Instant.now().toString());
        copyIfPresent(arguments, state, "channel");
        copyIfPresent(arguments, state, "manifestUrl");
        copyIfPresent(arguments, state, "latestVersionName");
        copyIfPresent(arguments, state, "latestVersionCode");
        SelfUpdateState.write(context, state);

        Context appContext = context.getApplicationContext();
        new Thread(
                () -> runUpdate(appContext, url, expectedSha256, allowSameVersion),
                "mcpocket-self-update").start();

        SelfUpdateState.put(state, "toolCallCount", callCount);
        return state;
    }

    static JSONObject checkLatest(Context context, JSONObject arguments, long callCount) {
        String manifestUrl = "";
        try {
            manifestUrl = resolveManifestUrl(context, arguments);
            JSONObject manifest = fetchManifest(manifestUrl);
            validateManifest(manifest);

            PackageInfo current = currentPackage(context);
            long currentVersionCode = versionCode(current);
            long latestVersionCode = manifest.optLong("versionCode", -1L);

            return new JSONObject()
                    .put("channel", manifest.optString("channel", "stable"))
                    .put("manifestUrl", manifestUrl)
                    .put("currentVersionName", current == null ? "unknown" : current.versionName)
                    .put("currentVersionCode", currentVersionCode)
                    .put("latestVersionName", manifest.optString("versionName", "unknown"))
                    .put("latestVersionCode", latestVersionCode)
                    .put("updateAvailable", latestVersionCode > currentVersionCode)
                    .put("apkUrl", manifest.getString("apkUrl"))
                    .put("sha256", manifest.getString("sha256").toLowerCase(Locale.ROOT))
                    .put("publishedAt", manifest.optString("publishedAt", ""))
                    .put("toolCallCount", callCount);
        } catch (CommandRuntime.CommandInputException error) {
            throw error;
        } catch (Exception error) {
            throw new CommandRuntime.CommandInputException(
                    "Unable to check PickPico update channel: "
                            + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    static JSONObject startLatest(Context context, JSONObject arguments, long callCount) {
        JSONObject latest = checkLatest(context, arguments, callCount);
        return startResolvedLatest(context, latest, callCount);
    }

    static JSONObject startResolvedLatest(Context context, JSONObject latest, long callCount) {
        if (!latest.optBoolean("updateAvailable", false)) {
            SelfUpdateState.put(latest, "started", false);
            SelfUpdateState.put(latest, "status", "up_to_date");
            return latest;
        }

        JSONObject direct = new JSONObject();
        SelfUpdateState.put(direct, "url", latest.optString("apkUrl", ""));
        SelfUpdateState.put(direct, "sha256", latest.optString("sha256", ""));
        SelfUpdateState.put(direct, "allowSameVersion", false);
        SelfUpdateState.put(direct, "channel", latest.optString("channel", "stable"));
        SelfUpdateState.put(direct, "manifestUrl", latest.optString("manifestUrl", ""));
        SelfUpdateState.put(direct, "latestVersionName", latest.optString("latestVersionName", ""));
        SelfUpdateState.put(direct, "latestVersionCode", latest.optLong("latestVersionCode", -1L));
        return start(context, direct, callCount);
    }

    static void markFinished() {
        ACTIVE.set(false);
    }

    static JSONObject installStagedFromForeground(Context context) {
        File candidate = SelfUpdateState.candidateFile(context);
        if (!candidate.isFile() || candidate.length() <= 0L) {
            throw new CommandRuntime.CommandInputException("No staged PickPico update APK is available");
        }

        if (!canRequestPackageInstalls(context)) {
            JSONObject setup = SelfUpdateState.read(context);
            SelfUpdateState.put(setup, "status", "requires_setup");
            SelfUpdateState.put(setup, "running", false);
            SelfUpdateState.put(setup, "requiresSetup", true);
            SelfUpdateState.put(setup, "setupAction", "allow_install_unknown_apps");
            SelfUpdateState.put(setup, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, setup);
            launchInstallSourceSettings(context);
            return setup;
        }

        ACTIVE.set(true);

        try {
            JSONObject verified = verifyCandidate(context, candidate, false);
            Intent installIntent = buildInstallIntent(context, candidate);
            setPendingConfirmationIntent(installIntent);
            JSONObject state = SelfUpdateState.read(context);
            SelfUpdateState.put(state, "status", "pending_user_action");
            SelfUpdateState.put(state, "running", false);
            SelfUpdateState.put(state, "startedByUser", true);
            SelfUpdateState.put(state, "message", "Android confirmation required");
            SelfUpdateState.put(state, "candidateVersionName", verified.optString("candidateVersionName", ""));
            SelfUpdateState.put(state, "candidateVersionCode", verified.optLong("candidateVersionCode", -1L));
            SelfUpdateState.put(state, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, state);
            context.startActivity(installIntent);
            ACTIVE.set(false);
            return state;
        } catch (Throwable error) {
            JSONObject failed = SelfUpdateState.read(context);
            SelfUpdateState.put(failed, "status", "failed");
            SelfUpdateState.put(failed, "running", false);
            SelfUpdateState.put(failed, "error", error.getClass().getSimpleName() + ": " + error.getMessage());
            SelfUpdateState.put(failed, "completedAt", Instant.now().toString());
            SelfUpdateState.write(context, failed);
            ACTIVE.set(false);
            return failed;
        }
    }

    static boolean hasInstallableCandidate(Context context) {
        File candidate = SelfUpdateState.candidateFile(context);
        if (!candidate.isFile() || candidate.length() <= 0L) {
            return false;
        }
        JSONObject state = SelfUpdateState.read(context);
        PackageInfo current = currentPackage(context);
        long candidateVersionCode = state.optLong("candidateVersionCode", -1L);
        long currentVersionCode = versionCode(current);
        return candidateVersionCode > currentVersionCode;
    }

    static synchronized void setPendingConfirmationIntent(Intent intent) {
        pendingConfirmationIntent = intent == null ? null : new Intent(intent);
    }

    static synchronized Intent takePendingConfirmationIntent() {
        Intent intent = pendingConfirmationIntent;
        pendingConfirmationIntent = null;
        return intent;
    }

    private static void runUpdate(
            Context context,
            String url,
            String expectedSha256,
            boolean allowSameVersion) {
        try {
            File candidate = SelfUpdateState.candidateFile(context);
            String actualSha256 = download(context, url, candidate);
            if (!actualSha256.equals(expectedSha256)) {
                throw new IllegalStateException(
                        "APK SHA-256 mismatch: expected " + expectedSha256 + " but got " + actualSha256);
            }

            JSONObject verified = verifyCandidate(context, candidate, allowSameVersion);
            SelfUpdateState.put(verified, "status", "staging");
            SelfUpdateState.put(verified, "running", true);
            SelfUpdateState.put(verified, "url", url);
            SelfUpdateState.put(verified, "sha256", actualSha256);
            SelfUpdateState.put(verified, "bytesDownloaded", candidate.length());
            SelfUpdateState.put(verified, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, verified);

            Intent installIntent = buildInstallIntent(context, candidate);
            setPendingConfirmationIntent(installIntent);
            JSONObject ready = SelfUpdateState.read(context);
            SelfUpdateState.put(ready, "status", "pending_user_action");
            SelfUpdateState.put(ready, "running", false);
            SelfUpdateState.put(ready, "message", "APK verified. Tap the PickPico update notification to install.");
            SelfUpdateState.put(ready, "confirmationNotification", true);
            SelfUpdateState.put(ready, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, ready);
            showInstallNotification(context, installIntent);
            ACTIVE.set(false);
        } catch (Throwable error) {
            JSONObject failed = SelfUpdateState.read(context);
            SelfUpdateState.put(failed, "status", "failed");
            SelfUpdateState.put(failed, "running", false);
            SelfUpdateState.put(
                    failed,
                    "error",
                    error.getClass().getSimpleName() + ": " + error.getMessage());
            SelfUpdateState.put(failed, "completedAt", Instant.now().toString());
            SelfUpdateState.write(context, failed);
            ACTIVE.set(false);
        }
    }

    private static String download(Context context, String url, File target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "PickPico-SelfUpdate/1");

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Update download returned HTTP " + status);
        }

        long declaredLength = connection.getContentLengthLong();
        if (declaredLength > MAX_APK_BYTES) {
            connection.disconnect();
            throw new IllegalStateException("Update APK exceeds 250 MiB limit");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        long nextProgressWrite = 1024L * 1024L;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_APK_BYTES) {
                    throw new IllegalStateException("Update APK exceeds 250 MiB limit");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);

                if (total >= nextProgressWrite) {
                    JSONObject progress = SelfUpdateState.read(context);
                    SelfUpdateState.put(progress, "status", "downloading");
                    SelfUpdateState.put(progress, "running", true);
                    SelfUpdateState.put(progress, "bytesDownloaded", total);
                    if (declaredLength > 0) {
                        SelfUpdateState.put(progress, "totalBytes", declaredLength);
                    }
                    SelfUpdateState.put(progress, "updatedAt", Instant.now().toString());
                    SelfUpdateState.write(context, progress);
                    nextProgressWrite = total + 1024L * 1024L;
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        return toHex(digest.digest());
    }

    static String resolveManifestUrl(Context context, JSONObject arguments) {
        String explicit = arguments == null ? "" : arguments.optString("manifestUrl", "").trim();
        if (!explicit.isEmpty()) {
            if (!isAllowedUrl(explicit)) {
                throw new CommandRuntime.CommandInputException("manifestUrl must use http:// or https://");
            }
            return explicit;
        }
        String relay = context == null ? "" : context
                .getSharedPreferences(McpNodeService.PREFS, Context.MODE_PRIVATE)
                .getString(McpNodeService.KEY_RELAY_BASE_URL, "");
        return updateManifestForRelay(relay);
    }

    static String updateManifestForRelay(String relay) {
        if (relay == null || relay.trim().isEmpty()) {
            throw new CommandRuntime.CommandInputException(
                    "No update source configured. Set your own Relay URL or provide manifestUrl.");
        }
        return RelayClient.normalizeBaseUrl(relay) + "/v1/update/latest";
    }

    private static JSONObject fetchManifest(String manifestUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PickPico-UpdateChannel/1");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Update manifest returned HTTP " + status);
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > MAX_MANIFEST_BYTES) {
                throw new IllegalStateException("Update manifest exceeds 256 KiB limit");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > MAX_MANIFEST_BYTES) {
                        throw new IllegalStateException("Update manifest exceeds 256 KiB limit");
                    }
                    output.write(buffer, 0, read);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void validateManifest(JSONObject manifest) {
        long versionCode = manifest.optLong("versionCode", -1L);
        String versionName = manifest.optString("versionName", "").trim();
        String apkUrl = manifest.optString("apkUrl", "").trim();
        String sha256 = manifest.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        if (versionCode <= 0L) {
            throw new CommandRuntime.CommandInputException("Update manifest versionCode must be positive");
        }
        if (versionName.isEmpty() || versionName.length() > 128) {
            throw new CommandRuntime.CommandInputException("Update manifest versionName is missing or invalid");
        }
        if (!isAllowedUrl(apkUrl)) {
            throw new CommandRuntime.CommandInputException("Update manifest apkUrl must use http:// or https://");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new CommandRuntime.CommandInputException("Update manifest sha256 must be exactly 64 hexadecimal characters");
        }
    }

    private static JSONObject verifyCandidate(
            Context context,
            File candidate,
            boolean allowSameVersion) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        int signingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo candidateInfo = packageManager.getPackageArchiveInfo(
                candidate.getAbsolutePath(),
                signingFlags);
        if (candidateInfo == null) {
            throw new IllegalStateException("Downloaded file is not a readable APK");
        }
        if (!context.getPackageName().equals(candidateInfo.packageName)) {
            throw new IllegalStateException(
                    "APK package mismatch: expected " + context.getPackageName()
                            + " but got " + candidateInfo.packageName);
        }

        PackageInfo currentInfo = packageManager.getPackageInfo(context.getPackageName(), signingFlags);
        long currentVersionCode = versionCode(currentInfo);
        long candidateVersionCode = versionCode(candidateInfo);
        if (candidateVersionCode < currentVersionCode
                || (!allowSameVersion && candidateVersionCode == currentVersionCode)) {
            throw new IllegalStateException(
                    "APK versionCode " + candidateVersionCode
                            + " is not newer than installed versionCode " + currentVersionCode);
        }

        Set<String> currentCertificates = signingCertificateDigests(currentInfo);
        Set<String> candidateCertificates = signingCertificateDigests(candidateInfo);
        if (currentCertificates.isEmpty() || !currentCertificates.equals(candidateCertificates)) {
            throw new IllegalStateException("APK signing certificate does not match installed PickPico");
        }

        JSONObject verified = SelfUpdateState.json("verified", true);
        SelfUpdateState.put(verified, "packageName", candidateInfo.packageName);
        SelfUpdateState.put(verified, "currentVersionName", currentInfo.versionName);
        SelfUpdateState.put(verified, "currentVersionCode", currentVersionCode);
        SelfUpdateState.put(verified, "candidateVersionName", candidateInfo.versionName);
        SelfUpdateState.put(verified, "candidateVersionCode", candidateVersionCode);
        SelfUpdateState.put(verified, "signingCertificateSha256", candidateCertificates.iterator().next());
        return verified;
    }

    private static Intent buildInstallIntent(Context context, File candidate) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".files",
                candidate);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        install.setClipData(ClipData.newRawUri(candidate.getName(), uri));
        return install;
    }

    private static void showInstallNotification(Context context, Intent installIntent) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                UPDATE_CHANNEL_ID,
                "PickPico updates",
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(channel);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                UPDATE_NOTIFICATION_ID,
                installIntent,
                flags);

        Notification notification = new Notification.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PickPico update ready")
                .setContentText("Tap to install the verified update")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(UPDATE_NOTIFICATION_ID, notification);
    }

    private static boolean canRequestPackageInstalls(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    private static void launchInstallSourceSettings(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(settings);
    }

    private static PackageInfo currentPackage(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long versionCode(PackageInfo info) {
        if (info == null) {
            return -1L;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static Set<String> signingCertificateDigests(PackageInfo info) throws Exception {
        Set<String> digests = new HashSet<>();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null) {
            return digests;
        }
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digests.add(toHex(digest.digest(signature.toByteArray())));
        }
        return digests;
    }

    private static boolean isAllowedUrl(String url) {
        return url.startsWith("https://") || url.startsWith("http://");
    }

    static boolean isInFlightState(JSONObject state) {
        String status = state == null ? "" : state.optString("status", "");
        return "downloading".equals(status) || "staging".equals(status);
    }

    static boolean shouldMarkInstalled(JSONObject state, long currentVersionCode, long candidateVersionCode) {
        return candidateVersionCode > 0L
                && currentVersionCode >= candidateVersionCode
                && !"installed".equals(state == null ? "" : state.optString("status", ""));
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String key) {
        if (source.has(key)) {
            SelfUpdateState.put(target, key, source.opt(key));
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
