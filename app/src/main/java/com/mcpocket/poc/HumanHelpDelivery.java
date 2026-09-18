package com.mcpocket.poc;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Delivers pending help once. A successful startActivity is NOT a display receipt. */
final class HumanHelpDelivery {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean stopped;
    private boolean lastAllowed;
    private boolean lastLocked;
    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { refresh(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            deliver();
            handler.postDelayed(this, 1000L);
        }
    };

    HumanHelpDelivery(Context context) { this.context = context; }
    void start() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(unlock, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(unlock, filter);
        refresh();
    }
    void stop() {
        stopped = true;
        handler.removeCallbacks(tick);
        context.unregisterReceiver(unlock);
    }
    private void refresh() {
        handler.removeCallbacks(tick);
        if (!stopped) handler.post(tick);
    }
    static boolean isLocked(Context context) {
        KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isKeyguardLocked();
    }
    private void deliver() {
        boolean locked = isLocked(context);
        boolean allowed = AgentAttention.canStartActivityNow(context);
        boolean retry = (lastLocked && !locked) || (!lastAllowed && allowed);
        lastLocked = locked;
        lastAllowed = allowed;
        try {
            for (JSONObject request : HumanHelpStore.pendingDelivery(context)) {
                String id = request.getString("requestId");
                String state = request.optString("deliveryStatus");
                if (locked) {
                    if (!state.equals("waiting_unlock")) HumanHelpStore.deliveryState(context, id, "waiting_unlock", "device_locked", false);
                    continue;
                }
                if (!allowed) {
                    if (!state.equals("blocked")) HumanHelpStore.deliveryState(context, id, "blocked", "foreground_launch_access_required", false);
                    continue;
                }
                long elapsed = android.os.SystemClock.elapsedRealtime() - request.optLong("lastLaunchElapsedMs");
                if (state.equals("launching") && elapsed >= 0 && elapsed < 2500L) return;
                if (HumanHelpActivity.hasVisibleRequest()) {
                    if (state.equals("launching")) HumanHelpStore.deliveryState(context, id,
                            "blocked", "foreground_not_confirmed", false);
                    return;
                }
                if (state.equals("blocked") && !retry) continue;
                if (request.optInt("deliveryAttempts") >= 3 && !retry && !state.equals("waiting_unlock")) {
                    HumanHelpStore.deliveryState(context, id, "blocked", "foreground_not_confirmed", false);
                    continue;
                }
                HumanHelpStore.deliveryState(context, id, "launching", "awaiting_window_focus", true);
                try {
                    context.startActivity(new Intent(context, HumanHelpActivity.class)
                            .putExtra(HumanHelpStore.EXTRA_REQUEST_ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (RuntimeException error) {
                    HumanHelpStore.deliveryState(context, id, "blocked", "launch_rejected", false);
                }
                return;
            }
        } catch (Exception error) {
            android.util.Log.w("PickPico", "Human Help delivery check failed", error);
        }
    }
}
