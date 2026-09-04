package com.mcpocket.poc;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpNodeService extends Service implements McpToolActions {
    public static final String ACTION_START = "com.mcpocket.poc.action.START";
    public static final String ACTION_STOP = "com.mcpocket.poc.action.STOP";
    public static final String ACTION_REFRESH_MEDIA_FOREGROUND = "com.mcpocket.poc.action.REFRESH_MEDIA_FOREGROUND";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_ENABLE_MEDIA_FGS = "enableMediaForegroundCapabilities";

    public static final String PREFS = "mcpocket_node";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_DESIRED_RUNNING = "desired_running";
    public static final String KEY_MEDIA_FOREGROUND_REQUESTED = "media_foreground_requested";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_REMOTE_ENDPOINT = "remote_endpoint";
    public static final String KEY_RELAY_BASE_URL = "relay_base_url";
    public static final String KEY_RELAY_STATUS = "relay_status";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_RECENT = "recent";
    public static final String KEY_CALL_COUNT = "call_count";
    public static final String KEY_ERROR = "error";

    private static final int PORT = 8765;
    private static final int NOTIFICATION_ID = 8765;
    private static final String CHANNEL_ID = "mcpocket_node";
    private static final int MAX_PROCESS_SESSIONS = 20;
    private static final long AUTO_UPDATE_INITIAL_DELAY_MS = 10_000L;
    private static final long AUTO_UPDATE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5L);
    private static volatile boolean nodeActive;

    private McpHttpServer server;
    private String endpoint = "";
    private long startedElapsed;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean autoUpdateCheckRunning = new AtomicBoolean(false);
    private final Runnable autoUpdateCheckRunnable = this::runAutoUpdateCheck;
    private final LinkedHashMap<String, ProcessSession> processSessions = new LinkedHashMap<>();
    private Ringtone activeRing;
    private int previousAlarmVolume = -1;
    private AndroidDeviceCapabilities deviceCapabilities;
    private boolean mediaForegroundRequested;
    private RelayClient relayClient;
    private BleButtonBridge buttonBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceCapabilities = new AndroidDeviceCapabilities(this, workspaceRoot());
        buttonBridge = new BleButtonBridge(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DESIRED_RUNNING, false)
                    .putBoolean(KEY_MEDIA_FOREGROUND_REQUESTED, false)
                    .apply();
            stopNode();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH_MEDIA_FOREGROUND.equals(action)) {
            if (server != null && nodeActive) {
                mediaForegroundRequested = true;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_MEDIA_FOREGROUND_REQUESTED, true)
                        .apply();
                startAsForeground("Listening on " + endpoint);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || server != null || nodeActive) {
            return START_NOT_STICKY;
        }

        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (TextUtils.isEmpty(token)) {
            recordFailure("Missing bearer token");
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaForegroundRequested = intent.getBooleanExtra(EXTRA_ENABLE_MEDIA_FGS, false);
        nodeActive = true;
        startAsForeground("Starting local MCP server…");
        try {
            endpoint = "http://" + findLanAddress() + ":" + PORT + "/mcp";
            server = new McpHttpServer(PORT, token, this);
            server.start();
            startedElapsed = SystemClock.elapsedRealtime();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_RUNNING, true)
                    .putBoolean(KEY_DESIRED_RUNNING, true)
                    .putBoolean(KEY_MEDIA_FOREGROUND_REQUESTED, mediaForegroundRequested)
                    .putString(KEY_ENDPOINT, endpoint)
                    .putString(KEY_TOKEN, token)
                    .putString(KEY_RECENT, "Node started at " + Instant.now())
                    .putLong(KEY_CALL_COUNT, 0L)
                    .remove(KEY_ERROR)
                    .apply();
            startRelayIfConfigured();
            if (buttonBridge != null) {
                buttonBridge.start();
            }
            updateNotification("Listening on " + endpoint);
            scheduleAutoUpdateCheck(AUTO_UPDATE_INITIAL_DELAY_MS);
        } catch (Exception error) {
            nodeActive = false;
            if (server != null) {
                server.stop();
                server = null;
            }
            recordFailure(error.getClass().getSimpleName() + ": " + error.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        nodeActive = false;
        mainHandler.removeCallbacks(autoUpdateCheckRunnable);
        stopAlertSound();
        stopAllProcessSessions();
        if (buttonBridge != null) {
            buttonBridge.stop();
            buttonBridge = null;
        }
        if (deviceCapabilities != null) {
            deviceCapabilities.shutdown();
            deviceCapabilities = null;
        }
        stopRelay();
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        super.onDestroy();
    }

    private void scheduleAutoUpdateCheck(long delayMs) {
        mainHandler.removeCallbacks(autoUpdateCheckRunnable);
        if (nodeActive && server != null) {
            mainHandler.postDelayed(autoUpdateCheckRunnable, Math.max(0L, delayMs));
        }
    }

    private void runAutoUpdateCheck() {
        if (!nodeActive || server == null) {
            return;
        }
        if (!autoUpdateCheckRunning.compareAndSet(false, true)) {
            scheduleAutoUpdateCheck(AUTO_UPDATE_INTERVAL_MS);
            return;
        }

        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject status = SelfUpdateManager.status(appContext, 0L);
                if (status.optBoolean("active", false) || status.optBoolean("running", false)) {
                    return;
                }

                JSONObject latest = SelfUpdateManager.checkLatest(appContext, new JSONObject(), 0L);
                if (!latest.optBoolean("updateAvailable", false)) {
                    return;
                }

                long latestVersionCode = latest.optLong("latestVersionCode", -1L);
                long stagedVersionCode = status.optLong("candidateVersionCode", -1L);
                boolean hasCandidate = SelfUpdateManager.hasInstallableCandidate(appContext);

                // Unknown-app install setup must still be initiated by the human from the UI.
                // Once setup exists, future updates are downloaded and staged automatically.
                if (!status.optBoolean("canRequestPackageInstalls", false)) {
                    return;
                }

                if (!hasCandidate || stagedVersionCode < latestVersionCode) {
                    SelfUpdateManager.startLatest(appContext, new JSONObject(), 0L);
                }
            } catch (Throwable ignored) {
                // Auto-check is best effort. Manual update/check surfaces detailed errors.
            } finally {
                autoUpdateCheckRunning.set(false);
                if (nodeActive) {
                    mainHandler.postDelayed(autoUpdateCheckRunnable, AUTO_UPDATE_INTERVAL_MS);
                }
            }
        }, "pickpico-auto-update-check").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isNodeRunning() {
        return nodeActive;
    }

    @Override
    public JSONObject capabilityState(String commandId) throws JSONException {
        return AndroidCapabilityRegistry.state(this, commandId);
    }

    @Override
    public boolean isCommandExposed(String commandId) {
        return AndroidCapabilityRegistry.isCommandExposed(this, commandId);
    }

    @Override
    public String approvalMode() {
        return McpocketPolicySettings.approvalMode(this);
    }

    @Override
    public JSONObject policyStatus(long callCount) throws JSONException {
        return McpocketPolicySettings.status(this, callCount);
    }

    @Override
    public JSONObject requestApproval(
            String commandId,
            String description,
            String risk,
            JSONObject arguments,
            long callCount) throws JSONException {
        JSONObject requestArguments = new JSONObject()
                .put("requestType", "approval")
                .put("title", "Agent approval required")
                .put("instruction",
                        description
                                + "\n\nCommand: " + commandId
                                + "\nRisk: " + risk
                                + approvalArgumentSummary(commandId, arguments))
                .put("actions", new JSONArray().put("允許").put("拒絕"))
                .put("allowTextReply", false)
                .put("allowImages", false)
                .put("maxImages", 0)
                .put("idleTimeoutSeconds", 120);
        JSONObject response = HumanHelpStore.createAndWait(this, requestArguments, callCount);
        JSONObject humanResponse = response.optJSONObject("response");
        String action = humanResponse == null ? "" : humanResponse.optString("action", "");
        boolean approved = "completed".equals(response.optString("status"))
                && ("允許".equals(action) || "Allow".equalsIgnoreCase(action));
        AgentInboxStore.add(
                this,
                "human.approval.result",
                approved ? "Approval granted" : "Approval not granted",
                commandId + " · " + response.optString("status", "unknown"));
        return new JSONObject()
                .put("approved", approved)
                .put("status", response.optString("status", "unknown"))
                .put("requestId", response.optString("requestId", ""))
                .put("action", action)
                .put("commandId", commandId)
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject serverInfo(long callCount) throws JSONException {
        long uptimeMs = startedElapsed == 0L ? 0L : SystemClock.elapsedRealtime() - startedElapsed;
        return new JSONObject()
                .put("name", "PickPico")
                .put("version", BuildConfig.VERSION_NAME)
                .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("endpoint", endpoint)
                .put("remoteEndpoint", getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_REMOTE_ENDPOINT, ""))
                .put("relayStatus", getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(KEY_RELAY_STATUS, "disabled"))
                .put("workspaceRoot", workspaceRoot().getAbsolutePath())
                .put("uptimeSeconds", uptimeMs / 1000L)
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject phoneStatus(long callCount) throws JSONException {
        long uptimeMs = startedElapsed == 0L ? 0L : SystemClock.elapsedRealtime() - startedElapsed;
        BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int batteryPercent = battery == null
                ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = connectivity == null || activeNetwork == null
                ? null
                : connectivity.getNetworkCapabilities(activeNetwork);

        StatFs storage = new StatFs(getFilesDir().getAbsolutePath());
        return new JSONObject()
                .put("device", new JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("androidRelease", Build.VERSION.RELEASE)
                        .put("apiLevel", Build.VERSION.SDK_INT))
                .put("battery", new JSONObject()
                        .put("percent", batteryPercent)
                        .put("charging", battery != null && battery.isCharging())
                        .put("powerSaveMode", power != null && power.isPowerSaveMode()))
                .put("network", new JSONObject()
                        .put("transport", activeTransport(capabilities))
                        .put("ipv4", findLanAddress())
                        .put("metered", connectivity != null && connectivity.isActiveNetworkMetered()))
                .put("storage", new JSONObject()
                        .put("appDataFreeBytes", storage.getAvailableBytes())
                        .put("appDataTotalBytes", storage.getTotalBytes()))
                .put("capabilities", deviceCapabilities == null
                        ? new JSONObject()
                        : deviceCapabilities.status())
                .put("node", new JSONObject()
                        .put("endpoint", endpoint)
                        .put("remoteEndpoint", getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getString(KEY_REMOTE_ENDPOINT, ""))
                        .put("relayStatus", getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getString(KEY_RELAY_STATUS, "disabled"))
                        .put("uptimeSeconds", uptimeMs / 1000L)
                        .put("toolCallCount", callCount));
    }

    @Override
    public JSONObject phoneExec(String command, long callCount) throws JSONException {
        List<String> argv = commandArgv(command);
        if (argv == null) {
            return new JSONObject()
                    .put("command", command)
                    .put("executed", false)
                    .put("error", "Command is not allowlisted")
                    .put("toolCallCount", callCount);
        }

        long started = SystemClock.elapsedRealtime();
        Process process = null;
        try {
            process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                recordExec(command, callCount, "timed out");
                return new JSONObject()
                        .put("command", command)
                        .put("argv", new org.json.JSONArray(argv))
                        .put("executed", true)
                        .put("timedOut", true)
                        .put("durationMs", SystemClock.elapsedRealtime() - started)
                        .put("toolCallCount", callCount);
            }

            String output = readProcessOutput(process.getInputStream(), 32 * 1024);
            int exitCode = process.exitValue();
            recordExec(command, callCount, "exit " + exitCode);
            return new JSONObject()
                    .put("command", command)
                    .put("argv", new org.json.JSONArray(argv))
                    .put("executed", true)
                    .put("timedOut", false)
                    .put("exitCode", exitCode)
                    .put("stdout", output)
                    .put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
        } catch (Exception error) {
            recordExec(command, callCount, error.getClass().getSimpleName());
            return new JSONObject()
                    .put("command", command)
                    .put("argv", new org.json.JSONArray(argv))
                    .put("executed", false)
                    .put("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                    .put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("toolCallCount", callCount);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public JSONObject execCommand(JSONObject arguments, long callCount) throws JSONException {
        String command = arguments.optString("command", "");
        String cwd = arguments.optString("cwd", "");
        String stdin = arguments.optString("stdin", "");
        int timeoutMs = arguments.optInt("timeoutMs", 30000);
        int maxOutputBytes = arguments.optInt("maxOutputBytes", 65536);
        boolean background = arguments.optBoolean("background", false);
        JSONObject env = arguments.optJSONObject("env");

        long started = SystemClock.elapsedRealtime();
        Process process = null;
        StreamCapture stdoutCapture = null;
        StreamCapture stderrCapture = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        boolean keepAlive = false;
        File pidFile = null;
        int processGroupId = -1;
        try {
            File directory = resolveExecDirectory(cwd);
            if (!directory.isDirectory()) {
                return execFailure(
                        command,
                        directory.getAbsolutePath(),
                        "Working directory does not exist or is not a directory",
                        started,
                        callCount);
            }

            pidFile = File.createTempFile("mcpocket-proc-", ".pid", getCacheDir());
            String wrappedCommand = "printf '%s' $$ > "
                    + shellQuote(pidFile.getAbsolutePath())
                    + "; "
                    + command;
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/toybox",
                    "setsid",
                    "/system/bin/sh",
                    "-c",
                    wrappedCommand);
            builder.directory(directory);

            if (env != null) {
                Iterator<String> keys = env.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    builder.environment().put(key, env.getString(key));
                }
            }

            process = builder.start();
            processGroupId = waitForProcessGroupId(pidFile, 750L);
            stdoutCapture = new StreamCapture(process.getInputStream(), maxOutputBytes);
            stderrCapture = new StreamCapture(process.getErrorStream(), maxOutputBytes);
            stdoutThread = new Thread(stdoutCapture, "mcpocket-exec-stdout");
            stderrThread = new Thread(stderrCapture, "mcpocket-exec-stderr");
            stdoutThread.setDaemon(true);
            stderrThread.setDaemon(true);
            stdoutThread.start();
            stderrThread.start();

            try (OutputStream processInput = process.getOutputStream()) {
                if (!stdin.isEmpty()) {
                    processInput.write(stdin.getBytes(StandardCharsets.UTF_8));
                    processInput.flush();
                }
            }

            if (background) {
                String sessionId = "proc-" + UUID.randomUUID();
                ProcessSession session = new ProcessSession(
                        sessionId,
                        command,
                        directory.getAbsolutePath(),
                        processGroupId,
                        process,
                        stdoutCapture,
                        stderrCapture,
                        stdoutThread,
                        stderrThread,
                        started);
                rememberProcessSession(session);
                keepAlive = true;
                recordExec(command, callCount, "background " + sessionId);
                return processSessionResult(session, callCount);
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            if (timedOut) {
                terminateProcessGroup(process, processGroupId, true);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }

            joinCapture(stdoutThread);
            joinCapture(stderrThread);

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            String resultLabel = timedOut ? "timed out" : "exit " + exitCode;
            recordExec(command, callCount, resultLabel);

            return new JSONObject()
                    .put("command", command)
                    .put("shell", "/system/bin/sh")
                    .put("cwd", directory.getAbsolutePath())
                    .put("executed", true)
                    .put("background", false)
                    .put("timedOut", timedOut)
                    .put("exitCode", exitCode)
                    .put("stdout", stdoutCapture == null ? "" : stdoutCapture.text())
                    .put("stderr", stderrCapture == null ? "" : stderrCapture.text())
                    .put("stdoutTruncated", stdoutCapture != null && stdoutCapture.truncated())
                    .put("stderrTruncated", stderrCapture != null && stderrCapture.truncated())
                    .put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
        } catch (Exception error) {
            recordExec(command, callCount, error.getClass().getSimpleName());
            return execFailure(
                    command,
                    cwd,
                    error.getClass().getSimpleName() + ": " + error.getMessage(),
                    started,
                    callCount);
        } finally {
            if (pidFile != null) {
                pidFile.delete();
            }
            if (process != null && !keepAlive) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
                if (process.isAlive()) {
                    terminateProcessGroup(process, processGroupId, true);
                }
            }
        }
    }

    @Override
    public JSONObject readProcessOutput(JSONObject arguments, long callCount) throws JSONException {
        String sessionId = arguments.optString("sessionId", "");
        return processSessionResult(requireProcessSession(sessionId), callCount);
    }

    @Override
    public JSONObject killProcessSession(JSONObject arguments, long callCount) throws JSONException {
        String sessionId = arguments.optString("sessionId", "");
        boolean force = arguments.optBoolean("force", false);
        ProcessSession session = requireProcessSession(sessionId);
        boolean wasRunning = session.process.isAlive();
        if (wasRunning) {
            terminateProcessGroup(session, force);
        }
        JSONObject result = processSessionResult(session, callCount);
        result.put("stopRequested", wasRunning);
        result.put("force", force);
        recordExec(session.command, callCount, "stopped " + sessionId);
        return result;
    }

    @Override
    public JSONObject workspaceInfo(long callCount) throws JSONException {
        File root = workspaceRoot();
        StatFs storage = new StatFs(root.getAbsolutePath());
        JSONArray runtimes = new JSONArray();
        String[] candidates = {"sh", "toybox", "git", "node", "npm", "python3", "python", "java"};
        for (String candidate : candidates) {
            String executable = findExecutable(candidate);
            if (executable != null) {
                runtimes.put(new JSONObject()
                        .put("name", candidate)
                        .put("path", executable));
            }
        }
        return new JSONObject()
                .put("root", root.getAbsolutePath())
                .put("privateAppStorage", true)
                .put("relativeExecCwd", true)
                .put("backgroundProcesses", true)
                .put("freeBytes", storage.getAvailableBytes())
                .put("totalBytes", storage.getTotalBytes())
                .put("detectedExecutables", runtimes)
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject workspaceList(JSONObject arguments, long callCount) throws JSONException {
        String path = arguments.optString("path", ".");
        int maxDepth = arguments.optInt("maxDepth", 2);
        int maxEntries = arguments.optInt("maxEntries", 500);
        try {
            File target = resolveWorkspacePath(path);
            if (!target.exists()) {
                throw new CommandRuntime.CommandInputException("Workspace path does not exist: " + path);
            }
            if (!target.isDirectory()) {
                throw new CommandRuntime.CommandInputException("Workspace path is not a directory: " + path);
            }
            JSONArray entries = new JSONArray();
            boolean truncated = appendWorkspaceEntries(target, maxDepth, maxEntries, entries);
            return new JSONObject()
                    .put("path", workspaceRelativePath(target))
                    .put("entries", entries)
                    .put("count", entries.length())
                    .put("truncated", truncated)
                    .put("toolCallCount", callCount);
        } catch (IOException error) {
            throw new CommandRuntime.CommandInputException("Invalid workspace path: " + error.getMessage());
        }
    }

    @Override
    public JSONObject workspaceReadFile(JSONObject arguments, long callCount) throws JSONException {
        String path = arguments.optString("path", "");
        int maxBytes = arguments.optInt("maxBytes", 262144);
        try {
            File target = resolveWorkspacePath(path);
            if (!target.isFile()) {
                throw new CommandRuntime.CommandInputException("Workspace file does not exist: " + path);
            }
            byte[] bytes;
            boolean truncated;
            try (FileInputStream input = new FileInputStream(target);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int remaining = maxBytes;
                int read;
                while (remaining > 0
                        && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                truncated = input.read() >= 0;
                bytes = output.toByteArray();
            }
            return new JSONObject()
                    .put("path", workspaceRelativePath(target))
                    .put("content", new String(bytes, StandardCharsets.UTF_8))
                    .put("bytesRead", bytes.length)
                    .put("sizeBytes", target.length())
                    .put("truncated", truncated)
                    .put("toolCallCount", callCount);
        } catch (IOException error) {
            throw new CommandRuntime.CommandInputException("Unable to read workspace file: " + error.getMessage());
        }
    }

    @Override
    public JSONObject workspaceWriteFile(JSONObject arguments, long callCount) throws JSONException {
        String path = arguments.optString("path", "");
        String content = arguments.optString("content", "");
        boolean append = arguments.optBoolean("append", false);
        boolean createParents = arguments.optBoolean("createParents", true);
        try {
            File target = resolveWorkspacePath(path);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                if (!createParents || !parent.mkdirs()) {
                    throw new CommandRuntime.CommandInputException(
                            "Workspace parent directory does not exist: " + workspaceRelativePath(parent));
                }
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(target, append)) {
                output.write(bytes);
            }
            return new JSONObject()
                    .put("path", workspaceRelativePath(target))
                    .put("bytesWritten", bytes.length)
                    .put("sizeBytes", target.length())
                    .put("append", append)
                    .put("toolCallCount", callCount);
        } catch (IOException error) {
            throw new CommandRuntime.CommandInputException("Unable to write workspace file: " + error.getMessage());
        }
    }

    @Override
    public JSONObject nodeStart(JSONObject arguments, long callCount) throws JSONException {
        String entryPath = arguments.optString("entry", "");
        try {
            File entry = resolveWorkspacePath(entryPath);
            if (!entry.isFile()) {
                throw new CommandRuntime.CommandInputException("Node entry does not exist: " + entryPath);
            }

            if (findNodeRuntimePid() > 0) {
                JSONObject current = nodeStatus(callCount);
                current.put("started", false).put("alreadyRunning", true);
                return current;
            }

            File cwd = entry.getParentFile();
            Intent intent = new Intent(this, NodeRuntimeService.class)
                    .setAction(NodeRuntimeService.ACTION_START)
                    .putExtra(NodeRuntimeService.EXTRA_ENTRY, entry.getAbsolutePath())
                    .putExtra(NodeRuntimeService.EXTRA_CWD, cwd.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            return new JSONObject()
                    .put("started", true)
                    .put("alreadyRunning", false)
                    .put("status", "starting")
                    .put("running", true)
                    .put("entry", workspaceRelativePath(entry))
                    .put("cwd", workspaceRelativePath(cwd))
                    .put("runtime", "nodejs-mobile")
                    .put("nodeVersion", "18.20.4")
                    .put("toolCallCount", callCount);
        } catch (IOException error) {
            throw new CommandRuntime.CommandInputException("Invalid Node entry: " + error.getMessage());
        }
    }

    @Override
    public JSONObject nodeStatus(long callCount) throws JSONException {
        JSONObject state = NodeRuntimeState.read(this);
        boolean stateClaimedRunning = state.optBoolean("running", false);
        int pid = findNodeRuntimePid();
        boolean running = pid > 0;

        state.put("running", running)
                .put("runtime", "nodejs-mobile")
                .put("nodeVersion", "18.20.4")
                .put("toolCallCount", callCount);
        if (running) {
            state.put("pid", pid);
            if (!"starting".equals(state.optString("status"))) {
                state.put("status", "running");
            }
        } else if (stateClaimedRunning) {
            state.put("status", "stopped");
        }
        return state;
    }

    @Override
    public JSONObject nodeStop(JSONObject arguments, long callCount) throws JSONException {
        boolean force = arguments.optBoolean("force", false);
        int pid = findNodeRuntimePid();
        boolean wasRunning = pid > 0;

        if (wasRunning) {
            try {
                Os.kill(pid, force ? OsConstants.SIGKILL : OsConstants.SIGTERM);
            } catch (Exception error) {
                return new JSONObject()
                        .put("stopped", false)
                        .put("wasRunning", true)
                        .put("pid", pid)
                        .put("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                        .put("toolCallCount", callCount);
            }

            long deadline = SystemClock.elapsedRealtime() + 1000L;
            while (findNodeRuntimePid() > 0 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25L);
            }

            int remainingPid = findNodeRuntimePid();
            if (remainingPid > 0 && !force) {
                try {
                    Os.kill(remainingPid, OsConstants.SIGKILL);
                } catch (Exception ignored) {
                }
            }
        }

        JSONObject stopped = new JSONObject()
                .put("status", "stopped")
                .put("running", false)
                .put("stopped", true)
                .put("wasRunning", wasRunning)
                .put("force", force)
                .put("runtime", "nodejs-mobile")
                .put("nodeVersion", "18.20.4")
                .put("completedAt", Instant.now().toString())
                .put("toolCallCount", callCount);
        NodeRuntimeState.write(this, stopped);
        return stopped;
    }

    @Override
    public JSONObject appUpdate(JSONObject arguments, long callCount) throws JSONException {
        return SelfUpdateManager.start(this, arguments, callCount);
    }

    @Override
    public JSONObject appUpdateCheck(JSONObject arguments, long callCount) throws JSONException {
        return SelfUpdateManager.checkLatest(this, arguments, callCount);
    }

    @Override
    public JSONObject appUpdateLatest(JSONObject arguments, long callCount) throws JSONException {
        return SelfUpdateManager.startLatest(this, arguments, callCount);
    }

    @Override
    public JSONObject appUpdateStatus(long callCount) throws JSONException {
        return SelfUpdateManager.status(this, callCount);
    }

    @Override
    public synchronized JSONObject phoneRing(String action, int durationSeconds, long callCount) throws JSONException {
        if ("stop".equals(action)) {
            boolean wasPlaying = activeRing != null && activeRing.isPlaying();
            stopAlertSound();
            recordRing(callCount, "stopped");
            return new JSONObject()
                    .put("action", "stop")
                    .put("wasPlaying", wasPlaying)
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
        }

        stopAlertSound();
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0);
        }

        android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        activeRing = uri == null ? null : RingtoneManager.getRingtone(this, uri);
        if (activeRing == null) {
            restoreAlarmVolume();
            return new JSONObject()
                    .put("action", "start")
                    .put("playing", false)
                    .put("error", "No system alarm or ringtone is available")
                    .put("toolCallCount", callCount);
        }

        activeRing.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        activeRing.play();
        mainHandler.postDelayed(this::stopAlertSound, durationSeconds * 1000L);
        vibrate();
        recordRing(callCount, "playing for " + durationSeconds + "s");
        return new JSONObject()
                .put("action", "start")
                .put("playing", true)
                .put("durationSeconds", durationSeconds)
                .put("volume", "alarm_max_temporarily")
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject phoneLock(long callCount) throws JSONException {
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        boolean adminActive = policy != null && policy.isAdminActive(admin);
        if (!adminActive) {
            return new JSONObject()
                    .put("locked", false)
                    .put("adminActive", false)
                    .put("requiresSetup", true)
                    .put("setupAction", "enable_device_admin")
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
        }

        policy.lockNow();
        return new JSONObject()
                .put("locked", true)
                .put("adminActive", true)
                .put("requiresSetup", false)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    @Override
    @SuppressWarnings("deprecation")
    public JSONObject phoneWake(long callCount) throws JSONException {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) {
            return new JSONObject()
                    .put("woke", false)
                    .put("supported", false)
                    .put("error", "PowerManager is unavailable")
                    .put("timestamp", Instant.now().toString())
                    .put("toolCallCount", callCount);
        }

        boolean wasInteractive = power.isInteractive();
        long observationStartedAt = android.os.SystemClock.elapsedRealtime();
        boolean interactive = wasInteractive;
        if (!wasInteractive) {
            int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE;
            PowerManager.WakeLock wakeLock = power.newWakeLock(flags, "MCPocket:phoneWake");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire(3000L);
                long deadline = observationStartedAt + 1000L;
                do {
                    interactive = power.isInteractive();
                    if (interactive) {
                        break;
                    }
                    android.os.SystemClock.sleep(50L);
                } while (android.os.SystemClock.elapsedRealtime() < deadline);

                if (!interactive) {
                    interactive = power.isInteractive();
                }
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }

        long observationMs = android.os.SystemClock.elapsedRealtime() - observationStartedAt;
        boolean woke = !wasInteractive && interactive;
        return new JSONObject()
                .put("woke", woke)
                .put("wasInteractive", wasInteractive)
                .put("interactive", interactive)
                .put("observationMs", observationMs)
                .put("unlocked", false)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject phoneEcho(String text, long callCount) throws JSONException {
        vibrate();
        String inboxId = AgentInboxStore.add(this, "phone.echo", "PickPico Agent", text);
        String summary = "phone_echo #" + callCount + ": " + abbreviate(text, 80);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
        updateNotification(summary);
        return new JSONObject()
                .put("echo", text)
                .put("inboxId", inboxId)
                .put("executedOn", Build.MANUFACTURER + " " + Build.MODEL)
                .put("timestamp", Instant.now().toString())
                .put("action", "vibrated_and_updated_notification")
                .put("toolCallCount", callCount);
    }

    @Override
    public JSONObject cameraCapture(JSONObject arguments, long callCount) throws JSONException {
        if (deviceCapabilities == null) {
            return capabilityRuntimeUnavailable("captured", callCount);
        }
        JSONObject result = deviceCapabilities.captureCamera(arguments, callCount);
        recordCapabilityAction("camera.capture", result, callCount);
        return result;
    }

    @Override
    public JSONObject phoneNotify(JSONObject arguments, long callCount) throws JSONException {
        if (deviceCapabilities == null) {
            return capabilityRuntimeUnavailable("notified", callCount);
        }
        JSONObject result = deviceCapabilities.notifyUser(arguments, callCount);
        recordCapabilityAction("phone.notify", result, callCount);
        return result;
    }

    @Override
    public JSONObject phoneSpeak(JSONObject arguments, long callCount) throws JSONException {
        if (deviceCapabilities == null) {
            return capabilityRuntimeUnavailable("speaking", callCount);
        }
        JSONObject result = deviceCapabilities.speak(arguments, callCount);
        recordCapabilityAction("phone.speak", result, callCount);
        return result;
    }

    @Override
    public JSONObject microphoneRecord(JSONObject arguments, long callCount) throws JSONException {
        if (deviceCapabilities == null) {
            return capabilityRuntimeUnavailable("recorded", callCount);
        }
        JSONObject result = deviceCapabilities.recordMicrophone(arguments, callCount);
        recordCapabilityAction("microphone.record", result, callCount);
        return result;
    }

    @Override
    public JSONObject humanHelp(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = HumanHelpStore.createAndWait(this, arguments, callCount);
        recordCapabilityAction("human.help", result, callCount);
        return result;
    }

    @Override
    public JSONObject humanHelpStatus(JSONObject arguments, long callCount) throws JSONException {
        return HumanHelpStore.status(
                this,
                arguments.optString("requestId", ""),
                arguments.optBoolean("includeAttachmentData", true),
                callCount);
    }

    @Override
    public JSONObject notificationList(JSONObject arguments, long callCount) throws JSONException {
        return McpNotificationListenerService.list(
                this,
                Math.max(1, Math.min(200, arguments.optInt("limit", 50))),
                arguments.optBoolean("includeOwn", false),
                callCount);
    }

    @Override
    public JSONObject notificationGet(JSONObject arguments, long callCount) throws JSONException {
        return McpNotificationListenerService.get(this, arguments.optString("key", ""), callCount);
    }

    @Override
    public JSONObject notificationDismiss(JSONObject arguments, long callCount) throws JSONException {
        return McpNotificationListenerService.dismiss(this, arguments.optString("key", ""), callCount);
    }

    @Override
    public JSONObject notificationActions(JSONObject arguments, long callCount) throws JSONException {
        return McpNotificationListenerService.actions(this, arguments.optString("key", ""), callCount);
    }

    @Override
    public JSONObject notificationInvokeAction(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = McpNotificationListenerService.invokeAction(
                this,
                arguments.optString("key", ""),
                arguments.optInt("actionIndex", -1),
                callCount);
        recordCapabilityAction("notification.invoke_action", result, callCount);
        return result;
    }

    @Override
    public JSONObject notificationReply(JSONObject arguments, long callCount) throws JSONException {
        Integer actionIndex = arguments.has("actionIndex")
                ? arguments.optInt("actionIndex", -1)
                : null;
        JSONObject result = McpNotificationListenerService.reply(
                this,
                arguments.optString("key", ""),
                actionIndex,
                arguments.optString("text", ""),
                callCount);
        recordCapabilityAction("notification.reply", result, callCount);
        return result;
    }

    @Override
    public JSONObject uiInspect(JSONObject arguments, long callCount) throws JSONException {
        return McpAccessibilityService.inspect(arguments, callCount);
    }

    @Override
    public JSONObject uiAction(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = McpAccessibilityService.action(arguments, callCount);
        recordCapabilityAction("ui.action", result, callCount);
        return result;
    }

    @Override
    public JSONObject uiType(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = McpAccessibilityService.type(arguments, callCount);
        recordCapabilityAction("ui.type", result, callCount);
        return result;
    }

    @Override
    public JSONObject uiScroll(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = McpAccessibilityService.scroll(arguments, callCount);
        recordCapabilityAction("ui.scroll", result, callCount);
        return result;
    }

    @Override
    public JSONObject screenCapture(JSONObject arguments, long callCount) throws JSONException {
        return ScreenCaptureService.capture(arguments, callCount);
    }

    @Override
    public JSONObject appList(JSONObject arguments, long callCount) throws JSONException {
        return AndroidAgentActions.appList(this, arguments, callCount);
    }

    @Override
    public JSONObject appLaunch(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidAgentActions.appLaunch(this, arguments, callCount);
        recordCapabilityAction("app.launch", result, callCount);
        return result;
    }

    @Override
    public JSONObject urlOpen(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidAgentActions.urlOpen(this, arguments, callCount);
        recordCapabilityAction("url.open", result, callCount);
        return result;
    }

    @Override
    public JSONObject locationGet(JSONObject arguments, long callCount) throws JSONException {
        return AndroidAgentActions.locationGet(this, arguments, callCount);
    }

    @Override
    public JSONObject clipboardGet(long callCount) throws JSONException {
        return AndroidAgentActions.clipboardGet(this, callCount);
    }

    @Override
    public JSONObject clipboardSet(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidAgentActions.clipboardSet(this, arguments, callCount);
        recordCapabilityAction("clipboard.set", result, callCount);
        return result;
    }

    @Override
    public JSONObject contactsSearch(JSONObject arguments, long callCount) throws JSONException {
        return AndroidPersonalDataActions.contactsSearch(this, arguments, callCount);
    }

    @Override
    public JSONObject contactsGet(JSONObject arguments, long callCount) throws JSONException {
        return AndroidPersonalDataActions.contactsGet(this, arguments, callCount);
    }

    @Override
    public JSONObject calendarList(JSONObject arguments, long callCount) throws JSONException {
        return AndroidPersonalDataActions.calendarList(this, arguments, callCount);
    }

    @Override
    public JSONObject calendarGet(JSONObject arguments, long callCount) throws JSONException {
        return AndroidPersonalDataActions.calendarGet(this, arguments, callCount);
    }

    @Override
    public JSONObject calendarCreate(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidPersonalDataActions.calendarCreate(this, arguments, callCount);
        recordCapabilityAction("calendar.create", result, callCount);
        return result;
    }

    @Override
    public JSONObject calendarUpdate(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidPersonalDataActions.calendarUpdate(this, arguments, callCount);
        recordCapabilityAction("calendar.update", result, callCount);
        return result;
    }

    @Override
    public JSONObject calendarDelete(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidPersonalDataActions.calendarDelete(this, arguments, callCount);
        recordCapabilityAction("calendar.delete", result, callCount);
        return result;
    }

    @Override
    public JSONObject filePick(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = PickerRequestStore.createAndWait(this, arguments, false, callCount);
        recordCapabilityAction("file.pick", result, callCount);
        return result;
    }

    @Override
    public JSONObject mediaPick(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = PickerRequestStore.createAndWait(this, arguments, true, callCount);
        recordCapabilityAction("media.pick", result, callCount);
        return result;
    }

    @Override
    public JSONObject shareSend(JSONObject arguments, long callCount) throws JSONException {
        JSONObject result = AndroidPersonalDataActions.shareSend(this, workspaceRoot(), arguments, callCount);
        recordCapabilityAction("share.send", result, callCount);
        return result;
    }

    private static String approvalArgumentSummary(String commandId, JSONObject arguments) {
        if (arguments == null || arguments.length() == 0) {
            return "";
        }
        try {
            JSONObject summary = new JSONObject();
            if ("process.exec".equals(commandId)) {
                summary.put("command", arguments.optString("command", ""));
                if (!arguments.optString("cwd", "").isEmpty()) {
                    summary.put("cwd", arguments.optString("cwd", ""));
                }
            } else if ("workspace.write".equals(commandId)) {
                summary.put("path", arguments.optString("path", ""));
                summary.put("append", arguments.optBoolean("append", false));
                summary.put("contentCharacters", arguments.optString("content", "").length());
            } else if ("app.update".equals(commandId)) {
                summary.put("url", arguments.optString("url", ""));
                String sha = arguments.optString("sha256", "");
                summary.put("sha256", sha.length() > 16 ? sha.substring(0, 16) + "…" : sha);
            } else {
                Iterator<String> keys = arguments.keys();
                while (keys.hasNext() && summary.length() < 8) {
                    String key = keys.next();
                    String lower = key.toLowerCase();
                    if (lower.contains("token")
                            || lower.contains("secret")
                            || lower.contains("password")
                            || lower.contains("authorization")
                            || "stdin".equals(lower)
                            || "env".equals(lower)) {
                        summary.put(key, "<redacted>");
                        continue;
                    }
                    Object value = arguments.opt(key);
                    String rendered = value == null || value == JSONObject.NULL
                            ? "null"
                            : String.valueOf(value);
                    summary.put(key, rendered.length() > 240
                            ? rendered.substring(0, 240) + "…"
                            : rendered);
                }
            }
            return summary.length() == 0 ? "" : "\nArguments: " + summary.toString();
        } catch (JSONException ignored) {
            return "";
        }
    }

    private JSONObject capabilityRuntimeUnavailable(String resultKey, long callCount) throws JSONException {
        return new JSONObject()
                .put(resultKey, false)
                .put("errorCode", "capability_runtime_unavailable")
                .put("toolCallCount", callCount);
    }

    private void recordCapabilityAction(String capability, JSONObject result, long callCount) {
        String detail = result.optString("path", result.optString("errorCode", "ok"));
        String summary = capability + " #" + callCount + ": " + abbreviate(detail, 80);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
    }

    private void stopNode() {
        nodeActive = false;
        stopAlertSound();
        stopRelay();
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_DESIRED_RUNNING, false)
                .putBoolean(KEY_MEDIA_FOREGROUND_REQUESTED, false)
                .putString(KEY_ENDPOINT, "")
                .putString(KEY_REMOTE_ENDPOINT, "")
                .putString(KEY_RELAY_STATUS, "stopped")
                .putString(KEY_TOKEN, "")
                .putString(KEY_RECENT, "Node stopped at " + Instant.now())
                .remove(KEY_ERROR)
                .apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startRelayIfConfigured() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String relayBaseUrl = prefs.getString(KEY_RELAY_BASE_URL, "");
        if (TextUtils.isEmpty(relayBaseUrl)) {
            prefs.edit()
                    .putString(KEY_RELAY_STATUS, "disabled")
                    .putString(KEY_REMOTE_ENDPOINT, "")
                    .apply();
            return;
        }
        relayBaseUrl = RelayClient.migrateLegacyRelayIfNeeded(prefs, relayBaseUrl);
        stopRelay();
        relayClient = new RelayClient(this, relayBaseUrl, new RelayClient.Listener() {
            @Override
            public void onRelayState(String status, String remoteEndpoint, String detail) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_RELAY_STATUS, status)
                        .putString(KEY_REMOTE_ENDPOINT, remoteEndpoint == null ? "" : remoteEndpoint);
                if (detail != null && !detail.isEmpty()) {
                    editor.putString(KEY_RECENT, "relay: " + detail + "\n" + Instant.now());
                }
                editor.apply();
            }
        });
        relayClient.start();
    }

    private void stopRelay() {
        if (relayClient != null) {
            relayClient.close();
            relayClient = null;
        }
    }

    private void recordFailure(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_ERROR, message)
                .apply();
    }

    private void startAsForeground(String message) {
        Notification notification = buildNotification(message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (mediaForegroundRequested
                        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (mediaForegroundRequested
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
            }
            if (mediaForegroundRequested
                    && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (types != 0) {
                startForeground(NOTIFICATION_ID, notification, types);
                return;
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private Notification buildNotification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("PickPico node is running")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PickPico node",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when the local MCP execution node is reachable");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private synchronized void stopAlertSound() {
        mainHandler.removeCallbacksAndMessages(null);
        if (activeRing != null) {
            try {
                activeRing.stop();
            } catch (Exception ignored) {
            }
            activeRing = null;
        }
        restoreAlarmVolume();
    }

    private void restoreAlarmVolume() {
        if (previousAlarmVolume < 0) {
            return;
        }
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
        }
        previousAlarmVolume = -1;
    }

    private void recordRing(long callCount, String state) {
        String summary = "phone_ring #" + callCount + ": " + state;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
        updateNotification(summary);
    }

    private static String findLanAddress() {
        String siteLocalFallback = null;
        String publicFallback = null;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String candidate = address.getHostAddress();
                        String interfaceName = network.getName().toLowerCase(java.util.Locale.ROOT);
                        if ((interfaceName.startsWith("wlan") || interfaceName.startsWith("wifi"))
                                && address.isSiteLocalAddress()) {
                            return candidate;
                        }
                        if (address.isSiteLocalAddress() && siteLocalFallback == null) {
                            siteLocalFallback = candidate;
                        } else if (publicFallback == null) {
                            publicFallback = candidate;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // The server still starts; the UI falls back to loopback until networking is available.
        }
        if (siteLocalFallback != null) {
            return siteLocalFallback;
        }
        return publicFallback == null ? "127.0.0.1" : publicFallback;
    }

    private static String activeTransport(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return "none";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ethernet";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "vpn";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return "bluetooth";
        }
        return "other";
    }

    private File workspaceRoot() {
        File root = new File(getFilesDir(), "workspaces");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Unable to create PickPico workspace root");
        }
        return root;
    }

    private int findNodeRuntimePid() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) {
            return -1;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return -1;
        }

        String expectedProcessName = getPackageName() + ":node";
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (expectedProcessName.equals(process.processName)) {
                return process.pid;
            }
        }
        return -1;
    }

    private File resolveExecDirectory(String cwd) throws IOException {
        if (cwd == null || cwd.isEmpty() || ".".equals(cwd)) {
            return workspaceRoot().getCanonicalFile();
        }
        File requested = new File(cwd);
        if (requested.isAbsolute()) {
            return requested.getCanonicalFile();
        }
        return resolveWorkspacePath(cwd);
    }

    private File resolveWorkspacePath(String path) throws IOException {
        File root = workspaceRoot().getCanonicalFile();
        String relative = path == null || path.isEmpty() ? "." : path;
        File candidate = new File(root, relative).getCanonicalFile();
        String rootPath = root.getPath();
        String candidatePath = candidate.getPath();
        if (!candidatePath.equals(rootPath)
                && !candidatePath.startsWith(rootPath + File.separator)) {
            throw new CommandRuntime.CommandInputException("Workspace path escapes the workspace root: " + relative);
        }
        return candidate;
    }

    private String workspaceRelativePath(File file) throws IOException {
        File root = workspaceRoot().getCanonicalFile();
        File canonical = file.getCanonicalFile();
        String rootPath = root.getPath();
        String candidatePath = canonical.getPath();
        if (candidatePath.equals(rootPath)) {
            return ".";
        }
        if (!candidatePath.startsWith(rootPath + File.separator)) {
            throw new CommandRuntime.CommandInputException("Path is outside the workspace root");
        }
        return candidatePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private boolean appendWorkspaceEntries(
            File directory,
            int maxDepth,
            int maxEntries,
            JSONArray entries) throws IOException, JSONException {
        return appendWorkspaceEntries(directory, 0, maxDepth, maxEntries, entries);
    }

    private boolean appendWorkspaceEntries(
            File directory,
            int depth,
            int maxDepth,
            int maxEntries,
            JSONArray entries) throws IOException, JSONException {
        File[] children = directory.listFiles();
        if (children == null) {
            return false;
        }
        Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File child : children) {
            if (entries.length() >= maxEntries) {
                return true;
            }
            File canonical = child.getCanonicalFile();
            String relative;
            try {
                relative = workspaceRelativePath(canonical);
            } catch (CommandRuntime.CommandInputException ignored) {
                continue;
            }
            entries.put(new JSONObject()
                    .put("path", relative)
                    .put("name", child.getName())
                    .put("type", canonical.isDirectory() ? "directory" : "file")
                    .put("sizeBytes", canonical.isFile() ? canonical.length() : 0L)
                    .put("modifiedEpochMs", canonical.lastModified()));
            if (canonical.isDirectory() && depth < maxDepth) {
                if (appendWorkspaceEntries(canonical, depth + 1, maxDepth, maxEntries, entries)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            path = "/system/bin:/system/xbin";
        }
        for (String directory : path.split(":")) {
            if (directory.isEmpty()) {
                continue;
            }
            File candidate = new File(directory, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private synchronized void rememberProcessSession(ProcessSession session) {
        Iterator<Map.Entry<String, ProcessSession>> iterator = processSessions.entrySet().iterator();
        while (processSessions.size() >= MAX_PROCESS_SESSIONS && iterator.hasNext()) {
            ProcessSession candidate = iterator.next().getValue();
            if (!candidate.process.isAlive()) {
                iterator.remove();
            }
        }
        if (processSessions.size() >= MAX_PROCESS_SESSIONS) {
            session.process.destroyForcibly();
            throw new CommandRuntime.CommandInputException(
                    "Too many running PickPico process sessions; stop one before starting another");
        }
        processSessions.put(session.sessionId, session);
    }

    private synchronized ProcessSession requireProcessSession(String sessionId) {
        ProcessSession session = processSessions.get(sessionId);
        if (session == null) {
            throw new CommandRuntime.CommandInputException("Unknown process session: " + sessionId);
        }
        return session;
    }

    private JSONObject processSessionResult(ProcessSession session, long callCount) throws JSONException {
        boolean running = session.process.isAlive();
        if (!running) {
            try {
                joinCapture(session.stdoutThread);
                joinCapture(session.stderrThread);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        JSONObject result = new JSONObject()
                .put("sessionId", session.sessionId)
                .put("command", session.command)
                .put("shell", "/system/bin/sh")
                .put("cwd", session.cwd)
                .put("background", true)
                .put("status", running ? "running" : "exited")
                .put("running", running)
                .put("stdout", session.stdout.text())
                .put("stderr", session.stderr.text())
                .put("stdoutTruncated", session.stdout.truncated())
                .put("stderrTruncated", session.stderr.truncated())
                .put("durationMs", SystemClock.elapsedRealtime() - session.startedElapsed)
                .put("startedAt", session.startedAt)
                .put("toolCallCount", callCount);
        if (!running) {
            try {
                result.put("exitCode", session.process.exitValue());
            } catch (IllegalThreadStateException ignored) {
                result.put("exitCode", JSONObject.NULL);
            }
        } else {
            result.put("exitCode", JSONObject.NULL);
        }
        return result;
    }

    private synchronized void stopAllProcessSessions() {
        for (ProcessSession session : processSessions.values()) {
            if (session.process.isAlive()) {
                terminateProcessGroup(session, true);
            }
        }
        processSessions.clear();
    }

    private static void terminateProcessGroup(ProcessSession session, boolean force) {
        terminateProcessGroup(session.process, session.processGroupId, force);
    }

    private static void terminateProcessGroup(Process process, int processGroupId, boolean force) {
        int signal = force ? OsConstants.SIGKILL : OsConstants.SIGTERM;
        try {
            if (processGroupId > 0) {
                Os.kill(-processGroupId, signal);
            } else if (force) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (Exception ignored) {
            if (force) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }

        try {
            process.waitFor(750L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        if (process.isAlive()) {
            try {
                if (processGroupId > 0) {
                    Os.kill(-processGroupId, OsConstants.SIGKILL);
                } else {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
            try {
                process.waitFor(750L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int waitForProcessGroupId(File pidFile, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pidFile.isFile() && pidFile.length() > 0L) {
                String text = readProcessOutput(new FileInputStream(pidFile), 64).trim();
                if (!text.isEmpty()) {
                    int pid = Integer.parseInt(text);
                    if (pid > 0) {
                        return pid;
                    }
                }
            }
            Thread.sleep(10L);
        }
        throw new IOException("Timed out waiting for process group ID");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static List<String> commandArgv(String command) {
        switch (command) {
            case "identity":
                return Collections.singletonList("/system/bin/id");
            case "kernel":
                return Arrays.asList("/system/bin/uname", "-a");
            case "model_property":
                return Arrays.asList("/system/bin/getprop", "ro.product.model");
            case "data_disk":
                return Arrays.asList("/system/bin/df", "-h", "/data");
            default:
                return null;
        }
    }

    private void recordExec(String command, long callCount, String result) {
        String summary = "exec #" + callCount + ": " + abbreviate(command, 80) + " (" + result + ")";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
        updateNotification(summary);
    }

    private static JSONObject execFailure(
            String command,
            String cwd,
            String error,
            long started,
            long callCount) throws JSONException {
        return new JSONObject()
                .put("command", command)
                .put("shell", "/system/bin/sh")
                .put("cwd", cwd.isEmpty() ? JSONObject.NULL : cwd)
                .put("executed", false)
                .put("timedOut", false)
                .put("error", error)
                .put("durationMs", SystemClock.elapsedRealtime() - started)
                .put("timestamp", Instant.now().toString())
                .put("toolCallCount", callCount);
    }

    private static void joinCapture(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(250L);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ProcessSession {
        final String sessionId;
        final String command;
        final String cwd;
        final int processGroupId;
        final Process process;
        final StreamCapture stdout;
        final StreamCapture stderr;
        final Thread stdoutThread;
        final Thread stderrThread;
        final long startedElapsed;
        final String startedAt;

        ProcessSession(
                String sessionId,
                String command,
                String cwd,
                int processGroupId,
                Process process,
                StreamCapture stdout,
                StreamCapture stderr,
                Thread stdoutThread,
                Thread stderrThread,
                long startedElapsed) {
            this.sessionId = sessionId;
            this.command = command;
            this.cwd = cwd;
            this.processGroupId = processGroupId;
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
            this.startedElapsed = startedElapsed;
            this.startedAt = Instant.now().toString();
        }
    }

    private static final class StreamCapture implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private volatile boolean truncated;

        StreamCapture(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int remaining = maxBytes - captured.size();
                    if (remaining > 0) {
                        int accepted = Math.min(read, remaining);
                        captured.write(buffer, 0, accepted);
                        if (accepted < read) {
                            truncated = true;
                        }
                    } else if (read > 0) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }
    }

    private static String readProcessOutput(InputStream input, int maxBytes) throws Exception {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                int accepted = Math.min(read, maxBytes - total);
                if (accepted > 0) {
                    output.write(buffer, 0, accepted);
                    total += accepted;
                }
                if (total >= maxBytes) {
                    break;
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }
}
