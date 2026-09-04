package com.mcpocket.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Locale;

/** Shared PickPico appearance model for background and glass controls. */
final class PickPicoTheme {
    static final String PREFS = "pickpico_appearance";

    static final String DEFAULT_A = "#4b1f66";
    static final String DEFAULT_B = "#17344d";
    static final int DEFAULT_GLASS_OPACITY = 4;
    static final int DEFAULT_HIGHLIGHT = 28;
    static final int DEFAULT_BACKGROUND_INTENSITY = 88;

    static final class State {
        final boolean gradient;
        final int colorA;
        final int colorB;
        final int glassOpacity;
        final int highlight;
        final int backgroundIntensity;

        State(boolean gradient, int colorA, int colorB, int glassOpacity) {
            this(gradient, colorA, colorB, glassOpacity, DEFAULT_HIGHLIGHT, DEFAULT_BACKGROUND_INTENSITY);
        }

        State(boolean gradient, int colorA, int colorB, int glassOpacity, int highlight, int backgroundIntensity) {
            this.gradient = gradient;
            this.colorA = colorA;
            this.colorB = colorB;
            this.glassOpacity = clamp(glassOpacity, 1, 30);
            this.highlight = clamp(highlight, 4, 48);
            this.backgroundIntensity = clamp(backgroundIntensity, 30, 100);
        }
    }

    private PickPicoTheme() {
    }

