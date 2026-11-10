package com.phonelink.phonelinkserver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Picture for each setup step, drawn on a 280x200 grid and scaled to fit. */
final class Illustration extends View {
    static final int INK = 0xFF10202E;
    static final int GROUND = 0xFFF3F6F8;
    static final int TRACK = 0xFFCFDAE2;
    static final int ACCENT = 0xFF0B6E99;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int step;

    Illustration(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(3);
    }

    void setStep(int value) {
        if (step == value) return;
        step = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(width / 280f, height / 200f);
        c.translate(getPaddingLeft() + (width - 280 * scale) / 2, getPaddingTop() + (height - 200 * scale) / 2);
        c.scale(scale, scale);
        if (step == 0) {
            drawLandscape(c);
        } else if (step == 1) {
            drawTouch(c);
        } else {
            drawMirror(c);
        }
    }

    private void box(Canvas c, float x, float y, float w, float h, float r, int color) {
        fill.setColor(color);
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, r, r, fill);
    }

    private void stroke(int color, int alpha, boolean dashed) {
        line.setColor(color);
        line.setAlpha(alpha);
        line.setPathEffect(dashed ? new DashPathEffect(new float[] {1, 8}, 0) : null);
    }

    private void car(Canvas c) {
        box(c, 10, 30, 170, 104, 12, INK);
        box(c, 19, 39, 152, 86, 5, GROUND);
        box(c, 31, 51, 62, 9, 4.5f, TRACK);
        box(c, 31, 68, 92, 9, 4.5f, TRACK);
        box(c, 31, 85, 48, 26, 8, ACCENT);
    }

    private void phone(Canvas c) {
        box(c, 192, 44, 78, 146, 14, INK);
        box(c, 199, 52, 64, 130, 8, GROUND);
        box(c, 207, 66, 30, 7, 3.5f, TRACK);
        box(c, 207, 82, 48, 7, 3.5f, TRACK);
        box(c, 207, 104, 24, 24, 6, ACCENT);
    }

    /** A phone lying sideways, with an arrow showing the turn from upright. */
    private void drawLandscape(Canvas c) {
        stroke(INK, 70, false);
        rect.set(108, 14, 172, 134);
        c.drawRoundRect(rect, 12, 12, line);
        box(c, 40, 64, 200, 104, 14, INK);
        box(c, 49, 73, 182, 86, 8, GROUND);
        box(c, 62, 86, 72, 9, 4.5f, TRACK);
        box(c, 62, 103, 120, 9, 4.5f, TRACK);
        box(c, 62, 120, 54, 26, 8, ACCENT);
        stroke(ACCENT, 255, false);
        Path arrow = new Path();
        arrow.moveTo(190, 22);
        arrow.cubicTo(216, 28, 232, 42, 238, 56);
        arrow.moveTo(226, 52);
        arrow.lineTo(238, 57);
        arrow.lineTo(243, 44);
        c.drawPath(arrow, line);
    }

    /** A tap on the car screen, passed along to the phone. */
    private void drawTouch(Canvas c) {
        car(c);
        phone(c);
        stroke(ACCENT, 115, false);
        c.drawCircle(55, 98, 24, line);
        stroke(ACCENT, 51, false);
        c.drawCircle(55, 98, 35, line);
        Path link = new Path();
        link.moveTo(92, 150);
        link.cubicTo(125, 178, 165, 176, 190, 150);
        stroke(ACCENT, 255, true);
        c.drawPath(link, line);
        Path tip = new Path();
        tip.moveTo(180, 148);
        tip.lineTo(191, 149);
        tip.lineTo(189, 160);
        stroke(ACCENT, 255, false);
        c.drawPath(tip, line);
    }

    /** The phone wired to the car, with the same picture on both. */
    private void drawMirror(Canvas c) {
        car(c);
        phone(c);
        Path cable = new Path();
        cable.moveTo(95, 134);
        cable.cubicTo(95, 186, 190, 196, 231, 190);
        stroke(ACCENT, 255, false);
        c.drawPath(cable, line);
    }
}
