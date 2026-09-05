package com.mcpocket.poc;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ScreenFramePolicyTest {
    @Test public void rejectsExpiredCacheButAllowsNewFramesAndRecentCache() {
        assertFalse(ScreenFramePolicy.isExpired(false, 5_000L));
        assertTrue(ScreenFramePolicy.isExpired(false, 5_001L));
        assertFalse(ScreenFramePolicy.isExpired(true, 5_001L));
    }

    @Test public void contentOnlyClientsCanDistinguishCachedFrames() {
        String text = ScreenFramePolicy.describe(false, 2_345L, "captures/test.jpg");
        assertTrue(text.contains("Cached"));
        assertTrue(text.contains("2345 ms"));
        assertTrue(text.contains("current screen is not confirmed"));
        assertTrue(text.contains("captures/test.jpg"));
        assertFalse(text.contains("Captured a new"));
    }

    @Test public void newFrameIsNotLabelledAsCache() {
        String text = ScreenFramePolicy.describe(true, 0L, "captures/new.jpg");
        assertTrue(text.contains("Captured a new"));
        assertFalse(text.contains("Cached"));
    }
}
