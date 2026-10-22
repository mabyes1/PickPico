package com.mcpocket.poc;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Renders every lantern expression with real pixels; the sheet lands in build/lantern-preview. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LanternOrbRenderTest {
    private static final String[] MODES = {PicoOrbState.READY, PicoOrbState.RUNNING, PicoOrbState.CONNECTING,
            PicoOrbState.HUMAN_HELP, PicoOrbState.BLOCKED, PicoOrbState.CONNECTION_ATTENTION, PicoOrbState.COMPLETED};

    @Test public void lanternRendersVisiblePixelsInEveryMode() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        PickPicoTheme.State theme = new PickPicoTheme.State(true, 0xff5e6d77, 0xff435059,
                PickPicoTheme.DEFAULT_GLASS_OPACITY, PickPicoTheme.DEFAULT_HIGHLIGHT,
                PickPicoTheme.DEFAULT_BACKGROUND_INTENSITY, PickPicoTheme.ORB_LANTERN);
        int cell = 360;
        Bitmap sheet = Bitmap.createBitmap(cell * 4, cell * 2, Bitmap.Config.ARGB_8888);
        Canvas sheetCanvas = new Canvas(sheet);
        sheetCanvas.drawColor(Color.rgb(44, 40, 48));
        for (int i = 0; i < MODES.length; i++) {
            Bitmap frame = render(app, theme, MODES[i], cell);
            int center = frame.getPixel(cell / 2, cell / 2);
            assertTrue(MODES[i] + " should paint the lantern body", Color.alpha(center) > 200);
            sheetCanvas.drawBitmap(frame, (i % 4) * cell, (i / 4) * cell, null);
        }
        // Small sizes as used by the top-bar logo and the floating ball.
        sheetCanvas.drawBitmap(render(app, theme, PicoOrbState.READY, 76), 3 * cell + 40, cell + 60, null);
        sheetCanvas.drawBitmap(render(app, theme, PicoOrbState.READY, 168), 3 * cell + 150, cell + 150, null);

        File out = new File("build/lantern-preview/sheet.png");
        out.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }

    private static Bitmap render(Application app, PickPicoTheme.State theme, String mode, int size) {
        PulseOrbView orb = new PulseOrbView(app, theme);
        orb.setMode(mode);
        orb.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        orb.layout(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(44, 40, 48));
        orb.draw(canvas);
        return bitmap;
    }
}
