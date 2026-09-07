package com.mcpocket.poc;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Thin, consistent line icons instead of platform-dependent Unicode symbols. */
final class PulseGlyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private final int color;
    PulseGlyph(String kind, int color) { this.kind = kind; this.color = color; }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width()/24f, getBounds().height()/24f);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        Path p = new Path();
        if (kind.equals("home")) {
            p.moveTo(3, 10); p.lineTo(12, 3); p.lineTo(21, 10); p.moveTo(5, 9); p.lineTo(5, 21);
            p.lineTo(10, 21); p.lineTo(10, 15); p.lineTo(14, 15); p.lineTo(14, 21); p.lineTo(19, 21); p.lineTo(19, 9);
            canvas.drawPath(p, paint);
        } else if (kind.equals("activity")) {
            canvas.drawLine(5, 12, 5, 21, paint); canvas.drawLine(10, 4, 10, 21, paint);
            canvas.drawLine(15, 8, 15, 21, paint); canvas.drawLine(20, 14, 20, 21, paint);
        } else if (kind.equals("capabilities")) {
            for (int x=0;x<2;x++) for(int y=0;y<2;y++) canvas.drawRoundRect(3+x*11,3+y*11,10+x*11,10+y*11,1.5f,1.5f,paint);
        } else if (kind.equals("relay")) {
            canvas.drawLine(12, 7, 6, 17, paint); canvas.drawLine(12, 7, 18, 17, paint); canvas.drawLine(7,18,17,18,paint);
            canvas.drawCircle(12,5,2.5f,paint); canvas.drawCircle(5,19,2.5f,paint); canvas.drawCircle(19,19,2.5f,paint);
        } else if (kind.equals("layers")) {
            p.moveTo(3,8);p.lineTo(12,3);p.lineTo(21,8);p.lineTo(12,13);p.close();canvas.drawPath(p,paint);
            p.reset();p.moveTo(3,12);p.lineTo(12,17);p.lineTo(21,12);p.moveTo(3,16);p.lineTo(12,21);p.lineTo(21,16);canvas.drawPath(p,paint);
        } else if (kind.equals("folder")) {
            p.moveTo(3,6);p.lineTo(9,6);p.lineTo(11,8);p.lineTo(21,8);p.lineTo(21,20);p.lineTo(3,20);p.close();canvas.drawPath(p,paint);
        } else if (kind.equals("settings")) {
            canvas.drawCircle(12,12,6,paint);canvas.drawCircle(12,12,2,paint);
            for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,paint);}
        } else {
            // Neutral agent emblem; provider name in adjacent text supplies identity.
            for(int i=0;i<3;i++){canvas.save();canvas.rotate(i*60,12,12);canvas.drawOval(3,7,21,17,paint);canvas.restore();}
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,12,1.3f,paint);
        }
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
