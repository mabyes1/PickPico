package com.mcpocket.poc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioVolumePolicyTest {
    @Test
    public void boostsMutedMediaToAudibleTarget() {
        AudioVolumePolicy.Decision decision = AudioVolumePolicy.decide(0, 100, false);

        assertTrue(decision.shouldAdjust);
        assertEquals(35, decision.targetVolume);
        assertEquals("low_media_volume", decision.reason);
    }

    @Test
    public void boostsVeryLowMediaVolume() {
        AudioVolumePolicy.Decision decision = AudioVolumePolicy.decide(10, 100, false);

        assertTrue(decision.shouldAdjust);
        assertEquals(35, decision.targetVolume);
    }

    @Test
    public void leavesAlreadyAudibleVolumeAlone() {
        AudioVolumePolicy.Decision decision = AudioVolumePolicy.decide(20, 100, false);

        assertFalse(decision.shouldAdjust);
        assertEquals(20, decision.targetVolume);
        assertEquals("already_audible", decision.reason);
    }

    @Test
    public void respectsQuietModeEvenWhenMediaIsMuted() {
        AudioVolumePolicy.Decision decision = AudioVolumePolicy.decide(0, 100, true);

        assertFalse(decision.shouldAdjust);
        assertEquals(0, decision.targetVolume);
        assertEquals("quiet_mode_respected", decision.reason);
    }
}
