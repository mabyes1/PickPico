package com.mcpocket.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import java.io.File;
import java.time.Instant;

public final class NodeRuntimeService extends Service {
    static final String ACTION_START = "com.mcpocket.poc.node.START";
    static final String EXTRA_ENTRY = "entry";
    static final String EXTRA_CWD = "cwd";

    private static final int NOTIFICATION_ID = 8767;
    private static final String CHANNEL_ID = "mcpocket_node_runtime";
    private volatile boolean started;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PickPico Node runtime",
                NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started || intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        String entry = intent.getStringExtra(EXTRA_ENTRY);
        String cwd = intent.getStringExtra(EXTRA_CWD);
        if (entry == null || cwd == null || !new File(entry).isFile() || !new File(cwd).isDirectory()) {
            NodeRuntimeState.write(this, NodeRuntimeState.state(
                    "status", "failed",
                    "running", false,
                    "error", "Invalid Node entry or cwd",
                    "timestamp", Instant.now().toString()));
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }

        started = true;
        startAsForeground(entry);
        NodeRuntimeState.write(this, NodeRuntimeState.state(
                "status", "starting",
                "running", true,
                "pid", Process.myPid(),
                "entry", entry,
                "cwd", cwd,
                "startedAt", Instant.now().toString()));

        new Thread(() -> runNode(cwd, entry), "mcpocket-node-runtime").start();
        return START_NOT_STICKY;
    }

    private void runNode(String cwd, String entry) {
        int exitCode;
        try {
            NodeRuntimeState.write(this, NodeRuntimeState.state(
                    "status", "running",
                    "running", true,
                    "pid", Process.myPid(),
                    "entry", entry,
                    "cwd", cwd,
                    "startedAt", Instant.now().toString()));
            exitCode = NodeRuntimeBridge.startNode(cwd, new String[]{"node", entry});
            NodeRuntimeState.write(this, NodeRuntimeState.state(
                    "status", "exited",
                    "running", false,
                    "pid", Process.myPid(),
                    "entry", entry,
                    "cwd", cwd,
                    "exitCode", exitCode,
                    "completedAt", Instant.now().toString()));
        } catch (Throwable error) {
            NodeRuntimeState.write(this, NodeRuntimeState.state(
                    "status", "failed",
                    "running", false,
                    "pid", Process.myPid(),
                    "entry", entry,
                    "cwd", cwd,
                    "error", error.getClass().getSimpleName() + ": " + error.getMessage(),
                    "completedAt", Instant.now().toString()));
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            // nodejs-mobile cannot safely initialize node::Start twice in the
            // same Linux process. The :node process is intentionally disposable:
            // every completed run must force Android to create a fresh process
            // for the next node.start invocation.
            Process.killProcess(Process.myPid());
        }
    }

    private void startAsForeground(String entry) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("PickPico Node runtime")
                .setContentText(new File(entry).getName())
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
