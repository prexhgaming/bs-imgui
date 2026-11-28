package com.zyn.bs.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Three dots that breathe in sequence while the loader is working.
 *
 * <p>Only {@code alpha} and {@code scale} are animated, and the pulse is driven
 * by {@link Motion#loop} so it runs exactly while the view is on screen.</p>
 */
public final class PulseDots extends LinearLayout {

    private static final int DOT_COUNT = 3;
    private static final long PERIOD_MS = 620L;
    private static final long STAGGER_MS = 190L;

    private static final float IDLE_ALPHA = 0.3f;

    private final List<View> dots = new ArrayList<>();
    private final List<android.animation.Animator> animators = new ArrayList<>();
    private final List<Runnable> starters = new ArrayList<>();

    private boolean pulsing = true;

    public PulseDots(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClipChildren(false);
        setClipToPadding(false);

        int size = Theme.dp(context, 7);
        int gap = Theme.dp(context, 8);
        int color = Theme.alpha(Theme.TEXT, 0.92f);

        for (int i = 0; i < DOT_COUNT; i++) {
            View dot = new View(context);
            Drawable shape = Theme.circle(context, color);
            dot.setBackground(shape);
            dot.setAlpha(IDLE_ALPHA);

            LayoutParams lp = new LayoutParams(size, size);
            if (i > 0) {
                lp.setMarginStart(gap);
            }
            addView(dot, lp);
            dots.add(dot);

            final android.animation.Animator animator = buildAnimator(dot, i);
            animators.add(animator);
            // Each dot owns its own attach/detach life cycle, so a hidden pulse
            // never keeps an animator running.
            starters.add(() -> {
                if (pulsing && Motion.loopsEnabled(getContext())) {
                    animator.start();
                }
            });
            dot.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    if (pulsing) {
                        animator.start();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    animator.cancel();
                }
            });
        }
    }

    private android.animation.Animator buildAnimator(View dot, int index) {
        android.animation.PropertyValuesHolder fade =
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.22f, 1f);
        android.animation.PropertyValuesHolder grow =
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.22f);
        android.animation.PropertyValuesHolder growY =
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.22f);

        android.animation.ObjectAnimator animator =
                android.animation.ObjectAnimator.ofPropertyValuesHolder(dot, fade, grow, growY);
        animator.setDuration(PERIOD_MS);
        animator.setStartDelay(index * STAGGER_MS);
        animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        animator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        animator.setInterpolator(new Motion.EaseInOut());
        return animator;
    }

    /** Pauses or resumes the pulse (kept for the loader's state machine). */
    public void setPulsing(boolean pulsing) {
        this.pulsing = pulsing;
        if (pulsing) {
            for (Runnable starter : starters) {
                starter.run();
            }
        } else {
            for (android.animation.Animator animator : animators) {
                animator.cancel();
            }
            for (View dot : dots) {
                dot.setAlpha(IDLE_ALPHA);
                dot.setScaleX(1f);
                dot.setScaleY(1f);
            }
        }
    }
}
