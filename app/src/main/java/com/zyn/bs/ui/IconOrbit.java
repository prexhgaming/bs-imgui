package com.zyn.bs.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The hero: the game icon on a frosted plate, wrapped in a slowly rotating
 * gradient arc and a breathing halo.
 *
 * <p>The arc is deliberately asymmetric (a 300° sweep with a gap), so the
 * rotation reads as movement instead of a static circle. Only {@code rotation},
 * {@code scale} and {@code alpha} are animated.</p>
 */
public final class IconOrbit extends FrameLayout {

    /** Diameter of the hero plate, in dp. */
    public static final float SIZE_DP = 104f;

    private static final int HALO_DP = 134;

    private final View halo;
    private final View ring;
    private final FrameLayout plate;
    private final ImageView icon;
    private final int iconSizePx;

    public IconOrbit(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        int size = Theme.dp(context, SIZE_DP);
        int haloSize = Theme.dp(context, HALO_DP);

        // ---- halo: a soft accent wash that breathes behind everything --------
        halo = new View(context);
        halo.setBackground(Theme.circle(context, Theme.alpha(Theme.ACCENT_START, 0.13f)));
        LayoutParams haloLp = new LayoutParams(haloSize, haloSize);
        haloLp.gravity = Gravity.CENTER;
        addView(halo, haloLp);

        // ---- plate: the disc the icon sits on --------------------------------
        plate = new FrameLayout(context);
        plate.setBackground(Theme.circleOutline(context, 0x33000000, Theme.HAIRLINE, 1f));
        int inset = Theme.dp(context, 4);
        plate.setPadding(inset, inset, inset, inset);
        LayoutParams plateLp = new LayoutParams(size, size);
        plateLp.gravity = Gravity.CENTER;
        addView(plate, plateLp);

        iconSizePx = size - (inset * 2);
        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setVisibility(INVISIBLE);
        plate.addView(icon, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- ring: the rotating gradient arc ---------------------------------
        ring = new View(context);
        ring.setBackground(new ArcRing());
        LayoutParams ringLp = new LayoutParams(size, size);
        ringLp.gravity = Gravity.CENTER;
        addView(ring, ringLp);

        // ---- motion ----------------------------------------------------------
        Motion.rotateForever(ring, 9000L);
        Motion.breathe(halo, 3200L, 0.94f, 1.06f, 0.75f, 1f);
    }

    /**
     * Paints the resolved game icon. A {@code null} icon leaves the plate empty
     * (the arc still spins), which is the state before the package resolves.
     */
    public void setIcon(Drawable source) {
        Drawable circular = Theme.circularIcon(getResources(), source, iconSizePx);
        icon.setImageDrawable(circular);
        if (circular == null) {
            icon.setVisibility(INVISIBLE);
            return;
        }
        icon.setVisibility(VISIBLE);
        Motion.pop(icon);
    }

    /** One-off accent flash, used when the icon arrives or a launch starts. */
    public void flash() {
        Motion.pop(plate);
        halo.animate().cancel();
        halo.setAlpha(0.45f);
        halo.animate().alpha(1f)
                .setDuration(Motion.dur(getContext(), Motion.SLOW))
                .start();
    }

    /**
     * A 300° sweep of the brand gradient: the rotating arc. Drawn in a
     * {@link Drawable} so the view itself only has to rotate.
     */
    private static final class ArcRing extends Drawable {

        private static final float SWEEP_DEGREES = 300f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        ArcRing() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            oval.set(bounds);
            float inset = Math.max(2f, bounds.width() * 0.02f);
            oval.inset(inset, inset);
            paint.setStrokeWidth(Math.max(2f, bounds.width() * 0.035f));

            // Fading the gradient back to transparent closes the arc's gap.
            int start = Theme.ACCENT_START;
            int end = Theme.ACCENT_END;
            paint.setShader(new SweepGradient(oval.centerX(), oval.centerY(),
                    new int[]{start, end, Theme.alpha(start, 0f)},
                    new float[]{0f, 0.55f, 1f}));
        }

        @Override
        public void draw(Canvas canvas) {
            if (oval.isEmpty()) {
                return;
            }
            canvas.drawArc(oval, -90f, SWEEP_DEGREES, false, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