    static State load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new State(
                prefs.getBoolean("gradient", true),
                parseHex(prefs.getString("color_a", DEFAULT_A), Color.rgb(75, 31, 102)),
                parseHex(prefs.getString("color_b", DEFAULT_B), Color.rgb(23, 52, 77)),
                prefs.getInt("glass_opacity", DEFAULT_GLASS_OPACITY),
                prefs.getInt("highlight", DEFAULT_HIGHLIGHT),
                prefs.getInt("background_intensity", DEFAULT_BACKGROUND_INTENSITY));
    }

    static State save(Context context, boolean gradient, String colorA, String colorB, int glassOpacity) {
        State current = load(context);
        return save(context, gradient, colorA, colorB, glassOpacity, current.highlight, current.backgroundIntensity);
    }

    static State save(
            Context context,
            boolean gradient,
            String colorA,
            String colorB,
            int glassOpacity,
            int highlight,
            int backgroundIntensity) {
        int a = parseHex(colorA, Color.rgb(75, 31, 102));
        int b = parseHex(colorB, Color.rgb(23, 52, 77));
        State state = new State(gradient, a, b, glassOpacity, highlight, backgroundIntensity);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gradient", state.gradient)
                .putString("color_a", toHex(state.colorA))
                .putString("color_b", toHex(state.colorB))
                .putInt("glass_opacity", state.glassOpacity)
                .putInt("highlight", state.highlight)
                .putInt("background_intensity", state.backgroundIntensity)
                .apply();
        return state;
    }

    static String toHex(int color) {
        return String.format(Locale.US, "#%02x%02x%02x", Color.red(color), Color.green(color), Color.blue(color));
    }

    static int parseHex(String value, int fallback) {
        if (value == null) return fallback;
        String candidate = value.trim();
        if (!candidate.startsWith("#")) candidate = "#" + candidate;
        try {
            if (candidate.length() == 4) {
                char r = candidate.charAt(1);
                char g = candidate.charAt(2);
                char b = candidate.charAt(3);
                candidate = "#" + r + r + g + g + b + b;
            }
            if (candidate.length() != 7) return fallback;
            return Color.parseColor(candidate);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    static int accentA(State state) {
        return mix(state.colorA, isLightBackground(state) ? Color.BLACK : Color.WHITE, 0.36f);
    }

    static int accentB(State state) {
        return mix(state.colorB, isLightBackground(state) ? Color.BLACK : Color.WHITE, 0.44f);
    }

    static boolean isLightBackground(State state) {
        return state != null && !state.gradient && Color.luminance(state.colorA) >= 0.56f;
    }

    static Drawable card(State state, float radius, boolean accented) {
        return new GlassDrawable(state, radius, false, accented);
    }

    static Drawable strongGlass(State state, float radius) {
        return new GlassDrawable(state, radius, true, false);
    }

    static Drawable control(State state, float radius, int accent, boolean filled) {
        return new ControlDrawable(state, radius, accent, filled);
    }

    static Drawable preview(State state, float radius) {
        return new PreviewDrawable(state, radius);
    }

    static final class BackgroundView extends View {
        private State state;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackgroundView(Context context, State state) {
            super(context);
            this.state = state;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setState(State state) {
            this.state = state;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            if (!state.gradient) {
                canvas.drawColor(state.colorA);
                return;
            }

            float intensity = state.backgroundIntensity / 100f;

            paint.setShader(new LinearGradient(
                    0f, 0f, w, h,
                    new int[]{Color.rgb(8, 9, 13), Color.rgb(8, 12, 18), Color.rgb(7, 13, 20)},
                    new float[]{0f, .54f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);

            paint.setShader(new RadialGradient(
                    -w * .08f,
                    -h * .10f,
                    Math.max(w, h) * .68f,
                    new int[]{withAlpha(state.colorA, .30f + .48f * intensity), withAlpha(state.colorA, .10f + .24f * intensity), Color.TRANSPARENT},
                    new float[]{0f, .44f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);

            paint.setShader(new RadialGradient(
                    w * 1.08f,
                    h * 1.10f,
                    Math.max(w, h) * .72f,
                    new int[]{withAlpha(state.colorB, .28f + .46f * intensity), withAlpha(state.colorB, .09f + .22f * intensity), Color.TRANSPARENT},
                    new float[]{0f, .46f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);

            paint.setShader(new LinearGradient(
                    0f, h,
                    w, 0f,
                    new int[]{withAlpha(state.colorA, .08f + .18f * intensity), Color.TRANSPARENT, withAlpha(state.colorB, .07f + .17f * intensity)},
                    new float[]{0f, .46f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, w, h, paint);
            paint.setShader(null);
        }
    }

    /** Self-lit glass surface: transmitted scene plus independent highlight and shadow. */
    private static final class GlassDrawable extends Drawable {
        private final State state;
        private final float radius;
        private final boolean strong;
        private final boolean accented;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassDrawable(State state, float radius, boolean strong, boolean accented) {
            this.state = state;
            this.radius = radius;
            this.strong = strong;
            this.accented = accented;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF rect = new RectF(getBounds());
            boolean lightBackground = isLightBackground(state);
            float opacity = state.glassOpacity / 100f;
            // DEFAULT_HIGHLIGHT is the Tunnel-Coding reference recipe at 1.0x.
            // The previous implementation divided this value by 100, which made
            // the optical highlight far too weak to read as glass.
            float shine = clamp(state.highlight / (float) DEFAULT_HIGHLIGHT, .25f, 1.70f);

            float glassHi = (lightBackground ? .52f : .34f) * shine;
            float glassMid = (lightBackground ? .22f : .13f) * shine;
            float glassLow = (lightBackground ? .05f : .025f) * shine;
            float baseAlpha = lightBackground
                    ? .06f + opacity * .65f
                    : .025f + opacity * .95f;

            paint.setShader(null);
            if (strong) {
                paint.setColor(lightBackground
                        ? withAlpha(Color.WHITE, clamp(.11f + opacity * .35f, 0f, .28f))
                        : withAlpha(Color.rgb(10, 14, 18), clamp(.20f + opacity * .70f, 0f, .42f)));
            } else {
                paint.setColor(withAlpha(Color.WHITE, clamp(baseAlpha, 0f, .30f)));
            }
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Tunnel-Coding's 145deg surface gradient.
            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{
                            withAlpha(Color.WHITE, clamp(glassMid, 0f, .65f)),
                            withAlpha(Color.WHITE, clamp(glassLow, 0f, .28f)),
                            Color.TRANSPARENT},
                    new float[]{0f, .34f, .70f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Opposing low light, matching the shell's secondary 325deg layer.
            paint.setShader(new LinearGradient(
                    rect.right, rect.bottom, rect.left, rect.top,
                    new int[]{withAlpha(Color.WHITE, clamp(glassLow, 0f, .22f)), Color.TRANSPARENT},
                    new float[]{0f, .34f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Large top-left incident light. This is the layer that makes the
            // material look illuminated rather than merely transparent.
            paint.setShader(new RadialGradient(
                    rect.left + rect.width() * .06f,
                    rect.top - rect.height() * .04f,
                    Math.max(rect.width(), rect.height()) * 1.18f,
                    new int[]{
                            withAlpha(Color.WHITE, clamp(glassHi * .78f, 0f, .72f)),
                            withAlpha(Color.WHITE, clamp(glassMid * .78f, 0f, .42f)),
                            Color.TRANSPARENT},
                    new float[]{0f, .18f, .48f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Smaller reflected light from the opposite corner.
            paint.setShader(new RadialGradient(
                    rect.right + rect.width() * .04f,
                    rect.bottom + rect.height() * .04f,
                    Math.max(rect.width(), rect.height()) * .72f,
                    new int[]{withAlpha(Color.WHITE, clamp(glassMid * .78f, 0f, .38f)), Color.TRANSPARENT},
                    new float[]{0f, .58f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);

            // Narrow diagonal light streak, equivalent to Tunnel-Coding's
            // ::before linear-gradient(112deg, ...).
            paint.setShader(new LinearGradient(
                    rect.left, rect.bottom,
                    rect.right, rect.top,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            withAlpha(Color.WHITE, clamp(.10f * .78f * shine, 0f, .18f)),
                            withAlpha(Color.WHITE, clamp(.025f * .78f * shine, 0f, .08f)),
                            Color.TRANSPARENT,
                            Color.TRANSPARENT},
                    new float[]{0f, .34f, .44f, .54f, .64f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);

            if (accented) {
                paint.setShader(new LinearGradient(
                        rect.left, rect.top, rect.right, rect.bottom,
                        new int[]{withAlpha(state.colorA, .15f), Color.TRANSPARENT, withAlpha(state.colorB, .10f)},
                        new float[]{0f, .55f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, paint);
            }

            // On bright scenes a dark hairline gives the white optical border
            // somewhere to land, the same job done by Tunnel's shadow/line pair.
            RectF edge = new RectF(rect.left + .5f, rect.top + .5f, rect.right - .5f, rect.bottom - .5f);
            if (lightBackground) {
                stroke.setColor(withAlpha(Color.rgb(17, 24, 39), .13f));
                canvas.drawRoundRect(edge, radius, radius, stroke);
            }

            stroke.setColor(withAlpha(Color.WHITE, lightBackground ? .56f : .42f));
            canvas.drawRoundRect(edge, radius, radius, stroke);

            RectF inner = new RectF(rect.left + 1.5f, rect.top + 1.5f, rect.right - 1.5f, rect.bottom - 1.5f);
            stroke.setColor(withAlpha(Color.WHITE, clamp(.07f * shine, 0f, .16f)));
            canvas.drawRoundRect(inner, radius, radius, stroke);

            // Soft top inset light and bottom inset shade, corresponding to the
            // CSS ::after inset 0 18px 34px / inset 0 -20px 30px pair.
            paint.setShader(new LinearGradient(
                    rect.left, rect.top,
                    rect.left, rect.bottom,
                    new int[]{
                            withAlpha(Color.WHITE, clamp(.055f * shine, 0f, .12f)),
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            withAlpha(Color.BLACK, lightBackground ? .065f : .035f)},
                    new float[]{0f, .34f, .68f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(inner, radius, radius, paint);
            paint.setShader(null);
        }

        @Override
        public void getOutline(Outline outline) {
            Rect bounds = getBounds();
            outline.setRoundRect(bounds, radius);
            outline.setAlpha(strong ? .50f : .18f);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class ControlDrawable extends Drawable {
        private final State state;
        private final float radius;
        private final int accent;
        private final boolean filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        ControlDrawable(State state, float radius, int accent, boolean filled) {
            this.state = state;
            this.radius = radius;
            this.accent = accent;
            this.filled = filled;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF rect = new RectF(getBounds());
            boolean lightBackground = isLightBackground(state);
            paint.setColor(filled
                    ? withAlpha(accent, .16f)
                    : (lightBackground ? Color.argb(10, 0, 0, 0) : Color.argb(12, 255, 255, 255)));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{Color.argb(24, 255, 255, 255), Color.TRANSPARENT, Color.argb(18, 0, 0, 0)},
                    new float[]{0f, .58f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            stroke.setColor(filled
                    ? withAlpha(accent, .38f)
                    : (lightBackground ? Color.argb(45, 0, 0, 0) : Color.argb(31, 255, 255, 255)));
            canvas.drawRoundRect(new RectF(rect.left + .5f, rect.top + .5f, rect.right - .5f, rect.bottom - .5f), radius, radius, stroke);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class PreviewDrawable extends Drawable {
        private final State state;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewDrawable(State state, float radius) {
            this.state = state;
            this.radius = radius;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
            stroke.setColor(Color.argb(52, 255, 255, 255));
        }

        @Override
        public void draw(Canvas canvas) {
            RectF rect = new RectF(getBounds());
            boolean lightBackground = isLightBackground(state);
            if (state.gradient) {
                paint.setShader(new LinearGradient(rect.left, rect.bottom, rect.right, rect.top,
                        state.colorA, state.colorB, Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(state.colorA);
            }
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            RectF glass = new RectF(
                    rect.left + rect.width() * .13f,
                    rect.top + rect.height() * .16f,
                    rect.right - rect.width() * .13f,
                    rect.bottom - rect.height() * .16f);
            paint.setColor(lightBackground
                    ? Color.argb(Math.max(5, Math.round(255f * state.glassOpacity / 200f)), 0, 0, 0)
                    : Color.argb(Math.round(255f * state.glassOpacity / 100f), 255, 255, 255));
            canvas.drawRoundRect(glass, radius * .72f, radius * .72f, paint);
            paint.setShader(new LinearGradient(
                    glass.left, glass.top, glass.right, glass.bottom,
                    new int[]{
                            Color.argb(Math.round(255f * (.05f + state.highlight / 100f * .22f)), 255, 255, 255),
                            Color.TRANSPARENT,
                            Color.argb(Math.round(255f * (.03f + state.highlight / 100f * .10f)), 0, 0, 0)},
                    new float[]{0f, .58f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(glass, radius * .72f, radius * .72f, paint);
            paint.setShader(null);
            stroke.setColor(lightBackground
                    ? Color.argb(Math.round(255f * (.10f + state.highlight / 100f * .26f)), 0, 0, 0)
                    : Color.argb(Math.round(255f * (.10f + state.highlight / 100f * .62f)), 255, 255, 255));
            canvas.drawRoundRect(new RectF(glass.left + .5f, glass.top + .5f, glass.right - .5f, glass.bottom - .5f), radius * .72f, radius * .72f, stroke);

            stroke.setColor(lightBackground
                    ? Color.argb(52, 0, 0, 0)
                    : Color.argb(52, 255, 255, 255));
            canvas.drawRoundRect(new RectF(rect.left + .5f, rect.top + .5f, rect.right - .5f, rect.bottom - .5f), radius, radius, stroke);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(clamp(alpha, 0f, 1f) * 255f), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int mix(int color, int target, float amount) {
        float mix = clamp(amount, 0f, 1f);
        int r = Math.round(Color.red(color) + (Color.red(target) - Color.red(color)) * mix);
        int g = Math.round(Color.green(color) + (Color.green(target) - Color.green(color)) * mix);
        int b = Math.round(Color.blue(color) + (Color.blue(target) - Color.blue(color)) * mix);
        return Color.rgb(r, g, b);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }
}
