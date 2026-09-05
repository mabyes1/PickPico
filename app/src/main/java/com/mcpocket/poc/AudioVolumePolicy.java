package com.mcpocket.poc;

/** Pure decision logic for making audible output useful without trampling quiet-mode intent. */
final class AudioVolumePolicy {
    static final double LOW_VOLUME_FRACTION = 0.20;
    static final double TARGET_VOLUME_FRACTION = 0.35;

    private AudioVolumePolicy() {}

    static Decision decide(int currentVolume, int maxVolume, boolean quietMode) {
        int safeMax = Math.max(0, maxVolume);
        int safeCurrent = Math.max(0, Math.min(currentVolume, safeMax));
        if (safeMax == 0) {
            return new Decision(safeCurrent, safeCurrent, false, "audio_unavailable");
        }

        int lowVolumeThreshold = Math.max(1, (int) Math.ceil(safeMax * LOW_VOLUME_FRACTION));
        int targetVolume = Math.max(1, (int) Math.ceil(safeMax * TARGET_VOLUME_FRACTION));
        if (quietMode) {
            return new Decision(safeCurrent, safeCurrent, false, "quiet_mode_respected");
        }
        if (safeCurrent >= lowVolumeThreshold) {
            return new Decision(safeCurrent, safeCurrent, false, "already_audible");
        }
        return new Decision(
                safeCurrent,
                Math.max(safeCurrent, Math.min(targetVolume, safeMax)),
                true,
                "low_media_volume");
    }

    static final class Decision {
        final int previousVolume;
        final int targetVolume;
        final boolean shouldAdjust;
        final String reason;

        Decision(int previousVolume, int targetVolume, boolean shouldAdjust, String reason) {
            this.previousVolume = previousVolume;
            this.targetVolume = targetVolume;
            this.shouldAdjust = shouldAdjust;
            this.reason = reason;
        }
    }
}
