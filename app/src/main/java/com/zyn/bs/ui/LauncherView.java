package com.zyn.bs.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zyn.bs.R;
import com.zyn.bs.Wallpaper;

/**
 * The loader's home screen. Built entirely in code — no layout XML.
 *
 * <p>Structure: a full-bleed wallpaper with a very slow Ken Burns zoom, two
 * gradients scrims for legibility, then content — a header with the brand and
 * a busy hairline, a hero (icon orbit, title, tagline, status chip) that takes
 * the free space, and a bottom block with the hint, the action button and the
 * activity dots.</p>
 *
 * <p>On first attach the whole screen plays a staggered entrance, and every
 * subsequent state change ("installing", "running", "error") pops the status
 * chip and the activity dots, so state is felt rather than just read.</p>
 */
public final class LauncherView extends FrameLayout {

    /** Notified when the action button (or the menu button) is pressed. */
    public interface Listener {
        void onPrimaryAction();
    }

    private final View scrimTop;
    private final View scrimBottom;
    private final LinearLayout header;
    private final SweepBar sweep;
    private final IconOrbit orbit;
    private final TextView titleView;
    private final TextView taglineView;
    private final LinearLayout statusChip;
    private final View statusDot;
    private final TextView statusView;
    private final TextView hintView;
    private final TextView buttonView;
    private final PulseDots pulse;

    private Listener listener;
    private boolean entered;

    public LauncherView(Context context) {
        super(context);
        setBackgroundColor(Theme.BG);

        // ---- wallpaper + scrims ---------------------------------------------
        ImageView wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wallpaper, fullBleed());
        loadWallpaper(wallpaper);

        scrimTop = scrim(true);
        addView(scrimTop, scrimParams(Gravity.TOP));
        scrimBottom = scrim(false);
        addView(scrimBottom, scrimParams(Gravity.BOTTOM));

        // ---- root column -----------------------------------------------------
        LinearLayout root = Theme.column(context);
        int side = Theme.dp(context, 24);
        root.setPadding(side, Theme.dp(context, 40), side, Theme.dp(context, 30));
        addView(root, fullBleed());

        // ---- header ----------------------------------------------------------
        header = Theme.row(context);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brandColumn = Theme.column(context);
        TextView brand = Theme.label(context, R.string.brand, 15f, Theme.TEXT,
                Theme.FONT_MEDIUM);
        brand.setLetterSpacing(0.16f);
        brandColumn.addView(brand);

        TextView brandSub = Theme.label(context, R.string.brand_subtitle, 9.5f, Theme.FAINT,
                Theme.FONT);
        brandSub.setLetterSpacing(0.34f);
        LinearLayout.LayoutParams subLp = Theme.wrap();
        subLp.topMargin = Theme.dp(context, 6);
        brandColumn.addView(brandSub, subLp);

