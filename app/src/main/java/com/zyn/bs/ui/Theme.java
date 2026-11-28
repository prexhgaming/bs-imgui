package com.zyn.bs.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

/**
 * Design tokens and small drawable/text factories shared by the launcher UI.
 *
 * <p>Everything visual in {@code com.zyn.bs.ui} pulls its colours, type and
 * spacing from here, so the whole look can be retuned in one place. Values are
 * deliberately dark-theme first: the wallpaper is dark and the panel sits on top
 * of it as frosted glass.</p>
 */
public final class Theme {

    // ------------------------------------------------------------- palette

    public static final int BG = 0xFF0B0B0D;
    public static final int TEXT = Color.WHITE;
    public static final int MUTED = 0xFFB0B4BB;
    public static final int FAINT = 0xFF8A8F98;

    /** Brand gradient used by the primary action. */
    public static final int ACCENT_START = 0xFFFF8A2B;
    public static final int ACCENT_END = 0xFFD81E17;

    public static final int READY = 0xFF4CD07D;
    public static final int BUSY = 0xFFFFB020;
    public static final int ERROR = 0xFFFF4D4D;

    /** Frosted-glass panel fill and its hairline. */
    public static final int GLASS = 0x14FFFFFF;
    public static final int GLASS_STRONG = 0x1FFFFFFF;
    public static final int HAIRLINE = 0x24FFFFFF;

    /** Status chip fills: the state colour at low alpha over the glass. */
    public static final int READY_WASH = 0x244CD07D;
    public static final int BUSY_WASH = 0x24FFB020;
    public static final int ERROR_WASH = 0x24FF4D4D;

    public static final String FONT = "sans-serif";
    public static final String FONT_MEDIUM = "sans-serif-medium";
    public static final String FONT_LIGHT = "sans-serif-light";

    private Theme() {
        // Static surface only.
    }

    // --------------------------------------------------------------- units

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Same as {@link #dp}, for call sites that only have {@link Resources}. */
    public static int dp(Resources resources, float value) {
        return Math.round(value * resources.getDisplayMetrics().density);
    }

    /** A colour with its alpha scaled to {@code factor} (0..1). */
    public static int alpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * clamp(factor));
        return (Color.red(color) << 16) | (Color.green(color) << 8) | Color.blue(color)
                | (a << 24);
    }

    /** Blends {@code color} toward {@code target} by {@code ratio} (0..1). */
    public static int mix(int color, int target, float ratio) {
        float r = clamp(ratio);
        int red = Math.round(Color.red(color) + (Color.red(target) - Color.red(color)) * r);
        int green = Math.round(Color.green(color) + (Color.green(target) - Color.green(color)) * r);
        int blue = Math.round(Color.blue(color) + (Color.blue(target) - Color.blue(color)) * r);
        int alpha = Math.round(Color.alpha(color) + (Color.alpha(target) - Color.alpha(color)) * r);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    // ------------------------------------------------------------ drawables

    /** Rounded frosted panel, optionally outlined with a hairline. */
    public static Drawable glass(Context context, float radiusDp, boolean outlined) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, radiusDp));
        shape.setColors(new int[]{GLASS_STRONG, GLASS});
        if (outlined) {
            shape.setStroke(Math.max(1, dp(context, 1f)), HAIRLINE);
        }
        return shape;
    }

    /** Solid rounded rectangle. */
    public static Drawable rounded(Context context, float radiusDp, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, radiusDp));
        shape.setColor(color);
        return shape;
    }

    /** Fully rounded pill. */
    public static Drawable pill(Context context, int color) {
        return rounded(context, 999f, color);
    }

    public static Drawable circle(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    /** Outlined circle, used for the icon ring and the glass icon buttons. */
    public static Drawable circleOutline(Context context, int fill, int strokeColor,
                                         float strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        shape.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        return shape;
    }

    /** The brand gradient, left to right. */
    public static Drawable accent(Context context, float radiusDp) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT_START, ACCENT_END});
        shape.setCornerRadius(dp(context, radiusDp));
        return shape;
    }

    /** The brand gradient, top-left to bottom-right (for the hero ring). */
    public static Drawable accentDiagonal(Context context, int start, int end) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        shape.setShape(GradientDrawable.RECTANGLE);
        return shape;
    }

    /**
     * Wraps {@code content} in a white ripple. A ripple needs a mask that is the
     * <em>same shape</em> as the content, otherwise it leaks outside rounded
     * corners, so the mask is duplicated here rather than clipped.
     */
    public static Drawable rippled(Drawable content, Drawable mask, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static Drawable ripplePill(Context context, int fill, float radiusDp,
                                      int rippleColor) {
        return rippled(rounded(context, radiusDp, fill), rounded(context, radiusDp, TEXT),
                rippleColor);
    }

    public static Drawable rippleCircle(Context context, int fill, int rippleColor) {
        return rippled(circle(context, fill), circle(context, TEXT), rippleColor);
    }

    /** Gradient action button with a matching ripple mask. */
    public static Drawable accentButton(Context context, float radiusDp) {
        return rippled(accent(context, radiusDp), rounded(context, radiusDp, TEXT),
                Theme.alpha(TEXT, 0.34f));
    }

    // ----------------------------------------------------------------- text

    /** A {@link TextView} with the app's font padding and sizing conventions. */
    public static TextView label(Context context, int stringRes, float sizeSp, int color,
                                 String typeface) {
        TextView view = new TextView(context);
        if (stringRes != 0) {
            view.setText(stringRes);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (typeface != null) {
            view.setTypeface(Typeface.create(typeface, Typeface.NORMAL));
        }
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams rowWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    // ---------------------------------------------------------------- icons

    /**
     * Turns an arbitrary launcher icon (adaptive icons included) into a circular
     * bitmap drawable of exactly {@code sizePx}. Returns {@code null} when there
     * is nothing to draw, so the caller can show the empty ring instead.
     */
    public static Drawable circularIcon(Resources resources, Drawable source, int sizePx) {
        if (source == null || sizePx <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        source.setBounds(0, 0, sizePx, sizePx);
        source.draw(canvas);

        RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(resources, bitmap);
        rounded.setCircular(true);
        rounded.setAntiAlias(true);
        return rounded;
    }
}
