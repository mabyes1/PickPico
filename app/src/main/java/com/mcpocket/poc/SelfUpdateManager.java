package com.mcpocket.poc;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class SelfUpdateManager {
    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static Intent pendingConfirmationIntent;

    private SelfUpdateManager() {
    }

    static JSONObject status(Context context, long callCount) {
        JSONObject state = SelfUpdateState.read(context);
        PackageInfo current = currentPackage(context);
        SelfUpdateState.put(state, "active", ACTIVE.get());
        SelfUpdateState.put(state, "canRequestPackageInstalls", canRequestPackageInstalls(context));
        SelfUpdateState.put(state, "currentVersionName", current == null ? "unknown" : current.versionName);
        SelfUpdateState.put(state, "currentVersionCode", current == null ? -1L : versionCode(current));
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
        SelfUpdateState.write(context, state);

        Context appContext = context.getApplicationContext();
        new Thread(
                () -> runUpdate(appContext, url, expectedSha256, allowSameVersion),
                "mcpocket-self-update").start();

        SelfUpdateState.put(state, "toolCallCount", callCount);
        return state;
    }

    static void markFinished() {
        ACTIVE.set(false);
    }

    static JSONObject installStagedFromForeground(Context context) {
        File candidate = SelfUpdateState.candidateFile(context);
        if (!candidate.isFile() || candidate.length() <= 0L) {
            throw new CommandRuntime.CommandInputException("No staged MCPocket update APK is available");
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

        JSONObject previous = SelfUpdateState.read(context);
        abandonInstallerSession(context, previous.optInt("sessionId", 0));
        ACTIVE.set(true);

        JSONObject state = SelfUpdateState.read(context);
        SelfUpdateState.put(state, "status", "staging_from_ui");
        SelfUpdateState.put(state, "running", true);
        SelfUpdateState.put(state, "startedByUser", true);
        SelfUpdateState.put(state, "updatedAt", Instant.now().toString());
        SelfUpdateState.write(context, state);

        Context appContext = context.getApplicationContext();
        new Thread(
                () -> installStagedCandidate(appContext, candidate),
                "mcpocket-self-update-confirm").start();
        return state;
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

    private static void installStagedCandidate(Context context, File candidate) {
        try {
            JSONObject verified = verifyCandidate(context, candidate, false);
            SelfUpdateState.put(verified, "status", "staging_from_ui");
            SelfUpdateState.put(verified, "running", true);
            SelfUpdateState.put(verified, "startedByUser", true);
            SelfUpdateState.put(verified, "bytesDownloaded", candidate.length());
            SelfUpdateState.put(verified, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, verified);

            int sessionId = commitInstall(context, candidate);
            JSONObject committed = SelfUpdateState.read(context);
            SelfUpdateState.put(committed, "status", "committed");
            SelfUpdateState.put(committed, "running", true);
            SelfUpdateState.put(committed, "sessionId", sessionId);
            SelfUpdateState.put(committed, "startedByUser", true);
            SelfUpdateState.put(committed, "message", "Waiting for Android package installer result");
            SelfUpdateState.put(committed, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, committed);
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

    private static void abandonInstallerSession(Context context, int sessionId) {
        if (sessionId <= 0) {
            return;
        }
        try {
            context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
        } catch (Throwable ignored) {
        }
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

            int sessionId = commitInstall(context, candidate);
            JSONObject committed = SelfUpdateState.read(context);
            SelfUpdateState.put(committed, "status", "committed");
            SelfUpdateState.put(committed, "running", true);
            SelfUpdateState.put(committed, "sessionId", sessionId);
            SelfUpdateState.put(committed, "message", "Waiting for Android package installer result");
            SelfUpdateState.put(committed, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, committed);
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
        connection.setRequestProperty("User-Agent", "MCPocket-SelfUpdate/1");

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
            throw new IllegalStateException("APK signing certificate does not match installed MCPocket");
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

    private static int commitInstall(Context context, File candidate) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(candidate.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (FileInputStream input = new FileInputStream(candidate);
                 OutputStream output = session.openWrite("base.apk", 0L, candidate.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }

            Intent callback = new Intent(context, SelfUpdateInstallReceiver.class)
                    .setAction("com.mcpocket.poc.SELF_UPDATE_RESULT")
                    .putExtra("sessionId", sessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    flags);
            session.commit(pendingIntent.getIntentSender());
        } catch (Throwable error) {
            installer.abandonSession(sessionId);
            throw error;
        }
        return sessionId;
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

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
