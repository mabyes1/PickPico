package com.mcpocket.poc;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/** Hyper Mode bridge for Android-owned keyguard dismissal. */
public final class HyperUnlockActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!McpocketPolicySettings.isHyperModeEnabled(this)) {
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.screenBrightness = 1.0f;
        getWindow().setAttributes(window);

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            finish();
            return;
        }

        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() {
                new Handler(Looper.getMainLooper()).postDelayed(HyperUnlockActivity.this::finish, 1800L);
            }
            @Override public void onDismissCancelled() { finish(); }
            @Override public void onDismissError() { finish(); }
        });
    }
}
