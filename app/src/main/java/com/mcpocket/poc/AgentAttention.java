package com.mcpocket.poc;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared attention policy for Agent-originated notifications and human handoffs. */
final class AgentAttention {
    static final String EXTRA_FORWARD_INTENT = "com.mcpocket.poc.extra.HYPER_FORWARD_INTENT";
    private static final long SCREEN_WAKE_MS = 4500L;
    private static final long[] DOUBLE_PULSE_PATTERN_MS = {0L, 115L, 90L, 115L};
    private static final String VOICE_ASSET = "pickpico_voice.b64";
    private static final Object VOICE_LOCK = new Object();
    private static volatile File voiceFile;
    private static volatile MediaPlayer voicePlayer;

    private AgentAttention() {
    }

    static void configureUrgentChannel(NotificationChannel channel) {
        if (channel == null) return;
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // PickPico owns the two-pulse cue below so every urgent channel has the
        // same recognizable rhythm instead of a vendor-specific default buzz.
        channel.enableVibration(false);
        channel.setSound(null, null);
    }

    static boolean applyUrgentBehavior(Context context, Notification.Builder builder, int requestCode) {
        return applyUrgentBehavior(context, builder, requestCode, null);
    }

    static boolean applyUrgentBehavior(
            Context context,
            Notification.Builder builder,
            int requestCode,
            Intent forwardIntent) {
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH);
        if (!McpocketPolicySettings.isHyperModeEnabled(context)) return false;

        if (!canUseHyperUnlock(context)) return false;

        Intent unlock = new Intent(context, HyperUnlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);
        if (forwardIntent != null) {
            unlock.putExtra(EXTRA_FORWARD_INTENT, new Intent(forwardIntent));
        }
        int pendingRequestCode = 15000 + Math.abs(requestCode % 10000);
        PendingIntent fullScreen;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions options = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            fullScreen = PendingIntent.getActivity(
                    context,
                    pendingRequestCode,
                    unlock,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    options.toBundle());
        } else {
            fullScreen = PendingIntent.getActivity(
                    context,
                    pendingRequestCode,
                    unlock,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        builder.setFullScreenIntent(fullScreen, true);
        return true;
    }

    static boolean canUseHyperUnlock(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.canUseFullScreenIntent();
    }

    static boolean canLaunchBackgroundActivities(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context);
    }

    static boolean canStartActivityNow(Context context) {
        if (PickPicoApplication.isAppInForeground()) return true;
        if (!McpocketPolicySettings.isHyperModeEnabled(context)
                || !canLaunchBackgroundActivities(context)) {
            return false;
        }
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    static boolean requestBackgroundLaunchAccessIfNeeded(Activity activity) {
        if (canLaunchBackgroundActivities(activity)) return false;
        try {
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean requestHyperUnlockAccessIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || canUseHyperUnlock(activity)) {
            return false;
        }
        try {
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void applyPublicLockscreen(Notification.Builder builder) {
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH);
    }

    static void alert(Context context) {
        wakeScreen(context);
        vibrateCue(context);
        playVoiceCueIfAudible(context);
    }

    @SuppressWarnings("deprecation")
    static void wakeScreen(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) return;
        int flags = PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE;
        PowerManager.WakeLock wakeLock = power.newWakeLock(flags, "PickPico:agentAttention");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire(SCREEN_WAKE_MS);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (wakeLock.isHeld()) wakeLock.release();
            }, 2500L);
        } catch (RuntimeException ignored) {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrateCue(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(DOUBLE_PULSE_PATTERN_MS, -1));
            } else {
                vibrator.vibrate(DOUBLE_PULSE_PATTERN_MS, -1);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void playVoiceCueIfAudible(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null
                || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
                || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0) {
            return;
        }

        Context app = context.getApplicationContext();
        synchronized (VOICE_LOCK) {
            try {
                File cue = ensureVoiceFile(app);
                if (voicePlayer != null) {
                    try {
                        voicePlayer.stop();
                    } catch (RuntimeException ignored) {
                    }
                    voicePlayer.release();
                    voicePlayer = null;
                }
                MediaPlayer player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setDataSource(cue.getAbsolutePath());
                player.setOnCompletionListener(done -> {
                    synchronized (VOICE_LOCK) {
                        if (voicePlayer == done) voicePlayer = null;
                        done.release();
                    }
                });
                player.prepare();
                voicePlayer = player;
                player.start();
            } catch (Throwable ignored) {
            }
        }
    }

    private static File ensureVoiceFile(Context context) throws Exception {
        File cached = voiceFile;
        if (cached != null && cached.isFile() && cached.length() > 0L) return cached;

        File output = new File(context.getCacheDir(), "pickpico_attention_voice.mp3");
        if (!output.isFile() || output.length() == 0L) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try (InputStream input = context.getAssets().open(VOICE_ASSET)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) encoded.write(buffer, 0, read);
            }
            byte[] mp3 = Base64.decode(
                    new String(encoded.toByteArray(), StandardCharsets.US_ASCII),
                    Base64.DEFAULT);
            try (FileOutputStream sink = new FileOutputStream(output)) {
                sink.write(mp3);
            }
        }
        voiceFile = output;
        return output;
    }
}
