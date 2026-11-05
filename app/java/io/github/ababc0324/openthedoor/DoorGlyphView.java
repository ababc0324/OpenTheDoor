package io.github.ababc0324.openthedoor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 自绘「门」图形 —— 仪式化身份的核心。
 * 关门：一扇带小窗与把手的门；开锁成功：门扇向铰链侧收拢（模拟开门），
 * 门内透出暖光 + 绿色对勾，传达「已开门 · 欢迎回来」。
 * 无第三方资源，纯 Canvas。
 */
public class DoorGlyphView extends View {

    private final Paint leafPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint interiorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private float open = 0f;          // 0=关 1=开
    private ValueAnimator animator;

    public DoorGlyphView(Context context) { this(context, null); }
    public DoorGlyphView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        framePaint.setStyle(Paint.Style.FILL);
        interiorPaint.setStyle(Paint.Style.FILL);
        leafPaint.setStyle(Paint.Style.FILL);
        detailPaint.setStyle(Paint.Style.FILL);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** 开门/关门。animate=true 播动画，否则直接设置。 */
    public void setOpened(boolean opened, boolean animate) {
        if (animator != null) { animator.cancel(); animator = null; }
        float target = opened ? 1f : 0f;
        if (!animate) { open = target; invalidate(); return; }
        animator = ValueAnimator.ofFloat(open, target);
        animator.setDuration(opened ? 520 : 280);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> { open = (Float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float doorW = w * 0.86f, doorH = h * 0.74f;
        float left = (w - doorW) / 2f, top = (h - doorH) / 2f;
        float radius = doorW * 0.10f;

        // 门洞（透出内部）—— 开锁后由暗转暖绿
        int interiorColor = Color.argb((int) (200 * open), 52, 211, 153);
        interiorPaint.setColor(interiorColor);
        tmp.set(left, top, left + doorW, top + doorH);
        canvas.drawRoundRect(tmp, radius, radius, interiorPaint);

        // 门扇：从右向左收拢（模拟绕铰链开启）
        float leafW = doorW * (1f - 0.60f * open);
        leafPaint.setColor(0xFF0B3A44);
        tmp.set(left, top, left + leafW, top + doorH);
        canvas.drawRoundRect(tmp, radius, radius, leafPaint);
        // 门扇描边
        framePaint.setColor(0x6622D3EE);
        tmp.set(left, top, left + leafW, top + doorH);
        canvas.drawRoundRect(tmp, radius, radius, framePaint);

        // 小窗（门扇上）
        if (leafW > doorW * 0.3f) {
            float winW = leafW * 0.4f, winH = doorH * 0.18f;
            detailPaint.setColor(0x8822D3EE);
            tmp.set(left + (leafW - winW) / 2f, top + doorH * 0.16f,
                    left + (leafW + winW) / 2f, top + doorH * 0.16f + winH);
            canvas.drawRoundRect(tmp, winW * 0.2f, winW * 0.2f, detailPaint);
        }

        // 门把手（门扇右缘）
        if (leafW > doorW * 0.3f) {
            float hr = doorW * 0.045f;
            float hx = left + leafW - hr * 2.4f;
            float hy = top + doorH * 0.52f;
            detailPaint.setColor(0xCCFFFFFF);
            canvas.drawCircle(hx, hy, hr, detailPaint);
        }

        // 开门后的对勾（门洞内）
        if (open > 0.55f) {
            float cx = left + doorW * 0.62f, cy = top + doorH * 0.5f;
            float s = doorW * 0.22f;
            checkPaint.setColor(0xFF34D399);
            checkPaint.setStrokeWidth(Math.max(2f, doorW * 0.06f));
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - s * 0.9f, cy);
            p.lineTo(cx - s * 0.25f, cy + s * 0.6f);
            p.lineTo(cx + s * 0.95f, cy - s * 0.55f);
            canvas.drawPath(p, checkPaint);
        }
    }
}
