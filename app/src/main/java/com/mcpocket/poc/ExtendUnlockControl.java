package com.mcpocket.poc;

import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Temporarily extends Android's lock-after-screen-off timeout and disables
 * instant side-key lock while an Agent task or explicit extend-unlock lease is
 * active, restoring the owner's exact settings when the lease ends or on
 * service recovery.
 */
final class ExtendUnlockControl {
    static final String PERMISSION = DeveloperOptionsControl.PERMISSION;
    static final String KEY_LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout";
    static final String KEY_POWER_BUTTON_INSTANTLY_LOCKS = "power_button_instantly_locks";
    static final long DEFAULT_EXTEND_MS = 180_000L;
    static final long MIN_EXTEND_MS = 30_000L;
    static final long MAX_EXTEND_MS = 1_800_000L;
    private static final long DEFAULT_SYSTEM_LOCK_TIMEOUT_MS = 5_000L;
    private static final int DEFAULT_POWER_BUTTON_LOCKS = 1;

    private static final String PREFS = "pickpico_extend_unlock";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_ORIG_TIMEOUT_MS = "orig_lock_after_timeout_ms";
    private static final String PREF_ORIG_POWER_LOCKS = "orig_power_button_instantly_locks";
    private static final String PREF_APPLIED_TIMEOUT_MS = "applied_timeout_ms";

    private ExtendUnlockControl() {
    }

    static boolean hasPermission(Context context) {
        return context != null
                && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static synchronized boolean apply(Context context, long durationMs) {
        if (context == null) return false;
        long targetMs = Math.max(MIN_EXTEND_MS, Math.min(MAX_EXTEND_MS, durationMs));
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(PREF_ACTIVE, false)) {
            long origTimeout = readLong(resolver, KEY_LOCK_AFTER_TIMEOUT, DEFAULT_SYSTEM_LOCK_TIMEOUT_MS);
            int origPowerLocks = readInt(resolver, KEY_POWER_BUTTON_INSTANTLY_LOCKS, DEFAULT_POWER_BUTTON_LOCKS);
            prefs.edit()
                    .putBoolean(PREF_ACTIVE, true)
                    .putLong(PREF_ORIG_TIMEOUT_MS, origTimeout)
                    .putInt(PREF_ORIG_POWER_LOCKS, origPowerLocks)
                    .putLong(PREF_APPLIED_TIMEOUT_MS, targetMs)
                    .commit();
        } else {
            prefs.edit().putLong(PREF_APPLIED_TIMEOUT_MS, targetMs).apply();
        }

        try {
            boolean okTimeout = Settings.Secure.putLong(resolver, KEY_LOCK_AFTER_TIMEOUT, targetMs);
            boolean okPower = Settings.Secure.putInt(resolver, KEY_POWER_BUTTON_INSTANTLY_LOCKS, 0);
            return okTimeout && okPower;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    static synchronized boolean release(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ACTIVE, false)) {
            return false;
        }
        long origTimeout = prefs.getLong(PREF_ORIG_TIMEOUT_MS, DEFAULT_SYSTEM_LOCK_TIMEOUT_MS);
        int origPowerLocks = prefs.getInt(PREF_ORIG_POWER_LOCKS, DEFAULT_POWER_BUTTON_LOCKS);
        ContentResolver resolver = context.getContentResolver();
        boolean restored = false;
        try {
            boolean okTimeout = Settings.Secure.putLong(resolver, KEY_LOCK_AFTER_TIMEOUT, origTimeout);
            boolean okPower = Settings.Secure.putInt(resolver, KEY_POWER_BUTTON_INSTANTLY_LOCKS, origPowerLocks);
            restored = okTimeout && okPower;
        } catch (SecurityException ignored) {
        }
        prefs.edit()
                .putBoolean(PREF_ACTIVE, false)
                .remove(PREF_APPLIED_TIMEOUT_MS)
                .commit();
        return restored;
    }

    static synchronized void recoverIfNeeded(Context context) {
        release(context);
    }

    static synchronized JSONObject status(
            Context context,
            boolean leaseActive,
            long remainingMs,
            boolean taskLeaseActive,
            boolean manualLeaseActive) throws JSONException {
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean activeInPrefs = prefs.getBoolean(PREF_ACTIVE, false);
        long currentTimeout = readLong(resolver, KEY_LOCK_AFTER_TIMEOUT, DEFAULT_SYSTEM_LOCK_TIMEOUT_MS);
        int currentPowerLocks = readInt(resolver, KEY_POWER_BUTTON_INSTANTLY_LOCKS, DEFAULT_POWER_BUTTON_LOCKS);
        long origTimeout = activeInPrefs
                ? prefs.getLong(PREF_ORIG_TIMEOUT_MS, DEFAULT_SYSTEM_LOCK_TIMEOUT_MS)
                : currentTimeout;
        int origPowerLocks = activeInPrefs
                ? prefs.getInt(PREF_ORIG_POWER_LOCKS, DEFAULT_POWER_BUTTON_LOCKS)
                : currentPowerLocks;

        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean keyguardLocked = keyguard != null && keyguard.isKeyguardLocked();
        boolean interactive = power != null && power.isInteractive();

        return new JSONObject()
                .put("active", leaseActive && activeInPrefs)
                .put("permissionGranted", hasPermission(context))
                .put("remainingMs", Math.max(0L, remainingMs))
                .put("remainingSeconds", Math.max(0L, (remainingMs + 999L) / 1000L))
                .put("taskLeaseActive", taskLeaseActive)
                .put("manualLeaseActive", manualLeaseActive)
                .put("lockAfterTimeoutMs", currentTimeout)
                .put("powerButtonInstantlyLocks", currentPowerLocks != 0)
                .put("originalLockAfterTimeoutMs", origTimeout)
                .put("originalPowerButtonInstantlyLocks", origPowerLocks != 0)
                .put("keyguardLocked", keyguardLocked)
                .put("interactive", interactive);
    }

    private static long readLong(ContentResolver resolver, String key, long fallback) {
        try {
            return Settings.Secure.getLong(resolver, key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int readInt(ContentResolver resolver, String key, int fallback) {
        try {
            return Settings.Secure.getInt(resolver, key, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
