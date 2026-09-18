package com.mcpocket.poc;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class ThemeColorPickerTest {
    @Test public void enteringColorUpdatesAllComponentsWithoutOverwritingTheSelection() {
        ThemeColorPicker picker = new ThemeColorPicker(RuntimeEnvironment.getApplication(), Color.BLACK);
        EditText hex = (EditText) ((LinearLayout) picker.getChildAt(3)).getChildAt(1);
        hex.setText("29c7b4");
        assertTrue(picker.validateColor());
        assertEquals(Color.rgb(41, 199, 180), picker.getColor());
        SeekBar hue = (SeekBar) picker.getChildAt(2);
        assertTrue(hue.getProgress() > 160 && hue.getProgress() < 180);
        hex.setText("#FFFFFF");
        assertEquals(Color.WHITE, picker.getColor());
        hex.setText("#000000");
        assertEquals(Color.BLACK, picker.getColor());
    }

    @Test public void invalidInputCannotSilentlyApplyThePreviousColor() {
        ThemeColorPicker picker = new ThemeColorPicker(RuntimeEnvironment.getApplication(), Color.BLUE);
        EditText hex = (EditText) ((LinearLayout) picker.getChildAt(3)).getChildAt(1);
        hex.setText("#123");
        assertFalse(picker.validateColor());
        assertNotNull(hex.getError());
        assertEquals(Color.BLUE, picker.getColor());
    }

    @Test public void paletteTouchSelectsAnyShadeAndSynchronizesHex() {
        ThemeColorPicker picker = new ThemeColorPicker(RuntimeEnvironment.getApplication(), Color.RED);
        picker.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
        picker.layout(0, 0, picker.getMeasuredWidth(), picker.getMeasuredHeight());
        View palette = picker.getChildAt(1);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                palette.getWidth() / 2f, palette.getHeight() / 2f, 0);
        MotionEvent up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP,
                palette.getWidth() / 2f, palette.getHeight() / 2f, 0);
        palette.dispatchTouchEvent(down);
        palette.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
        assertEquals(128, Color.red(picker.getColor()), 1);
        assertEquals(64, Color.green(picker.getColor()), 1);
        EditText hex = (EditText) ((LinearLayout) picker.getChildAt(3)).getChildAt(1);
        assertEquals(picker.getColor(), Color.parseColor(hex.getText().toString()));
    }
}
