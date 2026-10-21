package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class PickPicoThemeLegibilityTest {
    private static double contrast(int a, int b) {
        double la = Color.luminance(a);
        double lb = Color.luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test public void brightBackgroundIsDarkenedWithoutLosingItsHue() {
        int lilac = Color.rgb(150, 130, 170);
        int capped = PickPicoTheme.capLuminance(lilac, PickPicoTheme.MAX_BACKGROUND_LUMINANCE);
        assertTrue(Color.luminance(capped) <= PickPicoTheme.MAX_BACKGROUND_LUMINANCE + 0.002f);
        assertTrue("blue stays the strongest channel", Color.blue(capped) > Color.green(capped));
        assertTrue("red stays above green", Color.red(capped) > Color.green(capped));
    }

    @Test public void alreadyDarkBackgroundIsLeftAlone() {
        int slate = Color.rgb(24, 31, 38);
        assertEquals(slate, PickPicoTheme.capLuminance(slate, PickPicoTheme.MAX_BACKGROUND_LUMINANCE));
    }

    @Test public void secondaryTextStaysReadableOnTheBrightestAllowedBackground() {
        int worstBackground = PickPicoTheme.capLuminance(Color.WHITE, PickPicoTheme.MAX_BACKGROUND_LUMINANCE);
        assertTrue(contrast(PickPicoTheme.DIM, worstBackground) >= 4.5);
        assertTrue(contrast(PickPicoTheme.MUTED, worstBackground) >= 6.0);
    }
}
