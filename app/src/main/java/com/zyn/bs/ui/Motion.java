package com.zyn.bs.ui;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

/**
 * The launcher's motion vocabulary.
 *
 * <p>Two rules shape everything here:</p>
 * <ul>
 *   <li>Only {@code alpha}, {@code scale}, {@code translation} and {@code rotation}
 *       are animated, so every frame stays on the GPU compositor and the
 *       wallpaper underneath is never re-drawn.</li>
 *   <li>Decorative loops are started on window attach and cancelled on detach, so
 *       nothing keeps running (and nothing leaks) while the app is in the
 *       background.</li>
 * </ul>
 *
 * <p>All timings are scaled by the system animator duration setting, so
 * "animations off" in developer options disables the decoration too.</p>
 */
public final class Motion {

    /** Baseline durations; every call site scales these through {@link #dur}. */
    public static final long FAST = 150L;
    public static final long NORMAL = 280L;
    public static final long SLOW = 420L;

    private Motion() {
        // Static surface only.
    }

    // ------------------------------------------------------------ settings

    /** The system animator duration scale, or 1 when it cannot be read. */
    public static float scale(Context context) {
        try {
            float value = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return value <= 0f ? 0f : value;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    /** A duration scaled by the system setting (never zero, so animations end). */
    public static long dur(Context context, long baseMs) {
        float factor = scale(context);
        if (factor <= 0f) {
            return 1L;
        }
        return Math.max(1L, Math.round(baseMs * factor));
    }

    /** Whether the decorative loops should run at all. */
    public static boolean loopsEnabled(Context context) {
        return scale(context) > 0f;
    }

    // --------------------------------------------------------------- enter

    /**
     * Staggered entrance: the view fades in while rising into place and easing up
     * from a slightly smaller scale. Called on the whole screen in sequence, this
     * is what makes the launcher feel assembled rather than switched on.
     */
    public static void enter(View view, long delayMs, float dyDp, float fromScale) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        if (scale(context) <= 0f) {
            view.setAlpha(1f);
            return;
        }

        float dy = Theme.dp(context, dyDp);
        view.setAlpha(0f);
        view.setTranslationY(dy);
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);

        view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(dur(context, SLOW))
                .setInterpolator(new EaseOutCubic())
                .start();
    }

    /** A short "something changed" pop: the view settles from 0.94 at 40% alpha. */
    public static void pop(View view) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        if (scale(context) <= 0f) {
            return;
        }
        view.animate().cancel();
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.setAlpha(0.4f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(dur(context, NORMAL))
                .setInterpolator(new EaseOutCubic())
                .start();
    }

    /** Cross-fade used for the wallpaper and the status chip. */
    public static void fade(View view, long delayMs, long baseMs) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        view.setAlpha(0f);
        view.animate()
                .alpha(1f)
                .setStartDelay(delayMs)
                .setDuration(dur(context, baseMs))
                .start();
    }

    // --------------------------------------------------------------- press

    /**
     * Adds tactile press feedback: the view shrinks slightly while held and
     * springs back on release. Safe to call together with a ripple; the listener
     * only observes and never consumes the event.
     */
    public static void pressScale(final View view, final float pressed) {
        if (view == null) {
            return;
        }
        final Context context = view.getContext();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateScaleTo(v, pressed, context, FAST);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateScaleTo(v, 1f, context, NORMAL);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static void animateScaleTo(View view, float target, Context context, long baseMs) {
        view.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(dur(context, baseMs))
                .setInterpolator(new EaseOutCubic())
                .start();
    }

    // ---------------------------------------------------------------- loops

    /**
     * Runs {@code animator} while {@code host} is attached to a window.
     *
     * <p>This is the single place that owns loop life cycles, which is why no
     * caller needs its own {@code onAttachedToWindow}/{@code onDetachedFromWindow}
     * bookkeeping.</p>
     */
    public static void loop(final View host, final Animator animator) {
        if (host == null || animator == null) {
            return;
        }
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (loopsEnabled(v.getContext())) {
                    animator.start();
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                animator.cancel();
            }
        });
        if (host.isAttachedToWindow() && loopsEnabled(host.getContext())) {
            animator.start();
        }
    }

    /** Slow breathing: scale and alpha together, forever, reversed. */
    public static void breathe(View view, long periodMs, float minScale, float maxScale,
                               float minAlpha, float maxAlpha) {
        if (view == null || !loopsEnabled(view.getContext())) {
            return;
        }
        PropertyValuesHolder grow = PropertyValuesHolder.ofFloat(View.SCALE_X, minScale, maxScale);
        PropertyValuesHolder growY = PropertyValuesHolder.ofFloat(View.SCALE_Y, minScale, maxScale);
        PropertyValuesHolder fade = PropertyValuesHolder.ofFloat(View.ALPHA, minAlpha, maxAlpha);

        ObjectAnimator animator =
                ObjectAnimator.ofPropertyValuesHolder(view, grow, growY, fade);
        animator.setDuration(periodMs);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new EaseInOut());
        loop(view, animator);
    }

    /** Constant rotation — the icon ring and the busy sweep runner. */
    public static void rotateForever(View view, long periodMs) {
        if (view == null || !loopsEnabled(view.getContext())) {
            return;
        }
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f);
        animator.setDuration(periodMs);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        loop(view, animator);
    }

    /**
     * Ken Burns on the wallpaper: a very slow zoom that makes the still artwork
     * feel alive. Only scale is touched, so it is composited.
     */
    public static void kenBurns(View view, long periodMs, float maxScale) {
        if (view == null || !loopsEnabled(view.getContext())) {
            return;
        }
        view.setScaleX(1f);
        view.setScaleY(1f);
        PropertyValuesHolder grow = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, maxScale);
        PropertyValuesHolder growY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, maxScale);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, grow, growY);
        animator.setDuration(periodMs);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        loop(view, animator);
    }

    /**
     * Left-to-right sweep of {@code runner} across {@code track}: the
     * indeterminate progress loop. Waits for the first layout so it can travel the
     * real width rather than a guess.
     */
    public static void sweep(final View runner, final View track, final long periodMs) {
        if (runner == null || track == null || !loopsEnabled(runner.getContext())) {
            return;
        }
        track.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private boolean started;

            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                if (started || width <= 0) {
                    return;
                }
                int runnerWidth = runner.getWidth();
                if (runnerWidth <= 0) {
                    return;
                }
                started = true;

                ObjectAnimator animator = ObjectAnimator.ofFloat(runner, View.TRANSLATION_X,
                        -runnerWidth, width);
                animator.setDuration(periodMs);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new android.view.animation.LinearInterpolator());
                loop(runner, animator);
            }
        });
    }

    // -------------------------------------------------------- interpolators

    /** Cubic ease-out — the default for anything entering the screen. */
    public static final class EaseOutCubic implements android.animation.TimeInterpolator {
        @Override
        public float getInterpolation(float input) {
            float f = input - 1f;
            return f * f * f + 1f;
        }
    }

    /** Smooth in-out, used by the breathing loops. */
    public static final class EaseInOut implements android.animation.TimeInterpolator {
        @Override
        public float getInterpolation(float input) {
            return input < 0.5f
                    ? 2f * input * input
                    : 1f - (float) Math.pow(-2f * input + 2f, 2) / 2f;
        }
    }
}
