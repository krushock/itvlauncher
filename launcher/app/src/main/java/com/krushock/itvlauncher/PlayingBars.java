package com.krushock.itvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Apple TV's "playing" indicator: three bars that bounce while audio plays and rest, low, when it's paused.
 * Takes its colour from the pill text colour, so it turns dark with the rest of a focused pill.
 */
public class PlayingBars extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final ColorStateList color;
    private ValueAnimator anim;
    private float phase;
    private boolean playing;

    public PlayingBars(Context context, AttributeSet attrs) {
        super(context, attrs);
        color = context.getColorStateList(R.drawable.pill_text);
    }

    /** Bouncing while playing (still, mid-height, with Reduce Motion), low and still when paused. */
    void setPlaying(boolean on) {
        playing = on;
        boolean move = on && !Look.reduceMotion(getContext()) && isAttachedToWindow();
        if (move && anim == null) {
            anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        } else if (!move && anim != null) {
            anim.cancel();
            anim = null;
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setPlaying(playing);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setColor(color.getColorForState(getDrawableState(), color.getDefaultColor()));
        float w = getWidth(), h = getHeight();
        float gap = w * 0.12f, bw = (w - 2 * gap) / 3f, r = bw / 2f;
        for (int i = 0; i < 3; i++) {
            float level;
            if (!playing) level = 0.3f;
            else if (anim == null) level = 0.6f;
            else level = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(phase * (i + 1) + i * 1.9f));
            float x = i * (bw + gap), top = h - Math.max(bw, h * level);
            bar.set(x, top, x + bw, h);
            canvas.drawRoundRect(bar, r, r, paint);
        }
    }
}
