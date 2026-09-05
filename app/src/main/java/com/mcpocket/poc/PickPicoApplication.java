package com.mcpocket.poc;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicInteger;

/** Process-wide visibility tracker for every PickPico activity, not just the legacy MainActivity. */
public final class PickPicoApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final AtomicInteger STARTED_ACTIVITIES = new AtomicInteger();

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    static boolean isAppInForeground() {
        return STARTED_ACTIVITIES.get() > 0;
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
