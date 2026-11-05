package io.github.ababc0324.openthedoor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 深色科技风背景（静态、无动画、无波浪）。
 *
 * 底：深墨蓝(#0B0C0E→#0E1520) 纵向渐变；顶部极淡青色环境光晕，
 * 营造干净的科技感而不花哨。
 */
public class AmbientBackgroundView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AmbientBackgroundView(Context context) { this(context, null); }
    public AmbientBackgroundView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        glow.setColor(Color.argb(26, 34, 211, 238)); // #22D3EE @ ~10%
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                0xFF0E1520, 0xFF0B0C0E, Shader.TileMode.CLAMP));
        float diag = (float) Math.sqrt(w * w + h * h);
        glow.setShader(new RadialGradient(w * 0.15f, h * 0.05f, diag * 0.7f,
                0x1A22D3EE, 0x0022D3EE, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawRect(0, 0, w, h, glow);
    }
}
