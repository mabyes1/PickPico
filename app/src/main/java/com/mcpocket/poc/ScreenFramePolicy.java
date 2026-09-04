package com.mcpocket.poc;

/** Bounds reuse of a projection frame and describes it to content-only clients. */
final class ScreenFramePolicy {
    static final long MAX_CACHED_FRAME_AGE_MS = 5_000L;

    private ScreenFramePolicy() { }

    static boolean isExpired(boolean freshFrame, long ageMs) {
        return !freshFrame && ageMs > MAX_CACHED_FRAME_AGE_MS;
    }

    static String describe(boolean freshFrame, long ageMs, String path) {
        return freshFrame
                ? "Captured a new Android screen frame to " + path
                : "Cached Android screen frame (" + ageMs
                        + " ms since last capture; current screen is not confirmed) saved to " + path;
    }
}
