package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.time.Instant;

public final class SelfUpdateInstallReceiver extends BroadcastReceiver {
    private static final String UPDATE_CHANNEL_ID = "mcpocket_self_update";
    private static final int UPDATE_NOTIFICATION_ID = 8768;

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent == null
                ? PackageInstaller.STATUS_FAILURE
                : intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent == null
                ? "Missing PackageInstaller result"
                : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            int sessionId = intent.getIntExtra("sessionId", 0);
            JSONObject state = SelfUpdateState.read(context);
            SelfUpdateState.put(state, "status", "pending_user_action");
            SelfUpdateState.put(state, "running", true);
            SelfUpdateState.put(state, "message", message == null ? "Android confirmation required" : message);
            SelfUpdateState.put(state, "confirmationNotification", true);
            SelfUpdateState.put(state, "updatedAt", Instant.now().toString());
            SelfUpdateState.write(context, state);

            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                SelfUpdateManager.setPendingConfirmationIntent(confirmation);
                showConfirmationNotification(context, confirmation, sessionId);
                try {
                    context.startActivity(confirmation);
                } catch (Throwable ignored) {
                    // Background activity launch may be blocked. The notification remains as the reliable path.
                }
            }
            return;
        }

        JSONObject state = SelfUpdateState.read(context);
        boolean success = status == PackageInstaller.STATUS_SUCCESS;
        SelfUpdateState.put(state, "status", success ? "installed" : "failed");
        SelfUpdateState.put(state, "running", false);
        SelfUpdateState.put(state, "installerStatus", status);
        if (message != null && !message.isEmpty()) {
            SelfUpdateState.put(state, "message", message);
        }
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(UPDATE_NOTIFICATION_ID);
        SelfUpdateState.put(state, "completedAt", Instant.now().toString());
        if (success) {
            File candidate = SelfUpdateState.candidateFile(context);
            if (candidate.exists()) {
                candidate.delete();
            }
            // NodeRestoreReceiver owns post-update runtime restoration via
            // ACTION_MY_PACKAGE_REPLACED. Keeping a second restart path here races
            // package replacement and can trigger restricted foreground-service types
            // while PickPico is still backgrounded.
        }
        SelfUpdateState.write(context, state);
        SelfUpdateManager.markFinished();
    }

    private static void showConfirmationNotification(Context context, Intent confirmation, int sessionId) {
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
                sessionId == 0 ? UPDATE_NOTIFICATION_ID : sessionId,
                confirmation,
                flags);

        Notification notification = new Notification.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PickPico update ready")
                .setContentText("Tap to confirm the Android app update")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(UPDATE_NOTIFICATION_ID, notification);
    }

}
