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

/** Shared PickPico appearance model, intentionally mirroring VibeDeck's glass/theme tokens. */
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
        return mix(state.colorA, Color.WHITE, 0.36f);
    }

    static int accentB(State state) {
        return mix(state.colorB, Color.WHITE, 0.44f);
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
                paint.setShader(new LinearGradient(
                        0f, 0f, w, h,
                        new int[]{Color.argb(35, 255, 255, 255), Color.argb(90, 0, 0, 0)},
                        null,
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0f, 0f, w, h, paint);
                paint.setShader(null);
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

    /** VibeDeck surface formula translated to native Canvas layers. */
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
            int glassAlpha = Math.round(255f * state.glassOpacity / 100f);
            float highlightStrength = state.highlight / 100f;

            paint.setShader(null);
            if (strong) {
                paint.setColor(Color.argb(Math.min(138, 18 + glassAlpha * 3), 6, 8, 12));
            } else {
                paint.setColor(Color.argb(glassAlpha, 255, 255, 255));
            }
            canvas.drawRoundRect(rect, radius, radius, paint);

            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{Color.argb(Math.round(255f * (.025f + highlightStrength * .18f)), 255, 255, 255), Color.argb(1, 255, 255, 255)},
                    null,
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

            stroke.setColor(Color.argb(Math.round(255f * (.06f + highlightStrength * .44f)), 255, 255, 255));
            RectF edge = new RectF(rect.left + .5f, rect.top + .5f, rect.right - .5f, rect.bottom - .5f);
            canvas.drawRoundRect(edge, radius, radius, stroke);

            canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.right, rect.top + Math.max(12f, rect.height() * .26f));
            stroke.setColor(accented
                    ? withAlpha(accentA(state), .26f + highlightStrength * .62f)
                    : Color.argb(Math.round(255f * (.10f + highlightStrength * .68f)), 255, 255, 255));
            RectF shine = new RectF(rect.left + 1.5f, rect.top + 1.5f, rect.right - 1.5f, rect.bottom - 1.5f);
            canvas.drawRoundRect(shine, radius, radius, stroke);
            canvas.restore();
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
            paint.setColor(filled ? withAlpha(accent, .16f) : Color.argb(12, 255, 255, 255));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{Color.argb(18, 255, 255, 255), Color.TRANSPARENT},
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            stroke.setColor(filled ? withAlpha(accent, .38f) : Color.argb(31, 255, 255, 255));
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
            paint.setColor(Color.argb(Math.round(255f * state.glassOpacity / 100f), 255, 255, 255));
            canvas.drawRoundRect(glass, radius * .72f, radius * .72f, paint);
            paint.setShader(new LinearGradient(
                    glass.left, glass.top, glass.right, glass.bottom,
                    new int[]{Color.argb(Math.round(255f * (.03f + state.highlight / 100f * .22f)), 255, 255, 255), Color.TRANSPARENT},
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(glass, radius * .72f, radius * .72f, paint);
            paint.setShader(null);
            stroke.setColor(Color.argb(Math.round(255f * (.10f + state.highlight / 100f * .62f)), 255, 255, 255));
            canvas.drawRoundRect(new RectF(glass.left + .5f, glass.top + .5f, glass.right - .5f, glass.bottom - .5f), radius * .72f, radius * .72f, stroke);

            stroke.setColor(Color.argb(52, 255, 255, 255));
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
