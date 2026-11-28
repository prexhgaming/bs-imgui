package com.zyn.bs.ui;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * The indeterminate progress hairline that runs under the header while the
 * loader is installing or launching.
 *
 * <p>A gradient runner sweeps across a dim track. It waits for the first layout
 * before starting, so the travel distance is the real width and not a guess, and
 * {@code setActive(false)} both stops and rewinds it.</p>
 */
public final class SweepBar extends FrameLayout {

    private static final float TRACK_HEIGHT_DP = 2f;
    private static final float RUNNER_WIDTH_RATIO = 0.34f;
    private static final long PERIOD_MS = 1150L;

    private final View runner;
    private final View track;

    private Animator animator;
    private boolean laidOut;
    private boolean active;

    public SweepBar(Context context) {
        super(context);
        setClipChildren(true);

        track = new View(context);
        track.setBackground(Theme.pill(context, Theme.alpha(Theme.TEXT, 0.06f)));
        addView(track, new LayoutParams(
                LayoutParams.MATCH_PARENT, Theme.dp(context, TRACK_HEIGHT_DP)));

        runner = new View(context);
        runner.setBackground(Theme.accent(context, 99f));
        LayoutParams runnerLp = new LayoutParams(0, LayoutParams.MATCH_PARENT);
        runnerLp.gravity = Gravity.CENTER_VERTICAL;
        addView(runner, runnerLp);

        setAlpha(0f);
        setVisibility(INVISIBLE);

        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            private int builtTrackWidth = -1;
            private int builtRunnerWidth = -1;

            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                if (width <= 0) {
                    return;
                }
                int runnerWidth = Math.max(Theme.dp(getContext(), 24f),
                        Math.round(width * RUNNER_WIDTH_RATIO));
                // Setting the runner width re-triggers layout, so bail out once
                // the geometry already matches what the animator was built for.
                if (width == builtTrackWidth && runnerWidth == builtRunnerWidth) {
                    return;
                }
                builtTrackWidth = width;
                builtRunnerWidth = runnerWidth;

                LayoutParams lp = (LayoutParams) runner.getLayoutParams();
                if (lp.width != runnerWidth) {
                    lp.width = runnerWidth;
                    runner.setLayoutParams(lp);
                }
                buildAnimator(runnerWidth, width);
                laidOut = true;
                if (active) {
                    start();
                }
            }
        });
    }

    private void buildAnimator(int runnerWidth, int trackWidth) {
        if (animator != null) {
            animator.cancel();
        }
        animator = ObjectAnimator.ofFloat(runner, View.TRANSLATION_X,
                -runnerWidth, trackWidth);
        ((ObjectAnimator) animator).setDuration(PERIOD_MS);
        animator.setInterpolator(new LinearInterpolator());
        ((ObjectAnimator) animator).setRepeatCount(ValueAnimator.INFINITE);
    }

    /** Shows the sweeping hairline while {@code active}, hides it otherwise. */
    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            setVisibility(VISIBLE);
            setAlpha(1f);
            start();
        } else {
            stop();
            setAlpha(0f);
            setVisibility(INVISIBLE);
        }
    }

    private void start() {
        if (!laidOut || animator == null || animator.isStarted()
                || !Motion.loopsEnabled(getContext())) {
            return;
        }
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
        }
        runner.setTranslationX(0f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
