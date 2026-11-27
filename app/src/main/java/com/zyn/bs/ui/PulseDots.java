package com.zyn.bs.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Three dots that breathe in sequence — the loader's activity indicator.
 *
 * <p>Only {@code alpha} and {@code scaleX/scaleY} are animated so every frame
 * stays on the GPU compositor (no layout passes, no re-draw of the wallpaper
 * underneath). Animations are started and cancelled with the view's window
 * attachment, so nothing keeps running in the background.</p>
 */
public class PulseDots extends LinearLayout {

    private static final int DOT_COUNT = 3;
    private static final long PERIOD_MS = 620L;
    private static final long STAGGER_MS = 190L;

    private final List<View> dots = new ArrayList<>();
    private final List<Animator> animators = new ArrayList<>();
    private boolean enabled = true;

    public PulseDots(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClipChildren(false);

        int size = LauncherView.dp(context, 7);
        int gap = LauncherView.dp(context, 7);

        for (int i = 0; i < DOT_COUNT; i++) {
            View dot = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0xFFFFFFFF);
            dot.setBackground(shape);
            dot.setAlpha(0.3f);

            LayoutParams lp = new LayoutParams(size, size);
            if (i > 0) {
                lp.setMarginStart(gap);
            }
            addView(dot, lp);
            dots.add(dot);
            animators.add(buildAnimator(dot, i));
        }
    }

    private Animator buildAnimator(View dot, int index) {
        PropertyValuesHolder fade = PropertyValuesHolder.ofFloat(View.ALPHA, 0.25f, 1f);
        PropertyValuesHolder grow = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.82f, 1.18f);
        PropertyValuesHolder growY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.82f, 1.18f);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(dot, fade, grow, growY);
        animator.setDuration(PERIOD_MS);
        animator.setStartDelay(index * STAGGER_MS);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        return animator;
    }

    /** Pauses or resumes the pulse (used while a launch is in flight). */
    public void setPulsing(boolean pulsing) {
        enabled = pulsing;
        if (pulsing) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (!enabled || animators.isEmpty() || animators.get(0).isStarted()) {
            return;
        }
        for (Animator animator : animators) {
            animator.start();
        }
    }

    private void stop() {
        for (Animator animator : animators) {
            animator.cancel();
        }
        for (View dot : dots) {
            dot.setAlpha(0.3f);
            dot.setScaleX(1f);
            dot.setScaleY(1f);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** Convenience for callers that only have a {@link Runnable}-style hook. */
    public void fadeIn() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(220L).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setListener(null);
            }
        }).start();
    }
}
