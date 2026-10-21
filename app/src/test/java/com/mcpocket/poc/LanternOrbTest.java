package com.mcpocket.poc;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class LanternOrbTest {
    @Test public void lanternIsARecognisedOrbStyle() {
        assertEquals(PickPicoTheme.ORB_LANTERN, PickPicoTheme.normalizeOrbStyle("lantern"));
        assertEquals(PickPicoTheme.ORB_FLAME, PickPicoTheme.normalizeOrbStyle("flame"));
        assertEquals(PickPicoTheme.ORB_ORIGINAL, PickPicoTheme.normalizeOrbStyle("crab"));
        assertEquals(PickPicoTheme.ORB_ORIGINAL, PickPicoTheme.normalizeOrbStyle(null));
    }

    @Test public void lanternChoiceSurvivesSaveAndLoad() {
        Application app = RuntimeEnvironment.getApplication();
        PickPicoTheme.State saved = PickPicoTheme.save(app, true, "#5e6d77", "#435059",
                PickPicoTheme.DEFAULT_GLASS_OPACITY, PickPicoTheme.DEFAULT_HIGHLIGHT,
                PickPicoTheme.DEFAULT_BACKGROUND_INTENSITY, PickPicoTheme.ORB_LANTERN);
        assertEquals(PickPicoTheme.ORB_LANTERN, saved.orbStyle);
        assertEquals(PickPicoTheme.ORB_LANTERN, PickPicoTheme.load(app).orbStyle);
    }

    @Test public void everyExpressionDrawsWithoutCrashing() {
        Application app = RuntimeEnvironment.getApplication();
        PickPicoTheme.State theme = new PickPicoTheme.State(true, 0xff5e6d77, 0xff435059,
                PickPicoTheme.DEFAULT_GLASS_OPACITY, PickPicoTheme.DEFAULT_HIGHLIGHT,
                PickPicoTheme.DEFAULT_BACKGROUND_INTENSITY, PickPicoTheme.ORB_LANTERN);
        PulseOrbView orb = new PulseOrbView(app, theme);
        orb.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        orb.layout(0, 0, 240, 240);
        Canvas canvas = new Canvas(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888));
        for (String mode : new String[]{PicoOrbState.READY, PicoOrbState.RUNNING, PicoOrbState.CONNECTING,
                PicoOrbState.HUMAN_HELP, PicoOrbState.BLOCKED, PicoOrbState.CONNECTION_ATTENTION,
                PicoOrbState.COMPLETED}) {
            orb.setMode(mode);
            orb.draw(canvas);
        }
    }
}
