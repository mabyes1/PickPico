package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Compose exactly the app background and content card, rather than tinting the desktop. */
final class PicoPanelSurface extends Drawable {
    private final PickPicoTheme.BackgroundView background;
    private final Drawable card;
    private final float radius;
    private int alpha = 255;

    PicoPanelSurface(Context context, PickPicoTheme.State theme, float radius) {
        background = new PickPicoTheme.BackgroundView(context, theme);
        card = PickPicoTheme.card(theme, radius, false);
        this.radius = radius;
    }

    @Override public void draw(Canvas canvas) {
        int w = getBounds().width(), h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        int saved = canvas.saveLayerAlpha(new RectF(getBounds()), alpha);
        canvas.translate(getBounds().left, getBounds().top);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        background.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        background.layout(0, 0, w, h);
        background.draw(canvas);
        card.setBounds(0, 0, w, h);
        card.draw(canvas);
        canvas.restoreToCount(saved);
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { card.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
