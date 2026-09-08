package com.mcpocket.poc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PicoOrbStateTest {
    @Test public void priorityKeepsHumanHelpAboveEveryOtherState() {
        assertEquals(PicoOrbState.HUMAN_HELP, PicoOrbState.resolve(
                true, true, true, true, true, true, true));
    }

    @Test public void completionWinsOverConnectionAttention() {
        assertEquals(PicoOrbState.COMPLETED, PicoOrbState.resolve(
                true, false, false, false, false, true, true));
    }

    @Test public void runningWinsOverConnectingAndCompletion() {
        assertEquals(PicoOrbState.RUNNING, PicoOrbState.resolve(
                true, false, false, true, true, true, false));
    }

    @Test public void stoppedNodeAlwaysHidesTheOrb() {
        assertEquals(PicoOrbState.HIDDEN, PicoOrbState.resolve(
                false, true, true, true, true, true, true));
    }

    @Test public void semanticPaletteMatchesTheProductSpec() {
        assertEquals(0xff5fae9b, PicoOrbState.primary(PicoOrbState.HUMAN_HELP, 0));
        assertEquals(0xff6fa06d, PicoOrbState.primary(PicoOrbState.COMPLETED, 0));
        assertEquals(0xffc9a84c, PicoOrbState.primary(PicoOrbState.RUNNING, 0));
        assertEquals(0xffb6534f, PicoOrbState.primary(PicoOrbState.BLOCKED, 0));
        assertEquals(0xffc9784a, PicoOrbState.primary(PicoOrbState.CONNECTING, 0));
        assertEquals(0xffc9784a, PicoOrbState.primary(PicoOrbState.CONNECTION_ATTENTION, 0));
    }
}
