package io.github.ababc0324.openthedoor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自绘挂锁图形（无第三方资源）。用于英雄开锁按钮中央。
 * 锁体 = 圆角矩形 + 钥匙孔；锁梁 = 半圆弧。unlocked 时锁梁上抬。
 */
public class LockGlyphView extends View {

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shacklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shacklePath = new Path();
    private final RectF tmp = new RectF();

    private int color = Color.WHITE;
    private int holeColor = 0x55000000;
    private boolean unlocked = false;

    public LockGlyphView(Context context) { this(context, null); }
    public LockGlyphView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        bodyPaint.setStyle(Paint.Style.FILL);
        shacklePaint.setStyle(Paint.Style.STROKE);
        shacklePaint.setStrokeCap(Paint.Cap.ROUND);
        holePaint.setStyle(Paint.Style.FILL);
        applyColors();
    }

    public void setColor(int c) { color = c; applyColors(); invalidate(); }
    public void setUnlocked(boolean u) { if (unlocked != u) { unlocked = u; invalidate(); } }

    private void applyColors() {
        bodyPaint.setColor(color);
        shacklePaint.setColor(color);
        holePaint.setColor(holeColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float stroke = Math.max(1f, w * 0.10f);
        shacklePaint.setStrokeWidth(stroke);

        // 锁梁：半圆弧（unlocked 时上抬）
        float shackleR = w * 0.20f;
        float shackleCy = unlocked ? h * 0.30f : h * 0.34f;
        tmp.set(cx - shackleR, shackleCy - shackleR, cx + shackleR, shackleCy + shackleR);
        canvas.drawArc(tmp, 180f, 180f, false, shacklePaint);
        // 锁梁两腿
        float legBottom = h * 0.50f;
        canvas.drawLine(cx - shackleR, shackleCy, cx - shackleR, legBottom, shacklePaint);
        canvas.drawLine(cx + shackleR, shackleCy, cx + shackleR, legBottom, shacklePaint);

        // 锁体
        float bodyTop = h * 0.46f;
        float bodyBottom = h * 0.88f;
        float bodyLeft = w * 0.16f;
        float bodyRight = w * 0.84f;
        float radius = w * 0.10f;
        tmp.set(bodyLeft, bodyTop, bodyRight, bodyBottom);
        canvas.drawRoundRect(tmp, radius, radius, bodyPaint);

        // 钥匙孔
        float holeCy = (bodyTop + bodyBottom) / 2f;
        float holeR = w * 0.055f;
        canvas.drawCircle(cx, holeCy, holeR, holePaint);
        tmp.set(cx - holeR * 0.55f, holeCy, cx + holeR * 0.55f, holeCy + h * 0.10f);
        canvas.drawRoundRect(tmp, holeR * 0.55f, holeR * 0.55f, holePaint);
    }
}
