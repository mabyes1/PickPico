package com.mcpocket.poc;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-wide visibility tracker for every PickPico activity, not just the legacy MainActivity. */
public final class PickPicoApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final AtomicInteger STARTED_ACTIVITIES = new AtomicInteger();
    private static volatile long processStartedElapsed;
    private static volatile String processStartedAt = "";

    @Override
    public void onCreate() {
        super.onCreate();
        processStartedElapsed = SystemClock.elapsedRealtime();
        processStartedAt = Instant.now().toString();
        registerActivityLifecycleCallbacks(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || getPackageName().equals(Application.getProcessName())) {
            LauncherShortcuts.publish(this);
        }
    }

    static boolean isAppInForeground() {
        return STARTED_ACTIVITIES.get() > 0;
    }

    static String processStartedAt() {
        return processStartedAt;
    }

    static long processUptimeSeconds() {
        long started = processStartedElapsed;
        return started <= 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - started) / 1000L;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }

    @Override
    public void onActivityStarted(Activity activity) {
        STARTED_ACTIVITIES.incrementAndGet();
    }

    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }

    @Override
    public void onActivityStopped(Activity activity) {
        STARTED_ACTIVITIES.updateAndGet(value -> Math.max(0, value - 1));
    }

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
