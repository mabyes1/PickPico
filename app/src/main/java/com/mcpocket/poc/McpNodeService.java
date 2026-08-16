package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.time.Instant;
import java.util.Collections;

public final class McpNodeService extends Service implements McpHttpServer.ToolActions {
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
                .put("version", "0.1.0")
                .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("endpoint", endpoint)
                .put("uptimeSeconds", uptimeMs / 1000L)
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

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }
}
