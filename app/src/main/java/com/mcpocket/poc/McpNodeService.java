package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class McpNodeService extends Service implements McpToolActions {
    public static final String ACTION_START = "com.mcpocket.poc.action.START";
    public static final String ACTION_STOP = "com.mcpocket.poc.action.STOP";
    public static final String EXTRA_TOKEN = "token";

    public static final String PREFS = "mcpocket_node";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_RECENT = "recent";
    public static final String KEY_CALL_COUNT = "call_count";
    public static final String KEY_ERROR = "error";

    private static final int PORT = 8765;
    private static final int NOTIFICATION_ID = 8765;
    private static final String CHANNEL_ID = "mcpocket_node";
    private static volatile boolean nodeActive;

    private McpHttpServer server;
    private String endpoint = "";
    private long startedElapsed;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Ringtone activeRing;
    private int previousAlarmVolume = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopNode();
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

        nodeActive = true;
        startAsForeground("Starting local MCP server…");
        try {
            endpoint = "http://" + findLanAddress() + ":" + PORT + "/mcp";
            server = new McpHttpServer(PORT, token, this);
            server.start();
            startedElapsed = SystemClock.elapsedRealtime();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_RUNNING, true)
                    .putString(KEY_ENDPOINT, endpoint)
                    .putString(KEY_TOKEN, token)
                    .putString(KEY_RECENT, "Node started at " + Instant.now())
                    .putLong(KEY_CALL_COUNT, 0L)
                    .remove(KEY_ERROR)
                    .apply();
            updateNotification("Listening on " + endpoint);
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
        stopAlertSound();
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isNodeRunning() {
        return nodeActive;
    }

    @Override
    public JSONObject serverInfo(long callCount) throws JSONException {
        long uptimeMs = startedElapsed == 0L ? 0L : SystemClock.elapsedRealtime() - startedElapsed;
        return new JSONObject()
                .put("name", "MCPocket")
                .put("version", "0.5.0")
                .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("endpoint", endpoint)
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
                .put("node", new JSONObject()
                        .put("endpoint", endpoint)
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
    public JSONObject phoneEcho(String text, long callCount) throws JSONException {
        vibrate();
        String summary = "phone_echo #" + callCount + ": " + abbreviate(text, 80);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
        updateNotification(summary);
        return new JSONObject()
                .put("echo", text)
                .put("executedOn", Build.MANUFACTURER + " " + Build.MODEL)
                .put("timestamp", Instant.now().toString())
                .put("action", "vibrated_and_updated_notification")
                .put("toolCallCount", callCount);
    }

    private void stopNode() {
        nodeActive = false;
        stopAlertSound();
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_ENDPOINT, "")
                .putString(KEY_TOKEN, "")
                .putString(KEY_RECENT, "Node stopped at " + Instant.now())
                .remove(KEY_ERROR)
                .apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void recordFailure(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_ERROR, message)
                .apply();
    }

    private void startAsForeground(String message) {
        Notification notification = buildNotification(message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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
                .setContentTitle("MCPocket node is running")
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
                "MCPocket node",
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
        String summary = "phone_exec #" + callCount + ": " + command + " (" + result + ")";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, summary + "\n" + Instant.now())
                .putLong(KEY_CALL_COUNT, callCount)
                .apply();
        updateNotification(summary);
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
