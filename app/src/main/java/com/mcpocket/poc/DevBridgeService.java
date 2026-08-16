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

/** ADB-shell-only foreground service for debug smoke tests. */
public final class DevBridgeService extends Service {
    private static final int NOTIFICATION_ID = 8766;
    private static final String CHANNEL_ID = "mcpocket_dev_bridge";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Ringtone activeRing;
    private int previousAlarmVolume = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MCPocket debug bridge",
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

    private void startAsForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("MCPocket debug bridge")
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
