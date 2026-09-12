package com.mcpocket.poc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Visual-only drop target shown while the Pico ball is being dragged. */
final class PicoOrbDismissTargetView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean armed;

    PicoOrbDismissTargetView(Context context) {
        super(context);
        setContentDescription("拖到這裡暫時關閉 Pico 球");
    }

    void setArmed(boolean armed) {
        if (this.armed == armed) return;
        this.armed = armed;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(4);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(armed ? Color.argb(235, 255, 77, 90) : Color.argb(190, 48, 52, 58));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(4));
        paint.setColor(Color.WHITE);
        float half = dp(10);
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint);
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
