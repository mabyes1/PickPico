package com.mcpocket.poc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.os.Build;
import android.graphics.RuntimeShader;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.view.View;

/** Procedural light, no bitmap assets. Animation stops whenever the page is hidden. */
final class PulseOrbView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private PickPicoTheme.State theme;
    private RuntimeShader fluid;
    private long lastFrame;
    private long modeStartedAt = SystemClock.uptimeMillis();
    private float flowTime;
    private String mode = "idle";
    private boolean resumed;

    PulseOrbView(Context context, PickPicoTheme.State theme) {
        super(context);
        this.theme = theme;
        if (Build.VERSION.SDK_INT >= 33) {
            try (InputStream input = context.getAssets().open("pulse-orb.agsl")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                fluid = new RuntimeShader(bytes.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception error) {
                android.util.Log.e("PickPicoOrb", "Fluid shader unavailable", error);
            }
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setMode(String value) {
        if (!mode.equals(value)) {
            mode = value;
            modeStartedAt = SystemClock.uptimeMillis();
            invalidate();
        }
    }
    void setResumed(boolean value) { resumed = value; lastFrame = 0; if (value) invalidate(); }
    void setTheme(PickPicoTheme.State value) { theme = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean animate = resumed && isShown() && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
        long now = SystemClock.uptimeMillis();
        boolean connectionMode = mode.equals(PicoOrbState.CONNECTING) || mode.equals(PicoOrbState.CONNECTION_ATTENTION);
        float timeScale = mode.equals(PicoOrbState.RUNNING) ? 1.9f
                : connectionMode ? 1.1f
                : mode.equals(PicoOrbState.HUMAN_HELP) ? 1.25f
                : mode.equals(PicoOrbState.BLOCKED) ? .55f : .7f;
        if (animate && lastFrame != 0) flowTime += Math.min(0.05f, (now - lastFrame) / 1000f)
                * timeScale;
        lastFrame = animate ? now : 0;
        float t = flowTime;
        int accent = PicoOrbState.primary(mode, theme.colorA);
        int secondary = PicoOrbState.secondary(mode, theme.gradient ? theme.colorB : theme.colorA);
        float pulseScale = heartbeatScale(t);
        int pulseSave = canvas.save();
        if (pulseScale != 1f) {
            canvas.scale(pulseScale, pulseScale, getWidth() * .5f, getHeight() * .5f);
        }
        if (Build.VERSION.SDK_INT >= 33 && fluid != null && canvas.isHardwareAccelerated()) {
            fluid.setFloatUniform("resolution", (float)getWidth(), (float)getHeight());
            fluid.setFloatUniform("time", t);
            fluid.setFloatUniform("energy", mode.equals(PicoOrbState.BLOCKED) ? .72f
                    : mode.equals(PicoOrbState.COMPLETED) || mode.equals(PicoOrbState.HUMAN_HELP) ? 1.08f : 1f);
            fluid.setFloatUniform("tension", mode.equals(PicoOrbState.BLOCKED) ? 1f
                    : mode.equals(PicoOrbState.RUNNING) ? .58f
                    : mode.equals(PicoOrbState.HUMAN_HELP) ? .35f : 0f);
            fluid.setColorUniform("primary", accent);
            fluid.setColorUniform("secondary", secondary);
            paint.setShader(fluid);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            drawSparks(canvas, t, accent);
            drawCompletedRipple(canvas, now);
            canvas.restoreToCount(pulseSave);
            if (animate) postInvalidateOnAnimation();
            return;
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float speed = mode.equals(PicoOrbState.RUNNING) ? 1.35f : connectionMode ? 1.25f : .42f;
        float breath = (float) Math.sin(t * speed);
        float r = Math.min(getWidth() * .33f, getHeight() * .37f) * (1 + .018f * breath);
        boolean blocked = mode.equals(PicoOrbState.BLOCKED);
        int light = blend(accent, Color.WHITE, .84f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r * 1.48f,
                new int[]{alpha(accent, blocked ? 25 : 66), alpha(accent, 22), Color.TRANSPARENT},
                new float[]{0, .64f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.48f, paint);

        paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getResources().getDisplayMetrics().density * .65f);
        paint.setColor(alpha(accent, mode.equals(PicoOrbState.HUMAN_HELP) ? 112 : 32));
        canvas.drawCircle(cx, cy, r * 1.23f + breath * 2, paint);
        paint.setColor(alpha(accent, 18)); canvas.drawCircle(cx, cy, r * 1.38f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - r, cy - r, cx + r * .4f, cy + r,
                new int[]{blend(accent, Color.WHITE, .12f), accent, secondary, light},
                new float[]{0, .36f, .69f, 1}, Shader.TileMode.CLAMP));
        paint.setAlpha(blocked ? 135 : 255);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setAlpha(255);

        int saved = canvas.save();
        wave.reset(); wave.addCircle(cx, cy, r, Path.Direction.CW); canvas.clipPath(wave);
        for (int i = 0; i < 4; i++) {
            float drift = (float) Math.sin(t * speed * .45f + i * .9f);
            wave.reset(); wave.moveTo(cx - r * 1.2f, cy + r * .12f + i * r * .12f);
            wave.cubicTo(cx - r * .45f, cy - r * .36f + drift * r * .15f,
                    cx + r * .2f, cy + r * .88f, cx + r * 1.2f, cy + r * .12f + drift * r * .12f);
            wave.lineTo(cx + r * 1.2f, cy + r * 1.2f); wave.lineTo(cx - r * 1.2f, cy + r * 1.2f); wave.close();
            paint.setShader(new LinearGradient(cx, cy - r * .2f, cx, cy + r,
                    alpha(light, blocked ? 30 : 68 + i * 22), alpha(light, blocked ? 48 : 215), Shader.TileMode.CLAMP));
            canvas.drawPath(wave, paint);
        }
        paint.setShader(new RadialGradient(cx - r * .52f, cy + r * .38f, r * .95f,
                new int[]{alpha(Color.WHITE, blocked ? 70 : 218), alpha(light, 60), Color.TRANSPARENT},
                new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.5f, paint);
        canvas.restoreToCount(saved);

        paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f);
        paint.setColor(alpha(light, blocked ? 50 : 135)); canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float orbitSpeed = mode.equals(PicoOrbState.RUNNING) ? 2.2f : .035f;
            double angle = i * 1.8 + t * orbitSpeed * (i % 2 == 0 ? 1 : -1);
            float orbit = r * (1.22f + (i % 2) * .14f);
            paint.setColor(alpha(light, blocked ? 30 : 85 + i * 20));
            canvas.drawCircle(cx + (float)Math.cos(angle) * orbit, cy + (float)Math.sin(angle) * orbit,
                    getResources().getDisplayMetrics().density * (i == 2 ? 2 : 1.1f), paint);
        }
        drawCompletedRipple(canvas, now);
        canvas.restoreToCount(pulseSave);
        if (animate) postInvalidateDelayed(mode.equals(PicoOrbState.RUNNING) || mode.equals(PicoOrbState.HUMAN_HELP) ? 33L : 50L);
    }

    private float heartbeatScale(float time) {
        if (!mode.equals(PicoOrbState.HUMAN_HELP)) return 1f;
        float phase = time % 1.65f;
        float first = gaussian(phase, .17f, .075f);
        float second = gaussian(phase, .48f, .09f);
        return 1f + .045f * first + .032f * second;
    }

    private static float gaussian(float value, float center, float width) {
        float normalized = (value - center) / width;
        return (float) Math.exp(-normalized * normalized);
    }

    private void drawSparks(Canvas canvas, float time, int accent) {
        float r = Math.min(getWidth() * .335f, getHeight() * .397f);
        paint.setStyle(Paint.Style.FILL);
        float orbitSpeed = mode.equals(PicoOrbState.RUNNING) ? 2.35f : .04f;
        for (int i = 0; i < 6; i++) {
            double direction = i % 2 == 0 ? 1d : -1d;
            double angle = i * 2.39996 + time * orbitSpeed * direction;
            float orbit = r * (i % 2 == 0 ? 1.255f : 1.43f);
            float x = getWidth() * .5f + (float)Math.cos(angle) * orbit;
            float y = getHeight() * .5f + (float)Math.sin(angle) * orbit;
            float size = getResources().getDisplayMetrics().density * (i == 1 ? 2 : 1);
            paint.setShader(new RadialGradient(x, y, size * 4, alpha(accent, 130), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, size * 4, paint);
            paint.setShader(null); paint.setColor(alpha(blend(accent, Color.WHITE, .5f), 175));
            canvas.drawCircle(x, y, size, paint);
        }
    }

    private void drawCompletedRipple(Canvas canvas, long now) {
        if (!mode.equals(PicoOrbState.COMPLETED)) return;
        float elapsed = Math.max(0f, (now - modeStartedAt) / 1000f);
        if (elapsed > .9f) return;
        float progress = elapsed / .9f;
        float eased = 1f - (1f - progress) * (1f - progress);
        float base = Math.min(getWidth() * .335f, getHeight() * .397f);
        float radius = base * (1.02f + eased * .78f);
        int alpha = (int) (185f * (1f - progress));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.6f);
        paint.setColor(alpha(Color.WHITE, alpha));
        canvas.drawCircle(getWidth() * .5f, getHeight() * .5f, radius, paint);
    }

    private static int alpha(int color, int alpha) { return (color & 0xffffff) | (alpha << 24); }
    private static int blend(int a, int b, float ratio) {
        return Color.rgb((int)(Color.red(a)*(1-ratio)+Color.red(b)*ratio),
                (int)(Color.green(a)*(1-ratio)+Color.green(b)*ratio), (int)(Color.blue(a)*(1-ratio)+Color.blue(b)*ratio));
    }
}
