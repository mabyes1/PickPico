package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class DashboardCalmStatusTest {
    @Test public void healthyAndNeutralStatesStayQuiet() {
        assertEquals(PickPicoTheme.MUTED, DashboardActivity.calmStateColor(PickPicoTheme.GREEN));
        assertEquals(PickPicoTheme.MUTED, DashboardActivity.calmStateColor(PickPicoTheme.BLUE));
    }

    @Test public void attentionStatesKeepTheirColour() {
        assertEquals(PickPicoTheme.AMBER, DashboardActivity.calmStateColor(PickPicoTheme.AMBER));
        assertEquals(PickPicoTheme.RED, DashboardActivity.calmStateColor(PickPicoTheme.RED));
    }

    @Test public void otherColoursPassThrough() {
        assertEquals(PickPicoTheme.DIM, DashboardActivity.calmStateColor(PickPicoTheme.DIM));
        assertEquals(PickPicoTheme.TEXT, DashboardActivity.calmStateColor(PickPicoTheme.TEXT));
    }
}