        header.addView(brandColumn, Theme.rowWeight(1f));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- busy hairline ---------------------------------------------------
        sweep = new SweepBar(context);
        LinearLayout.LayoutParams sweepLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 2));
        sweepLp.topMargin = Theme.dp(context, 18);
        root.addView(sweep, sweepLp);

        // ---- hero ------------------------------------------------------------
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout centre = Theme.column(context);
        centre.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        centre.setPadding(0, Theme.dp(context, 20), 0, Theme.dp(context, 20));

        orbit = new IconOrbit(context);
        LinearLayout.LayoutParams orbitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 140));
        centre.addView(orbit, orbitLp);

        titleView = Theme.label(context, 0, 30f, Theme.TEXT, Theme.FONT);
        titleView.setTypeface(android.graphics.Typeface.create(Theme.FONT, android.graphics.Typeface.BOLD));
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setShadowLayer(Theme.dp(context, 8), 0f, Theme.dp(context, 1), 0x66000000);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = Theme.dp(context, 22);
        centre.addView(titleView, titleLp);

        taglineView = Theme.label(context, R.string.game_subtitle, 10.5f, Theme.MUTED,
                Theme.FONT_MEDIUM);
        taglineView.setLetterSpacing(0.26f);
        taglineView.setGravity(Gravity.CENTER);
        taglineView.setSingleLine(true);
        taglineView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams taglineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taglineLp.topMargin = Theme.dp(context, 10);
        centre.addView(taglineView, taglineLp);

        // The status chip: dot + label inside a washed, rounded pill.
        statusChip = Theme.row(context);
        statusChip.setGravity(Gravity.CENTER);
        int chipPad = Theme.dp(context, 12);
        statusChip.setPadding(chipPad, Theme.dp(context, 7), chipPad, Theme.dp(context, 7));

        statusDot = new View(context);
        statusChip.addView(statusDot, new LinearLayout.LayoutParams(
                Theme.dp(context, 6), Theme.dp(context, 6)));

        statusView = Theme.label(context, R.string.status_ready, 11f, Theme.READY,
                Theme.FONT_MEDIUM);
        statusView.setLetterSpacing(0.2f);
        statusView.setSingleLine(true);
        LinearLayout.LayoutParams statusLp = Theme.wrap();
        statusLp.setMarginStart(Theme.dp(context, 8));
        statusChip.addView(statusView, statusLp);

        LinearLayout.LayoutParams chipLp = Theme.wrap();
        chipLp.topMargin = Theme.dp(context, 20);
        chipLp.gravity = Gravity.CENTER_HORIZONTAL;
        centre.addView(statusChip, chipLp);

        scroll.addView(centre, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- bottom block ----------------------------------------------------
        LinearLayout bottom = Theme.column(context);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        hintView = Theme.label(context, R.string.tap_hint, 12.5f, Theme.MUTED, Theme.FONT);
        hintView.setGravity(Gravity.CENTER);
        hintView.setMaxLines(4);
        hintView.setEllipsize(TextUtils.TruncateAt.END);
        hintView.setLineSpacing(Theme.dp(context, 3), 1f);
        bottom.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buttonView = Theme.label(context, R.string.launch_game, 15f, Theme.TEXT,
                Theme.FONT_MEDIUM);
        buttonView.setLetterSpacing(0.16f);
        buttonView.setGravity(Gravity.CENTER);
        buttonView.setSingleLine(true);
        buttonView.setEllipsize(TextUtils.TruncateAt.END);
        buttonView.setClickable(true);
        buttonView.setFocusable(true);
        buttonView.setBackground(accentButton(context));
        buttonView.setElevation(Theme.dp(context, 12));
        Motion.pressScale(buttonView, 0.97f);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 58));
        buttonLp.topMargin = Theme.dp(context, 18);
        bottom.addView(buttonView, buttonLp);

        pulse = new PulseDots(context);
        LinearLayout.LayoutParams pulseLp = Theme.wrap();
        pulseLp.topMargin = Theme.dp(context, 20);
        pulseLp.gravity = Gravity.CENTER_HORIZONTAL;
        bottom.addView(pulse, pulseLp);

        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------- API

    public void setListener(Listener listener) {
        this.listener = listener;
        buttonView.setOnClickListener(v -> {
            if (this.listener != null) {
                this.listener.onPrimaryAction();
            }
        });
    }

    /** Paints the resolved game icon; a null icon leaves the plate empty. */
    public void bindGameIcon(android.graphics.drawable.Drawable icon) {
        orbit.setIcon(icon);
    }

    public void setGameTitle(String title) {
        titleView.setText(title);
    }

    /** Idle/ready styling — the default "READY TO PLAY" state. */
    public void showReady() {
        chip(R.string.status_ready, Theme.READY, Theme.READY_WASH);
        pulse.setPulsing(true);
        hintView.setText(R.string.tap_hint);
        buttonView.setText(R.string.launch_game);
        sweep.setActive(false);
        setButtonEnabled(true);
    }

    /** Game absent from the device: offer a Play Store install. */
    public void showNotInstalled(String label) {
        chip(R.string.status_not_installed, Theme.ERROR, Theme.ERROR_WASH);
        pulse.setPulsing(false);
        hintView.setText(getContext().getString(R.string.tap_hint_install, label));
        buttonView.setText(R.string.install_game);
        sweep.setActive(false);
        setButtonEnabled(true);
    }

    public void showBusy(int statusRes, int hintRes) {
        chip(statusRes, Theme.BUSY, Theme.BUSY_WASH);
        pulse.setPulsing(true);
        hintView.setText(hintRes);
        sweep.setActive(true);
        orbit.flash();
        setButtonEnabled(false);
    }

    public void showRunning() {
        chip(R.string.status_running, Theme.READY, Theme.READY_WASH);
        pulse.setPulsing(true);
        hintView.setText(R.string.tap_hint_running);
        buttonView.setText(R.string.launch_game);
        sweep.setActive(false);
        setButtonEnabled(true);
    }

    public void showError(String message) {
        chip(R.string.status_error, Theme.ERROR, Theme.ERROR_WASH);
        pulse.setPulsing(false);
        hintView.setText(message);
        sweep.setActive(false);
        setButtonEnabled(true);
    }

    /** Lets the host disable the action while the package is still resolving. */
    public void setPrimaryEnabled(boolean enabled) {
        setButtonEnabled(enabled);
    }

    /** Applies a status colour to the chip, its dot and its wash. */
    private void chip(int statusRes, int color, int wash) {
        statusView.setText(statusRes);
        statusView.setTextColor(color);
        statusDot.setBackground(Theme.circle(getContext(), color));
        statusChip.setBackground(Theme.pill(getContext(), wash));
        Motion.pop(statusChip);
    }

    private void setButtonEnabled(boolean enabled) {
        buttonView.setEnabled(enabled);
        buttonView.setAlpha(enabled ? 1f : 0.5f);
        // A disabled button should not keep springing under the finger.
        buttonView.setClickable(enabled);
    }

    // ------------------------------------------------------------- entrance

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (entered) {
            return;
        }
        entered = true;
        Motion.enter(header, 40L, -14f, 0.98f);
        Motion.enter(orbit, 140L, 26f, 0.86f);
        Motion.enter(titleView, 240L, 18f, 1f);
        Motion.enter(taglineView, 300L, 14f, 1f);
        Motion.enter(statusChip, 360L, 12f, 1f);
        Motion.enter(hintView, 420L, 12f, 1f);
        Motion.enter(buttonView, 480L, 20f, 0.96f);
        Motion.enter(pulse, 560L, 10f, 1f);
    }

    // -------------------------------------------------------------- helpers

    /**
     * Decodes the wallpaper off the main thread and paints it when ready.
     *
     * <p>The artwork is compiled in as raw bytes ({@link Wallpaper}), so there is
     * no asset to open and no decryption step that can fail: if the decode
     * somehow returns nothing, the dark background colour simply shows
     * through.</p>
     */
    private void loadWallpaper(ImageView target) {
        final int width = getResources().getDisplayMetrics().widthPixels;
        final int height = getResources().getDisplayMetrics().heightPixels;

        Thread worker = new Thread(() -> {
            Bitmap bitmap = Wallpaper.decode(width, height);
            if (bitmap == null) {
                return;
            }
            post(() -> {
                target.setImageBitmap(bitmap);
                Motion.fade(target, 0L, Motion.SLOW);
                Motion.kenBurns(target, 22000L, 1.07f);
            });
        }, "wallpaper-decode");
        worker.setDaemon(true);
        worker.start();
    }

    private View scrim(boolean top) {
        View view = new View(getContext());
        int[] colors = top
                ? new int[]{0xC4000000, 0x00000000}
                : new int[]{0x00000000, 0xE6000000};
        view.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors));
        return view;
    }

    private android.graphics.drawable.Drawable accentButton(Context context) {
        return Theme.accentButton(context, 29f);
    }

    private static LayoutParams fullBleed() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LayoutParams scrimParams(int gravity) {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        lp.gravity = gravity;
        return lp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Scrims are proportional to the screen height so the artwork's focal
        // point stays visible on tall and short devices alike. Referenced by
        // field, not child index, so the layout can be restructured safely.
        LayoutParams top = (LayoutParams) scrimTop.getLayoutParams();
        top.height = Math.round(h * 0.34f);
        scrimTop.setLayoutParams(top);

        LayoutParams bottom = (LayoutParams) scrimBottom.getLayoutParams();
        bottom.height = Math.round(h * 0.46f);
        scrimBottom.setLayoutParams(bottom);
    }
}
