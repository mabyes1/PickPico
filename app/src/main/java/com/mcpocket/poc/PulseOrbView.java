package com.mcpocket.poc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    private final RectF arc = new RectF();
    private PickPicoTheme.State theme;
    private RuntimeShader fluid;
    private RuntimeShader flame;
    private boolean flameLoadAttempted;
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

    boolean isFlameAvailable() {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (!flameLoadAttempted) {
            flameLoadAttempted = true;
            try (InputStream input = getContext().getAssets().open("flame-orb.agsl")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                flame = new RuntimeShader(bytes.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception error) {
                android.util.Log.e("PickPicoOrb", "Flame shader unavailable; keeping original orb", error);
            }
        }
        return flame != null;
    }

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
        float pulseScale = heartbeatScale(t) * blockedAlertScale(now);
        int pulseSave = canvas.save();
        if (pulseScale != 1f) {
            canvas.scale(pulseScale, pulseScale, getWidth() * .5f, getHeight() * .5f);
        }
        if (PickPicoTheme.ORB_LANTERN.equals(theme.orbStyle)) {
            drawLantern(canvas, t, accent);
            drawBlockedAlert(canvas, now, accent);
            drawCompletedRipple(canvas, now);
            canvas.restoreToCount(pulseSave);
            if (animate) postInvalidateDelayed(mode.equals(PicoOrbState.RUNNING) || mode.equals(PicoOrbState.HUMAN_HELP) ? 33L : 50L);
            return;
        }
        boolean useFlame = PickPicoTheme.ORB_FLAME.equals(theme.orbStyle) && isFlameAvailable();
        RuntimeShader activeShader = useFlame ? flame : fluid;
        if (Build.VERSION.SDK_INT >= 33 && activeShader != null && canvas.isHardwareAccelerated()) {
            activeShader.setFloatUniform("resolution", (float)getWidth(), (float)getHeight());
            activeShader.setFloatUniform("time", t);
            activeShader.setFloatUniform("energy",
                    mode.equals(PicoOrbState.COMPLETED) || mode.equals(PicoOrbState.HUMAN_HELP) ? 1.08f : 1f);
            activeShader.setFloatUniform("tension", mode.equals(PicoOrbState.RUNNING) ? .58f
                    : mode.equals(PicoOrbState.HUMAN_HELP) ? .35f : 0f);
            // Flame keeps the chosen theme pigments; semantic alerts still use
            // their existing rings, heartbeat, timing, and completion ripple.
            activeShader.setColorUniform("primary", useFlame ? theme.colorA : accent);
            activeShader.setColorUniform("secondary", useFlame
                    ? (theme.gradient ? theme.colorB : theme.colorA) : secondary);
            // Drawing helpers such as blocked/completed ripples use STROKE. Paint is stateful,
            // so always restore the fluid body contract before drawing the next frame.
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(activeShader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            if (!useFlame) drawSparks(canvas, t, accent);
            drawBlockedAlert(canvas, now, accent);
            drawCompletedRipple(canvas, now);
            canvas.restoreToCount(pulseSave);
            if (animate) {
                if (useFlame) postInvalidateDelayed(33L);
                else postInvalidateOnAnimation();
            }
            return;
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float speed = mode.equals(PicoOrbState.RUNNING) ? 1.35f : connectionMode ? 1.25f : .42f;
        float breath = (float) Math.sin(t * speed);
        float r = Math.min(getWidth() * .33f, getHeight() * .37f) * (1 + .018f * breath);
        int light = blend(accent, Color.WHITE, .84f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r * 1.48f,
                new int[]{alpha(accent, 66), alpha(accent, 22), Color.TRANSPARENT},
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
        canvas.drawCircle(cx, cy, r, paint);

        int saved = canvas.save();
        wave.reset(); wave.addCircle(cx, cy, r, Path.Direction.CW); canvas.clipPath(wave);
        for (int i = 0; i < 4; i++) {
            float drift = (float) Math.sin(t * speed * .45f + i * .9f);
            wave.reset(); wave.moveTo(cx - r * 1.2f, cy + r * .12f + i * r * .12f);
            wave.cubicTo(cx - r * .45f, cy - r * .36f + drift * r * .15f,
                    cx + r * .2f, cy + r * .88f, cx + r * 1.2f, cy + r * .12f + drift * r * .12f);
            wave.lineTo(cx + r * 1.2f, cy + r * 1.2f); wave.lineTo(cx - r * 1.2f, cy + r * 1.2f); wave.close();
            paint.setShader(new LinearGradient(cx, cy - r * .2f, cx, cy + r,
                    alpha(light, 68 + i * 22), alpha(light, 215), Shader.TileMode.CLAMP));
            canvas.drawPath(wave, paint);
        }
        paint.setShader(new RadialGradient(cx - r * .52f, cy + r * .38f, r * .95f,
                new int[]{alpha(Color.WHITE, 218), alpha(light, 60), Color.TRANSPARENT},
                new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.5f, paint);
        canvas.restoreToCount(saved);

        paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f);
        paint.setColor(alpha(light, 135)); canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float orbitSpeed = mode.equals(PicoOrbState.RUNNING) ? 2.2f : .035f;
            double angle = i * 1.8 + t * orbitSpeed * (i % 2 == 0 ? 1 : -1);
            float orbit = r * (1.22f + (i % 2) * .14f);
            paint.setColor(alpha(light, 85 + i * 20));
            canvas.drawCircle(cx + (float)Math.cos(angle) * orbit, cy + (float)Math.sin(angle) * orbit,
                    getResources().getDisplayMetrics().density * (i == 2 ? 2 : 1.1f), paint);
        }
        drawBlockedAlert(canvas, now, accent);
        drawCompletedRipple(canvas, now);
        canvas.restoreToCount(pulseSave);
        if (animate) postInvalidateDelayed(mode.equals(PicoOrbState.RUNNING) || mode.equals(PicoOrbState.HUMAN_HELP) ? 33L : 50L);
    }

    // Lantern: a warm paper lantern with a candle inside and a small face that reacts to the
    // agent state. Pure Canvas, so it runs on every SDK. It keeps its own warm palette and
    // only leans toward the semantic colour when something needs attention.
    private static final int LANTERN_CLAY = Color.rgb(214, 122, 88);
    private static final int LANTERN_CREAM = Color.rgb(252, 238, 222);
    private static final int LANTERN_DEEP = Color.rgb(150, 74, 54);
    private static final int LANTERN_INK = Color.rgb(74, 38, 30);
    private static final int LANTERN_GLOW = Color.rgb(255, 210, 140);
    private static final int LANTERN_BLUSH = Color.rgb(240, 140, 130);

    private void drawLantern(Canvas canvas, float t, int accent) {
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        boolean running = mode.equals(PicoOrbState.RUNNING);
        boolean alerting = mode.equals(PicoOrbState.BLOCKED) || mode.equals(PicoOrbState.CONNECTION_ATTENTION)
                || mode.equals(PicoOrbState.HUMAN_HELP);
        float breath = (float) Math.sin(t * (running ? 2.4f : .9f));
        float r = Math.min(getWidth() * .32f, getHeight() * .35f) * (1 + .015f * breath);
        int clay = alerting ? blend(LANTERN_CLAY, accent, .45f) : LANTERN_CLAY;
        int deep = blend(LANTERN_DEEP, clay, .25f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r * 1.55f,
                new int[]{alpha(LANTERN_GLOW, running ? 72 : 46), alpha(clay, 18), Color.TRANSPARENT},
                new float[]{0f, .62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.55f, paint);

        // Caps sit behind the paper so the body overlaps them like a real lantern.
        paint.setShader(null);
        paint.setColor(deep);
        float capW = r * .2f, capH = r * .1f;
        arc.set(cx - capW, cy - r - capH, cx + capW, cy - r + capH * 1.2f);
        canvas.drawRoundRect(arc, capH * .5f, capH * .5f, paint);
        arc.set(cx - capW * .8f, cy + r - capH * 1.2f, cx + capW * .8f, cy + r + capH);
        canvas.drawRoundRect(arc, capH * .5f, capH * .5f, paint);

        // Paper body, lit from the upper left and deepening toward the rim.
        paint.setShader(new RadialGradient(cx - r * .32f, cy - r * .38f, r * 1.55f,
                new int[]{LANTERN_CREAM, blend(clay, LANTERN_CREAM, .35f), clay, deep},
                new float[]{0f, .28f, .66f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);

        int saved = canvas.save();
        wave.reset();
        wave.addCircle(cx, cy, r, Path.Direction.CW);
        canvas.clipPath(wave);
        float core = .5f + .5f * (float) Math.sin(t * (running ? 3.1f : 1.2f));
        paint.setShader(new RadialGradient(cx, cy + r * .2f, r * .85f,
                new int[]{alpha(LANTERN_GLOW, Math.round(60 + 80 * core)), alpha(LANTERN_GLOW, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);

        // Ribs turn slowly; only the near half of each meridian is drawn.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * .9f));
        float spin = t * (running ? .9f : .18f);
        for (int i = 0; i < 6; i++) {
            double phase = spin + i * Math.PI / 3.0;
            float facing = (float) Math.cos(phase);
            if (facing <= 0f) continue;
            float side = (float) Math.sin(phase);
            float half = r * Math.abs(side);
            arc.set(cx - half, cy - r, cx + half, cy + r);
            paint.setColor(alpha(deep, Math.round(70 * facing)));
            canvas.drawArc(arc, side >= 0f ? -90f : 90f, 180f, false, paint);
        }
        paint.setColor(alpha(deep, 60));
        for (float latitude : new float[]{-.72f, .72f}) {
            float half = r * (float) Math.sqrt(1 - latitude * latitude);
            float y = cy + r * latitude;
            arc.set(cx - half, y - half * .16f, cx + half, y + half * .16f);
            canvas.drawArc(arc, 0f, 180f, false, paint);
        }
        canvas.restoreToCount(saved);

        paint.setColor(alpha(LANTERN_CREAM, 110));
        paint.setStrokeWidth(Math.max(1f, density * .7f));
        canvas.drawCircle(cx, cy, r, paint);

        drawLanternFace(canvas, cx, cy, r, t);

        // A single ember drifting above the cap.
        float flicker = .55f + .45f * (float) (Math.sin(t * 5.3f) * Math.sin(t * 2.1f + 1f));
        float emberY = cy - r * 1.3f + (float) Math.sin(t * 1.7f) * r * .04f;
        float emberX = cx + (float) Math.sin(t * 1.1f) * r * .05f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(emberX, emberY, r * .14f,
                new int[]{alpha(LANTERN_GLOW, Math.round(200 * flicker)), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(emberX, emberY, r * .14f, paint);
        paint.setShader(null);
        paint.setColor(alpha(Color.WHITE, Math.round(220 * flicker)));
        canvas.drawCircle(emberX, emberY, Math.max(1f, r * .025f), paint);
    }

    private void drawLanternFace(Canvas canvas, float cx, float cy, float r, float t) {
        boolean running = mode.equals(PicoOrbState.RUNNING);
        boolean help = mode.equals(PicoOrbState.HUMAN_HELP);
        boolean tired = mode.equals(PicoOrbState.BLOCKED) || mode.equals(PicoOrbState.CONNECTION_ATTENTION);
        boolean happy = mode.equals(PicoOrbState.COMPLETED);
        boolean searching = mode.equals(PicoOrbState.CONNECTING);

        float scale = help ? 1.25f : 1f;
        float eyeW = r * .075f * scale, eyeH = r * .105f * scale;
        float lookX = running ? (float) Math.sin(t * 1.6f) * r * .06f
                : searching ? (float) Math.sin(t * .8f) * r * .08f : 0f;
        float lookY = help ? -r * .05f : 0f;
        float eyeY = cy + r * .06f + lookY;
        float gap = r * .22f;
        float cycle = running ? 3.2f : 4.6f;
        float blinkPhase = t % cycle;
        float open = blinkPhase < .14f ? Math.abs(blinkPhase - .07f) / .07f : 1f;

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(LANTERN_BLUSH, 80));
        for (int side = -1; side <= 1; side += 2) {
            float bx = cx + side * gap * 1.45f;
            arc.set(bx - r * .1f, eyeY + r * .1f, bx + r * .1f, eyeY + r * .2f);
            canvas.drawOval(arc, paint);
        }

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(1.2f, r * .035f));
        for (int side = -1; side <= 1; side += 2) {
            float ex = cx + side * gap + lookX;
            if (happy) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(LANTERN_INK);
                wave.reset();
                wave.moveTo(ex - eyeW * 1.2f, eyeY + eyeH * .3f);
                wave.quadTo(ex, eyeY - eyeH * 1.1f, ex + eyeW * 1.2f, eyeY + eyeH * .3f);
                canvas.drawPath(wave, paint);
            } else if (tired) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(LANTERN_INK);
                canvas.drawLine(ex - eyeW * 1.1f, eyeY, ex + eyeW * 1.1f, eyeY, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(LANTERN_INK);
                float h = Math.max(eyeH * .12f, eyeH * open);
                arc.set(ex - eyeW, eyeY - h, ex + eyeW, eyeY + h);
                canvas.drawOval(arc, paint);
                if (open > .6f) {
                    paint.setColor(alpha(Color.WHITE, 230));
                    canvas.drawCircle(ex - eyeW * .35f, eyeY - h * .4f, eyeW * .32f, paint);
                }
            }
        }

        float mouthY = eyeY + r * .19f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(LANTERN_INK);
        if (help) {
            paint.setStrokeWidth(Math.max(1f, r * .028f));
            canvas.drawCircle(cx + lookX * .5f, mouthY, r * .04f, paint);
        } else if (tired) {
            canvas.drawLine(cx - r * .05f, mouthY, cx + r * .05f, mouthY, paint);
        } else {
            float w = happy ? r * .1f : r * .07f;
            arc.set(cx - w + lookX * .5f, mouthY - w * .8f, cx + w + lookX * .5f, mouthY + w * .8f);
            canvas.drawArc(arc, 20f, 140f, false, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
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

    private float blockedAlertScale(long now) {
        if (!mode.equals(PicoOrbState.BLOCKED)) return 1f;
        float elapsed = Math.max(0f, (now - modeStartedAt) / 1000f);
        float throb = .5f + .5f * (float) Math.sin(elapsed * 2f * Math.PI / .92f);
        return 1f - .026f * throb;
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

    private void drawBlockedAlert(Canvas canvas, long now, int accent) {
        if (!mode.equals(PicoOrbState.BLOCKED)) return;
        float elapsed = Math.max(0f, (now - modeStartedAt) / 1000f);
        float wave = .5f + .5f * (float) Math.sin(elapsed * 2f * Math.PI / .92f);
        float inverse = 1f - wave;
        float base = Math.min(getWidth() * .335f, getHeight() * .397f);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * (1.15f + wave * .9f));
        paint.setColor(alpha(accent, (int) (82f + wave * 145f)));
        canvas.drawCircle(getWidth() * .5f, getHeight() * .5f,
                base * (1.12f + wave * .09f), paint);

        paint.setStrokeWidth(getResources().getDisplayMetrics().density * .8f);
        paint.setColor(alpha(blend(accent, Color.WHITE, .28f), (int) (38f + inverse * 88f)));
        canvas.drawCircle(getWidth() * .5f, getHeight() * .5f,
                base * (1.34f + inverse * .045f), paint);
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
