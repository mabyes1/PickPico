package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** Continuous HSV palette. Draft edits stay local until the dialog is applied. */
final class ThemeColorPicker extends LinearLayout {
    private final float[] hsv = new float[3];
    private final Palette palette;
    private final SeekBar hue;
    private final EditText hex;
    private final View swatch;
    private boolean synchronizing;
    private int selected;

    ThemeColorPicker(Context context, int initial) {
        super(context);
        setOrientation(VERTICAL);
        selected = initial;
        Color.colorToHSV(initial, hsv);
        TextView hint = new TextView(context);
        hint.setText("Drag on the palette to choose a color. Use the rainbow bar to change hue.");
        addView(hint);
        palette = new Palette(context);
        LayoutParams paletteParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(200));
        paletteParams.topMargin = dp(12);
        addView(palette, paletteParams);
        hue = new SeekBar(context);
        hue.setMax(359);
        hue.setContentDescription("Hue");
        GradientDrawable rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED});
        rainbow.setCornerRadius(dp(8));
        hue.setProgressDrawable(rainbow);
        addView(hue, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout row = new LinearLayout(context);
        swatch = new View(context);
        row.addView(swatch, new LayoutParams(dp(48), dp(48)));
        hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setContentDescription("Color hex code");
        hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(hex, new LayoutParams(0, dp(48), 1f));
        addView(row);
        hue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (synchronizing || !fromUser) return;
                hsv[0] = progress;
                updateFromPalette();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (synchronizing || !validHex(s.toString())) return;
                selected = Color.parseColor(normalize(s.toString()));
                Color.colorToHSV(selected, hsv);
                synchronize(false);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        synchronize(true);
    }

    static boolean validHex(String value) { return value.trim().matches("#?[0-9a-fA-F]{6}"); }
    private static String normalize(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("#") ? trimmed : "#" + trimmed;
    }
    int getColor() { return selected; }
    boolean validateColor() {
        if (validHex(hex.getText().toString())) return true;
        hex.setError("Enter six hex digits, for example #5E6D77");
        hex.requestFocus();
        return false;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void updateFromPalette() {
        selected = Color.HSVToColor(hsv);
        synchronize(true);
    }
    private void synchronize(boolean writeHex) {
        synchronizing = true;
        hue.setProgress(Math.round(hsv[0]));
        if (writeHex) hex.setText(String.format(Locale.ROOT, "#%06X", selected & 0xffffff));
        hex.setError(null);
        swatch.setBackgroundColor(selected);
        palette.setContentDescription("Color palette, saturation " + Math.round(hsv[1] * 100)
                + " percent, brightness " + Math.round(hsv[2] * 100) + " percent. Color can also be entered below.");
        palette.invalidate();
        synchronizing = false;
    }

    private final class Palette extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Palette(Context context) { super(context); setClickable(true); }
        private float inset() { return dp(9); }
        @Override protected void onDraw(Canvas canvas) {
            float left = inset(), top = inset();
            float right = getWidth() - inset(), bottom = getHeight() - inset();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(left, top, right, top, Color.WHITE,
                    Color.HSVToColor(new float[]{hsv[0], 1f, 1f}), Shader.TileMode.CLAMP));
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setShader(new LinearGradient(left, top, left, bottom, Color.TRANSPARENT,
                    Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setShader(null);
            float x = left + hsv[1] * (right - left), y = top + (1f - hsv[2]) * (bottom - top);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4)); paint.setColor(Color.BLACK);
            canvas.drawCircle(x, y, dp(6), paint);
            paint.setStrokeWidth(dp(2)); paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(6), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_UP) return super.onTouchEvent(event);
            getParent().requestDisallowInterceptTouchEvent(true);
            hsv[1] = Math.max(0f, Math.min(1f, (event.getX() - inset()) / Math.max(1f, getWidth() - 2 * inset())));
            hsv[2] = 1f - Math.max(0f, Math.min(1f, (event.getY() - inset()) / Math.max(1f, getHeight() - 2 * inset())));
            updateFromPalette();
            if (action == MotionEvent.ACTION_UP) {
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
