package com.mcpocket.poc;

import android.app.KeyguardManager;
import android.content.Context;

/**
 * Tells apart "owner must unlock" from "lock screen is showing but the device is
 * already trusted" (Smart Lock / trust agents). In the second case Android keeps
 * {@code isKeyguardLocked()} true until something dismisses the keyguard, yet no
 * credential is needed: {@code isDeviceLocked()} is already false.
 */
final class KeyguardTrust {
    enum State {
        /** Keyguard is gone. */
        UNLOCKED,
        /** Keyguard is showing, device is trusted: a plain dismiss succeeds without owner input. */
        TRUSTED_DISMISSIBLE,
        /** Keyguard is showing and a credential or biometric is required. */
        OWNER_REQUIRED
    }

    private KeyguardTrust() {
    }

    static State classify(boolean keyguardLocked, boolean deviceLocked) {
        if (!keyguardLocked) return State.UNLOCKED;
        return deviceLocked ? State.OWNER_REQUIRED : State.TRUSTED_DISMISSIBLE;
    }

    static State current(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) return State.UNLOCKED;
        return classify(keyguard.isKeyguardLocked(), keyguard.isDeviceLocked());
    }
}
