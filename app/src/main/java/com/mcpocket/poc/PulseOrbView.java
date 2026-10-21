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
    private RuntimeShader mist;
    private boolean mistLoadAttempted;
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

    private RuntimeShader lanternMist() {
        if (Build.VERSION.SDK_INT < 33) return null;
        if (!mistLoadAttempted) {
            mistLoadAttempted = true;
            try (InputStream input = getContext().getAssets().open("lantern-mist.agsl")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                mist = new RuntimeShader(bytes.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception error) {
                android.util.Log.e("PickPicoOrb", "Lantern mist shader unavailable; using puffs", error);
            }
        }
        return mist;
    }

    /** layer 0 is drawn behind the lantern, layer 1 is a thin veil across its base. */
    private void drawLanternMist(Canvas canvas, float t, int warm, float layer, boolean running) {
        float energy = running ? 1.25f : 1f;
        RuntimeShader shader = canvas.isHardwareAccelerated() ? lanternMist() : null;
        if (shader != null) {
            shader.setFloatUniform("resolution", (float) getWidth(), (float) getHeight());
            shader.setFloatUniform("time", t);
            shader.setFloatUniform("energy", energy);
            shader.setFloatUniform("layer", layer);
            shader.setColorUniform("warm", warm);
            shader.setColorUniform("cool", blend(theme.colorA, Color.WHITE, .86f));
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(shader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            return;
        }
        // Fallback: soft puffs rising and drifting, same palette and rhythm as the shader.
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * .3f;
        int cool = blend(theme.colorA, Color.WHITE, .86f);
        int count = layer < .5f ? 7 : 3;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float phase = (t * .05f + i / (float) count + layer * .37f) % 1f;
            float side = (i % 2 == 0 ? -1f : 1f) * (.55f + .55f * (float) Math.abs(Math.sin(i * 2.3f + layer)));
            float sway = (float) Math.sin(phase * 5.2f + i * 1.7f) * r * .14f;
            float px = cx + side * r * (layer < .5f ? .8f : .6f) + sway;
            float py = layer < .5f
                    ? cy + r * .95f - phase * r * 2.1f
                    : cy + r * (.7f + .35f * (float) Math.sin(i * 1.9f)) ;
            float radius = r * (.5f + .4f * (float) Math.abs(Math.sin(i * 1.3f + 2f))) * (.8f + phase * .5f);
            float fade = (float) Math.sin(phase * Math.PI);
            int base = blend(cool, warm, Math.max(0f, 1f - (float) Math.hypot(px - cx, py - cy) / (r * 1.6f)) * .75f);
            int a = Math.round((layer < .5f ? 54f : 40f) * fade * energy);
            paint.setShader(new RadialGradient(px, py, radius,
                    new int[]{alpha(base, a), alpha(base, a / 3), Color.TRANSPARENT},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(px, py, radius, paint);
        }
        paint.setShader(null);
    }

    /** Largest ring radius that still fits inside this view, so rings fade instead of being cut. */
    private float ringLimit() {
        return Math.min(getWidth(), getHeight()) / 2f - getResources().getDisplayMetrics().density * 2f;
    }

    // Lantern: a paper lantern lit from inside, with a small printed face that reacts to the
    // agent state. Pure Canvas, so it runs on every SDK. It keeps its own warm palette and
    // only leans toward the semantic colour when something needs attention.
    private static final int LANTERN_CORE = Color.rgb(255, 238, 196);
    private static final int LANTERN_AMBER = Color.rgb(255, 178, 88);
    private static final int LANTERN_ORANGE = Color.rgb(240, 112, 54);
    private static final int LANTERN_RIM = Color.rgb(168, 56, 36);
    private static final int LANTERN_RIB = Color.rgb(124, 46, 28);
    private static final int LANTERN_LACQUER = Color.rgb(58, 34, 30);
    private static final int LANTERN_GOLD = Color.rgb(236, 184, 96);
    private static final int LANTERN_TASSEL = Color.rgb(200, 66, 46);
    private static final int LANTERN_INK = Color.rgb(74, 35, 24);
    private static final int LANTERN_BLUSH = Color.rgb(255, 130, 112);

    private void drawLantern(Canvas canvas, float t, int accent) {
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        boolean running = mode.equals(PicoOrbState.RUNNING);
        // Only trouble tints the paper; help and progress are told by the face and the rings.
        boolean troubled = mode.equals(PicoOrbState.BLOCKED) || mode.equals(PicoOrbState.CONNECTION_ATTENTION);
        float breath = (float) Math.sin(t * (running ? 2.4f : .9f));
        float r = Math.min(getWidth(), getHeight()) * .3f * (1 + .012f * breath);
        float rx = r * 1.04f, ry = r * .94f;
        float tint = troubled ? .25f : 0f;
        int amber = blend(LANTERN_AMBER, accent, tint);
        int orange = blend(LANTERN_ORANGE, accent, tint);
        int rim = blend(LANTERN_RIM, accent, tint * .6f);
        float flame = .5f + .5f * (float) Math.sin(t * (running ? 3.1f : 1.2f));

        // Smoke first, so the lantern floats in it.
        drawLanternMist(canvas, t, amber, 0f, running);

        // Light spilling onto the page.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy + ry * .08f, r * 1.85f,
                new int[]{alpha(amber, Math.round(96 + 50 * flame)), alpha(orange, 40), Color.TRANSPARENT},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * 1.85f, paint);
        paint.setShader(null);

        // Hanging cord.
        float capTop = cy - ry - ry * .11f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * 1.1f));
        paint.setColor(alpha(LANTERN_LACQUER, 230));
        canvas.drawLine(cx, capTop - r * .34f, cx, capTop, paint);

        // Tassel, swaying gently below the lantern.
        float tasselTop = cy + ry + ry * .09f;
        int swing = canvas.save();
        canvas.rotate((float) Math.sin(t * 1.3f) * (running ? 9f : 5f), cx, tasselTop);
        canvas.drawLine(cx, tasselTop, cx, tasselTop + r * .1f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(LANTERN_GOLD);
        canvas.drawCircle(cx, tasselTop + r * .12f, r * .035f, paint);
        float tw = r * .07f, tTop = tasselTop + r * .14f, tBottom = tasselTop + r * .4f;
        wave.reset();
        wave.moveTo(cx - tw * .45f, tTop);
        wave.lineTo(cx + tw * .45f, tTop);
        wave.quadTo(cx + tw * 1.1f, tBottom - r * .06f, cx + tw, tBottom);
        wave.lineTo(cx - tw, tBottom);
        wave.quadTo(cx - tw * 1.1f, tBottom - r * .06f, cx - tw * .45f, tTop);
        wave.close();
        paint.setColor(LANTERN_TASSEL);
        canvas.drawPath(wave, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * .6f));
        paint.setColor(alpha(LANTERN_RIB, 150));
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(cx + i * tw * .45f, tTop + r * .04f, cx + i * tw * .6f, tBottom - r * .01f, paint);
        }
        canvas.restoreToCount(swing);

        // Paper body: brightest at the candle, deepening toward the rim.
        arc.set(cx - rx, cy - ry, cx + rx, cy + ry);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy + ry * .12f, rx * 1.02f,
                new int[]{blend(LANTERN_CORE, amber, .2f * (1f - flame)), amber, orange, rim},
                new float[]{0f, .34f, .76f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawOval(arc, paint);

        int saved = canvas.save();
        wave.reset();
        wave.addOval(arc, Path.Direction.CW);
        canvas.clipPath(wave);
        // Back ribs show faintly through the lit paper; front ribs sit on top.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        float spin = t * (running ? .8f : .16f);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 8; i++) {
                double phase = spin + i * Math.PI / 4.0;
                float facing = (float) Math.cos(phase);
                boolean front = facing > 0f;
                if (front != (pass == 1)) continue;
                float side = (float) Math.sin(phase);
                float half = rx * Math.abs(side);
                arc.set(cx - half, cy - ry, cx + half, cy + ry);
                paint.setStrokeWidth(Math.max(1f, density * (front ? 1.4f : .9f)));
                paint.setColor(alpha(LANTERN_RIB, front ? Math.round(60 + 90 * facing) : 40));
                canvas.drawArc(arc, side >= 0f ? -90f : 90f, 180f, false, paint);
            }
        }
        arc.set(cx - rx, cy - ry, cx + rx, cy + ry);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, rx,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, alpha(rim, 120)},
                new float[]{0f, .68f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawOval(arc, paint);
        paint.setShader(new RadialGradient(cx - rx * .38f, cy - ry * .45f, rx * .5f,
                new int[]{alpha(Color.WHITE, 64), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawOval(arc, paint);
        canvas.restoreToCount(saved);
        paint.setShader(null);

        // Bamboo rings near the poles.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, density * 1.6f));
        paint.setColor(alpha(LANTERN_RIB, 150));
        for (float latitude : new float[]{-.84f, .84f}) {
            float half = rx * (float) Math.sqrt(1 - latitude * latitude);
            float y = cy + ry * latitude;
            arc.set(cx - half, y - half * .14f, cx + half, y + half * .14f);
            canvas.drawArc(arc, 0f, 180f, false, paint);
        }

        drawLanternCap(canvas, cx, cy - ry, rx * .3f, ry * .17f, true, density);
        drawLanternCap(canvas, cx, cy + ry, rx * .24f, ry * .14f, false, density);

        drawLanternFace(canvas, cx, cy, r, t);

        // Three small sparks drifting up from the top.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float p = (t * (running ? .5f : .22f) + i / 3f) % 1f;
            float sy = capTop - p * r * .55f;
            float sx = cx + (float) Math.sin(p * 6f + i * 2f) * r * .2f;
            paint.setColor(alpha(LANTERN_AMBER, Math.round((float) Math.sin(p * Math.PI) * 190)));
            canvas.drawCircle(sx, sy, Math.max(1f, density * 1.4f), paint);
        }

        // A thin veil drifting across the base, in front of the tassel.
        drawLanternMist(canvas, t, amber, 1f, running);
    }

    /** Lacquered cap with a gold rim; {@code poleY} is where the paper meets the cap. */
    private void drawLanternCap(Canvas canvas, float cx, float poleY, float halfW, float h,
            boolean top, float density) {
        float inner = top ? poleY + h * .35f : poleY - h * .35f;
        float outer = top ? poleY - h * .65f : poleY + h * .65f;
        wave.reset();
        wave.moveTo(cx - halfW, inner);
        wave.lineTo(cx + halfW, inner);
        wave.lineTo(cx + halfW * .72f, outer);
        wave.lineTo(cx - halfW * .72f, outer);
        wave.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(LANTERN_LACQUER);
        canvas.drawPath(wave, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(alpha(LANTERN_GOLD, 210));
        canvas.drawLine(cx - halfW, inner, cx + halfW, inner, paint);
    }

    private void drawLanternFace(Canvas canvas, float cx, float cy, float r, float t) {
        boolean running = mode.equals(PicoOrbState.RUNNING);
        boolean help = mode.equals(PicoOrbState.HUMAN_HELP);
        boolean tired = mode.equals(PicoOrbState.BLOCKED) || mode.equals(PicoOrbState.CONNECTION_ATTENTION);
        boolean happy = mode.equals(PicoOrbState.COMPLETED);
        boolean searching = mode.equals(PicoOrbState.CONNECTING);

        float scale = help ? 1.2f : 1f;
        float eyeW = r * .085f * scale, eyeH = r * .115f * scale;
        float lookX = running ? (float) Math.sin(t * 1.6f) * r * .06f
                : searching ? (float) Math.sin(t * .8f) * r * .08f : 0f;
        float lookY = help ? -r * .05f : 0f;
        float eyeY = cy + r * .1f + lookY;
        float gap = r * .27f;
        float cycle = running ? 3.2f : 4.6f;
        float blinkPhase = t % cycle;
        float open = blinkPhase < .14f ? Math.abs(blinkPhase - .07f) / .07f : 1f;
        int ink = alpha(LANTERN_INK, 235);

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(LANTERN_BLUSH, 110));
        for (int side = -1; side <= 1; side += 2) {
            float bx = cx + side * gap * 1.5f;
            arc.set(bx - r * .12f, eyeY + r * .13f, bx + r * .12f, eyeY + r * .26f);
            canvas.drawOval(arc, paint);
        }

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(1.2f, r * .04f));
        for (int side = -1; side <= 1; side += 2) {
            float ex = cx + side * gap + lookX;
            if (happy) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ink);
                wave.reset();
                wave.moveTo(ex - eyeW * 1.2f, eyeY + eyeH * .3f);
                wave.quadTo(ex, eyeY - eyeH * 1.1f, ex + eyeW * 1.2f, eyeY + eyeH * .3f);
                canvas.drawPath(wave, paint);
            } else if (tired) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ink);
                canvas.drawLine(ex - eyeW * 1.1f, eyeY, ex + eyeW * 1.1f, eyeY, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ink);
                float h = Math.max(eyeH * .12f, eyeH * open);
                arc.set(ex - eyeW, eyeY - h, ex + eyeW, eyeY + h);
                canvas.drawOval(arc, paint);
                if (open > .6f) {
                    paint.setColor(alpha(Color.WHITE, 235));
                    canvas.drawCircle(ex - eyeW * .32f, eyeY - h * .38f, eyeW * .34f, paint);
                }
            }
        }

        float mouthY = eyeY + r * .2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(ink);
        if (help) {
            paint.setStrokeWidth(Math.max(1f, r * .03f));
            canvas.drawCircle(cx + lookX * .5f, mouthY, r * .045f, paint);
        } else if (tired) {
            canvas.drawLine(cx - r * .055f, mouthY, cx + r * .055f, mouthY, paint);
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
        float limit = ringLimit();
        canvas.drawCircle(getWidth() * .5f, getHeight() * .5f,
                Math.min(base * (1.12f + wave * .09f), limit - getResources().getDisplayMetrics().density * 3f), paint);

        paint.setStrokeWidth(getResources().getDisplayMetrics().density * .8f);
        paint.setColor(alpha(blend(accent, Color.WHITE, .28f), (int) (38f + inverse * 88f)));
        canvas.drawCircle(getWidth() * .5f, getHeight() * .5f,
                Math.min(base * (1.34f + inverse * .045f), limit), paint);
    }

    private void drawCompletedRipple(Canvas canvas, long now) {
        if (!mode.equals(PicoOrbState.COMPLETED)) return;
        float elapsed = Math.max(0f, (now - modeStartedAt) / 1000f);
        if (elapsed > .9f) return;
        float progress = elapsed / .9f;
        float eased = 1f - (1f - progress) * (1f - progress);
        float base = Math.min(getWidth() * .335f, getHeight() * .397f);
        float radius = Math.min(base * (1.02f + eased * .78f), ringLimit());
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
