package com.mcpocket.poc;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;
import java.io.File;

/** Disposable development process. Never executes project code in the phone-control process. */
public class DevJobService extends Service {
    private File directory;
    private JSONObject state;
    private boolean started, finished;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    public static final class Slot0 extends DevJobService { }
    public static final class Slot1 extends DevJobService { }
    public static final class Slot2 extends DevJobService { }
    public static final class Slot3 extends DevJobService { }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started || intent == null) return START_NOT_STICKY;
        started = true;
        try {
            String id = intent.getStringExtra("jobId");
            if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid job id");
            directory = new File(new File(getFilesDir(), "dev-jobs"), id);
            state = DevWorkbench.read(new File(directory, "status.json"));
            if (!"starting".equals(state.optString("status")) || new File(directory,"cancel").exists()) { finishJob(130, "cancelled"); return START_NOT_STICKY; }
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("dev_jobs", "Development jobs", NotificationManager.IMPORTANCE_LOW));
            Notification notification = new Notification.Builder(this, "dev_jobs")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("PickPico development")
                    .setContentText(state.optString("project") + " · " + state.optString("operation"))
                    .setOngoing(true).build();
            int notificationId = 8800 + state.getInt("slot");
            if (Build.VERSION.SDK_INT >= 34) startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(notificationId, notification);
            state.put("pid", android.os.Process.myPid()).put("status", "running");
            DevWorkbench.write(new File(directory, "status.json"), state);
            long deadline = SystemClock.elapsedRealtime() + state.getLong("timeoutMs");
            watchdog.post(new Runnable() {
                public void run() {
                    if (finished) return;
                    if (new File(directory,"cancel").exists()) { finishJob(130,"cancelled"); return; }
                    if (SystemClock.elapsedRealtime() >= deadline) { finishJob(124, "timed_out"); return; }
                    if (new File(directory,"stdout.log").length() + new File(directory,"stderr.log").length() > 8 * 1024 * 1024) {
                        finishJob(125, "output_limit"); return;
                    }
                    watchdog.postDelayed(this, 500);
                }
            });
            new Thread(() -> {
                try {
                    int code = NodeRuntimeBridge.startNodeCaptured(state.getString("cwd"),
                            new String[]{"node", "--max-old-space-size=192", state.getString("runner"), new File(directory, "config.json").getAbsolutePath()},
                            new File(directory, "stdout.log").getAbsolutePath(), new File(directory, "stderr.log").getAbsolutePath());
                    finishJob(code, code == 0 ? "completed" : "failed");
                } catch (Throwable error) {
                    try { state.put("error", error.toString()); } catch (Exception ignored) { }
                    finishJob(126, "failed");
                }
            }, "pico-dev-job").start();
        } catch (Exception error) { finishJob(126, "failed"); }
        return START_NOT_STICKY;
    }

    private synchronized void finishJob(int code, String status) {
        if (finished) return;
        finished = true;
        try {
            if (directory != null && state != null) DevWorkbench.write(new File(directory, "status.json"),
                    state.put("status", status).put("exitCode", code).put("completedAt", System.currentTimeMillis()));
        } catch (Exception ignored) { }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
