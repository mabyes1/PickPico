package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** ADB-shell-only foreground service for debug smoke tests. */
public final class DevBridgeService extends Service {
    private static final int NOTIFICATION_ID = 8766;
    private static final String CHANNEL_ID = "mcpocket_dev_bridge";
    private static final String DEV_RESULT_FILE = "dev-last-command.json";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Ringtone activeRing;
    private int previousAlarmVolume = -1;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PickPico debug bridge",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            stopBridge();
            return START_NOT_STICKY;
        }

        String action = intent == null ? "" : intent.getStringExtra("action");
        if ("start_node".equals(action)) {
            startNode();
            mainHandler.postDelayed(this::stopBridge, 750L);
            return START_NOT_STICKY;
        }
        if ("mcp_command".equals(action)) {
            String commandId = intent.getStringExtra("commandId");
            String argumentsBase64 = intent.getStringExtra("argumentsBase64");
            runMcpCommand(commandId, argumentsBase64);
            return START_NOT_STICKY;
        }
        if ("stop_ring".equals(action)) {
            stopBridge();
            return START_NOT_STICKY;
        }
        if (!"ring".equals(action)) {
            stopBridge();
            return START_NOT_STICKY;
        }

        int duration = intent.getIntExtra("durationSeconds", 10);
        duration = Math.max(5, Math.min(60, duration));
        playRing(duration);
        mainHandler.postDelayed(this::stopBridge, duration * 1000L + 250L);
        return START_NOT_STICKY;
    }

    private void startNode() {
        byte[] tokenBytes = new byte[24];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Intent node = new Intent(this, McpNodeService.class)
                .setAction(McpNodeService.ACTION_START)
                .putExtra(McpNodeService.EXTRA_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(node);
        } else {
            startService(node);
        }
    }

    private void runMcpCommand(String commandId, String argumentsBase64) {
        new Thread(() -> {
            try {
                String token = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE)
                        .getString(McpNodeService.KEY_TOKEN, "");
                if (token.isEmpty()) {
                    throw new IllegalStateException("MCP node token is unavailable");
                }

                String argumentsJson = "{}";
                if (argumentsBase64 != null && !argumentsBase64.isEmpty()) {
                    argumentsJson = new String(
                            Base64.getUrlDecoder().decode(argumentsBase64),
                            StandardCharsets.UTF_8);
                }

                JSONObject body = new JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", 1)
                        .put("method", "tools/call")
                        .put("params", new JSONObject()
                                .put("name", "command_run")
                                .put("arguments", new JSONObject()
                                        .put("commandId", commandId == null ? "" : commandId)
                                        .put("arguments", new JSONObject(argumentsJson))));

                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:8765/mcp").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

                int status = connection.getResponseCode();
                InputStream stream = status >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                String response = stream == null ? "" : readAll(stream);
                connection.disconnect();

                writeDevResult(new JSONObject()
                        .put("httpStatus", status)
                        .put("commandId", commandId == null ? "" : commandId)
                        .put("response", response.isEmpty() ? JSONObject.NULL : new JSONObject(response)));
            } catch (Exception error) {
                try {
                    writeDevResult(new JSONObject()
                            .put("commandId", commandId == null ? "" : commandId)
                            .put("error", error.getClass().getSimpleName() + ": " + error.getMessage()));
                } catch (Exception ignored) {
                    // Debug-only best effort.
                }
            } finally {
                mainHandler.post(this::stopBridge);
            }
        }, "mcpocket-dev-command").start();
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void writeDevResult(JSONObject result) throws Exception {
        File target = new File(getFilesDir(), DEV_RESULT_FILE);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void startAsForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("PickPico debug bridge")
                .setContentText("ADB smoke-test action is running")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void playRing(int durationSeconds) {
        stopSound();
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
        if (activeRing != null) {
            activeRing.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            activeRing.play();
        }

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void stopBridge() {
        mainHandler.removeCallbacksAndMessages(null);
        stopSound();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopSound() {
        if (activeRing != null) {
            activeRing.stop();
            activeRing = null;
        }
        if (previousAlarmVolume >= 0) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            }
            previousAlarmVolume = -1;
        }
    }

    @Override
    public void onDestroy() {
        stopSound();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
