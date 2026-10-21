package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyguardTrustTest {
    @Test
    public void noKeyguardMeansUnlocked() {
        assertEquals(KeyguardTrust.State.UNLOCKED, KeyguardTrust.classify(false, false));
    }

    @Test
    public void keyguardShowingOnTrustedDeviceCanBeDismissedWithoutOwner() {
        assertEquals(KeyguardTrust.State.TRUSTED_DISMISSIBLE, KeyguardTrust.classify(true, false));
    }

    @Test
    public void keyguardShowingOnLockedDeviceNeedsOwner() {
        assertEquals(KeyguardTrust.State.OWNER_REQUIRED, KeyguardTrust.classify(true, true));
    }
}
