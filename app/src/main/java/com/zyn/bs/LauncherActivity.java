package com.zyn.bs;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.elite.EliteInstaller;
import com.elite.entity.pm.InstallResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The loader's only activity: the launcher UI plus the sandbox flow.
 *
 * <p>Engine calls are the ones the vbox host app makes —
 * {@link EliteInstaller#isInstalled}, {@code installPackageAsUser},
 * {@code launchApk}, {@code stopPackage}, {@code clearPackage},
 * {@code isInstallGms}, {@code installGms} and
 * {@code getBAccountManager()} — so behaviour matches the reference module.</p>
 *
 * <p>A single target game is used ({@link #TARGET_PACKAGE}); its label and icon
 * are read from the device at runtime, never hardcoded. There is no OBB step and
 * no game picker. The launcher UI ({@link LauncherView}, {@link PulseDots}) and
 * the encrypted-asset engine ({@link AssetCrypto}) are this project's own and
 * are kept as nested classes, which is why the app ships exactly two Java files:
 * this activity and {@link BoxApplication}.</p>
 */
public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "LauncherActivity";

    /** Sandbox user slot. */
    private static final int USER_ID = 0;

    /**
     * The one game this loader targets. The title and icon shown in the UI come
     * from {@code PackageManager} for this package.
     */
    private static final String TARGET_PACKAGE = "com.netease.newspike";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LauncherView view;

    private boolean installed;
    private boolean busy;

    // ------------------------------------------------------------------ boot

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new LauncherView(this);
        view.setListener(this::onPrimaryAction);
        view.setMenuListener(this::showMenu);
        setContentView(view);

        view.setGameTitle(TARGET_PACKAGE);
        view.setPrimaryEnabled(false);
        resolveGame();
    }

    /** Reads the game's label and icon off the main thread. */
    private void resolveGame() {
        new Thread(() -> {
            boolean present;
            String label = TARGET_PACKAGE;
            Drawable icon = null;
            PackageManager pm = getPackageManager();
            try {
                ApplicationInfo info = pm.getApplicationInfo(TARGET_PACKAGE, 0);
                CharSequence raw = pm.getApplicationLabel(info);
                if (raw != null && raw.length() > 0) {
                    label = raw.toString();
                }
                try {
                    icon = pm.getApplicationIcon(info);
                } catch (Throwable t) {
                    Log.w(TAG, "icon lookup failed", t);
                }
                present = true;
            } catch (Throwable t) {
                present = false;
            }

            final boolean isInstalled = present;
            final String resolvedLabel = label;
            final Drawable resolvedIcon = icon;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                installed = isInstalled;
                view.setGameTitle(resolvedLabel);
                view.bindGameIcon(resolvedIcon);
                if (isInstalled) {
                    view.showReady();
                } else {
                    view.showNotInstalled(resolvedLabel);
                }
            });
        }, "resolve-game").start();
    }

    // ------------------------------------------------------------- main flow

    private void onPrimaryAction() {
        if (busy) {
            return;
        }
        if (!installed) {
            openPlayStore();
            return;
        }
        startGame();
    }

    /** Registers the device's own APK with the sandbox, then launches it. */
    private void startGame() {
        busy = true;
        view.showBusy(R.string.status_installing, R.string.hint_installing);

        new Thread(() -> {
            try {
                if (!EliteInstaller.get().isInstalled(TARGET_PACKAGE, USER_ID)) {
                    InstallResult result =
                            EliteInstaller.get().installPackageAsUser(TARGET_PACKAGE, USER_ID);
                    if (result == null || !result.success) {
                        String detail = result != null && result.msg != null
                                ? result.msg : "unknown error";
                        fail(getString(R.string.error_install_failed) + "\n" + detail);
                        return;
                    }
                    Log.i(TAG, "cloned " + TARGET_PACKAGE + " into the sandbox");
                }
            } catch (Throwable t) {
                Log.e(TAG, "installPackageAsUser failed", t);
                fail(getString(R.string.error_install_failed) + "\n" + describe(t));
                return;
            }
            ui.post(this::launchGame);
        }, "install-game").start();
    }

    private void launchGame() {
        view.showBusy(R.string.status_launching, R.string.hint_launching);
        try {
            EliteInstaller.get().launchApk(TARGET_PACKAGE, USER_ID);
            busy = false;
            view.showRunning();
            Toast.makeText(this, "Launching the game...", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "launchApk failed", t);
            fail(getString(R.string.error_launch_failed) + "\n" + describe(t));
        }
    }

    private void fail(String message) {
        busy = false;
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            view.showError(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message != null && !message.isEmpty() ? message : t.getClass().getSimpleName();
    }

    // ------------------------------------------------------------ menu items

    private void showMenu() {
        String[] items = {
                "Stop the game",
                "Clear login data",
                "Virtual Google services",
                "Google accounts in the sandbox",
                "Screen recording protection",
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.brand)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            stopGame();
                            break;
                        case 1:
                            clearLogin();
                            break;
                        case 2:
                            virtualGoogle();
                            break;
                        case 3:
                            showAccounts();
                            break;
                        default:
                            toggleSecure();
                            break;
                    }
                })
                .show();
    }

    private void stopGame() {
        new Thread(() -> {
            try {
                EliteInstaller.get().stopPackage(TARGET_PACKAGE, USER_ID);
                toast("Game stopped");
            } catch (Throwable t) {
                Log.e(TAG, "stopPackage failed", t);
                toast("Stop error: " + describe(t));
            }
        }, "stop").start();
    }

    private void clearLogin() {
        new Thread(() -> {
            try {
                EliteInstaller.get().stopPackage(TARGET_PACKAGE, USER_ID);
                EliteInstaller.get().clearPackage(TARGET_PACKAGE, USER_ID);
                toast("Login data cleared");
            } catch (Throwable t) {
                Log.e(TAG, "clearPackage failed", t);
                toast("Failed to clear login: " + describe(t));
            }
        }, "clear").start();
    }

    private void virtualGoogle() {
        new Thread(() -> {
            try {
                if (EliteInstaller.get().isInstallGms(USER_ID)) {
                    toast("Google Play services are active in the sandbox");
                    if (EliteInstaller.get().isInstalled("com.google.android.play.games", USER_ID)) {
                        EliteInstaller.get().launchApk("com.google.android.play.games", USER_ID);
                    } else if (EliteInstaller.get().isInstalled("com.android.vending", USER_ID)) {
                        EliteInstaller.get().launchApk("com.android.vending", USER_ID);
                    }
                    return;
                }
                toast("Cloning and initialising Google services...");
                InstallResult result = EliteInstaller.get().installGms(USER_ID);
                if (result != null && result.success) {
                    toast("Google services installed into the sandbox");
                } else {
                    toast("GMS setup finished; system services linked");
                }
            } catch (Throwable t) {
                Log.e(TAG, "GMS setup failed", t);
                toast("GMS error: " + describe(t));
            }
        }, "gms").start();
    }

    private void showAccounts() {
        new Thread(() -> {
            android.accounts.Account[] accounts = null;
            try {
                accounts = EliteInstaller.getBAccountManager().getAccountsAsUser("com.google");
            } catch (Throwable t) {
                Log.w(TAG, "account lookup failed", t);
            }

            int count = accounts != null ? accounts.length : 0;
            StringBuilder body = new StringBuilder();
            if (count > 0) {
                for (android.accounts.Account account : accounts) {
                    body.append("\u2022 ").append(account.name).append('\n');
                }
            } else {
                body.append("No Google accounts found in the sandbox.\n\n")
                        .append("Sign in through Google services or the game inside the sandbox.");
            }
            final String message = body.toString().trim();

            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Google accounts (" + count + ")")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "accounts").start();
    }

    private void toggleSecure() {
        boolean enabled =
                (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        if (enabled) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            toast("Screen recording protection disabled");
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            toast("Screen recording protection enabled");
        }
    }

    private void toast(String message) {
        ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------ life cycle

    private void openPlayStore() {
        Intent store = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + TARGET_PACKAGE));
        store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(store);
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "no market:// handler, falling back to the web listing");
        }

        Intent web = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + TARGET_PACKAGE));
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(web);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_store, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        // The game may have been installed from the Play Store while away.
        if (!installed) {
            resolveGame();
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // =====================================================================
    //  Home screen — the existing launcher UI, unchanged.
    // =====================================================================

    /**
     * The loader's home screen, built entirely in code — no layout XML.
     *
     * <p>A single vertical stack with the centre block on {@code layout_weight = 1}:
     * header on top, action block at the bottom, and the icon/title/status block
     * taking whatever space is left. The centre block is scrollable, so it cannot
     * clip on a small display.</p>
     */
    public static class LauncherView extends FrameLayout {

        /** Notified when the action button (or the menu button) is pressed. */
        public interface Listener {
            void onPrimaryAction();
        }

        private static final int COLOR_TEXT = Color.WHITE;
        private static final int COLOR_MUTED = 0xFFB0B4BB;
        private static final int COLOR_FAINT = 0xFF8A8F98;
        private static final int COLOR_READY = 0xFF4CD07D;
        private static final int COLOR_BUSY = 0xFFFFB020;
        private static final int COLOR_ERROR = 0xFFFF4D4D;

        /**
         * Opaque name of the encrypted wallpaper in {@code assets/}, assembled
         * from fragments so neither the asset listing nor the dex advertises it.
         */
        private static final String ASSET_WALLPAPER = "a7f3" + "c9" + ".bin";

        private final View scrimTopView;
        private final View scrimBottomView;
        private final FrameLayout iconHolder;
        private final ImageView iconView;
        private final TextView menuButton;
        private final TextView titleView;
        private final TextView statusView;
        private final TextView hintView;
        private final TextView buttonView;
        private final PulseDots pulse;

        private Listener listener;
        private Listener menuListener;

        public LauncherView(Context context) {
            super(context);
            setBackgroundColor(0xFF0B0B0D);

            addView(buildWallpaper(context), fullBleed());

            scrimTopView = buildScrim(true);
            addView(scrimTopView, scrimParams(Gravity.TOP));
            scrimBottomView = buildScrim(false);
            addView(scrimBottomView, scrimParams(Gravity.BOTTOM));

            // ---- root vertical stack --------------------------------------
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(context, 24), dp(context, 38), dp(context, 24), dp(context, 30));
            addView(root, fullBleed());

            // ---- header ---------------------------------------------------
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout brandColumn = new LinearLayout(context);
            brandColumn.setOrientation(LinearLayout.VERTICAL);

            TextView brand = text(context, R.string.brand, 15f, COLOR_TEXT);
            brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            brand.setLetterSpacing(0.14f);
            brandColumn.addView(brand);

            TextView brandSub = text(context, R.string.brand_subtitle, 9.5f, COLOR_FAINT);
            brandSub.setLetterSpacing(0.34f);
            LinearLayout.LayoutParams brandSubLp = wrap();
            brandSubLp.topMargin = dp(context, 5);
            brandColumn.addView(brandSub, brandSubLp);

            LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(brandColumn, brandLp);

            menuButton = buildMenuButton(context);
            header.addView(menuButton,
                    new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)));
            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ---- centre block (scrollable, takes the free space) ----------
            ScrollView centreScroll = new ScrollView(context);
            centreScroll.setFillViewport(true);
            centreScroll.setClipToPadding(false);
            centreScroll.setVerticalScrollBarEnabled(false);

            LinearLayout centre = new LinearLayout(context);
            centre.setOrientation(LinearLayout.VERTICAL);
            centre.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            centre.setPadding(0, dp(context, 16), 0, dp(context, 16));

            iconHolder = new FrameLayout(context);
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconHolder.addView(iconView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iconHolder.setBackground(circleOutline(context));
            iconHolder.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
            centre.addView(iconHolder, new LinearLayout.LayoutParams(
                    dp(context, 80), dp(context, 80)));

            titleView = text(context, 0, 26f, COLOR_TEXT);
            titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            titleView.setGravity(Gravity.CENTER);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = dp(context, 16);
            centre.addView(titleView, titleLp);

            TextView tagline = text(context, R.string.game_subtitle, 10.5f, COLOR_MUTED);
            tagline.setLetterSpacing(0.24f);
            tagline.setGravity(Gravity.CENTER);
            tagline.setSingleLine(true);
            tagline.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams taglineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            taglineLp.topMargin = dp(context, 10);
            centre.addView(tagline, taglineLp);

            statusView = text(context, R.string.status_ready, 11.5f, COLOR_READY);
            statusView.setLetterSpacing(0.22f);
            statusView.setGravity(Gravity.CENTER);
            statusView.setSingleLine(true);
            statusView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = dp(context, 18);
            centre.addView(statusView, statusLp);

            centreScroll.addView(centre, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(centreScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // ---- bottom block ---------------------------------------------
            LinearLayout bottom = new LinearLayout(context);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.CENTER_HORIZONTAL);

            hintView = text(context, R.string.tap_hint, 12.5f, COLOR_MUTED);
            hintView.setGravity(Gravity.CENTER);
            hintView.setMaxLines(4);
            hintView.setEllipsize(TextUtils.TruncateAt.END);
            bottom.addView(hintView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            buttonView = text(context, R.string.launch_game, 14.5f, COLOR_TEXT);
            buttonView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            buttonView.setLetterSpacing(0.16f);
            buttonView.setGravity(Gravity.CENTER);
            buttonView.setSingleLine(true);
            buttonView.setEllipsize(TextUtils.TruncateAt.END);
            buttonView.setClickable(true);
            buttonView.setFocusable(true);
            buttonView.setBackground(pillButton(context));
            buttonView.setElevation(dp(context, 10));
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 56));
            buttonLp.topMargin = dp(context, 16);
            bottom.addView(buttonView, buttonLp);

            pulse = new PulseDots(context);
            LinearLayout.LayoutParams pulseLp = wrap();
            pulseLp.topMargin = dp(context, 18);
            pulseLp.gravity = Gravity.CENTER_HORIZONTAL;
            bottom.addView(pulse, pulseLp);

            root.addView(bottom, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---------------------------------------------------------------- API

        public void setListener(Listener listener) {
            this.listener = listener;
            buttonView.setOnClickListener(v -> {
                if (this.listener != null) {
                    this.listener.onPrimaryAction();
                }
            });
        }

        /** Wires the header's menu button (optional). */
        public void setMenuListener(Listener listener) {
            this.menuListener = listener;
            menuButton.setOnClickListener(v -> {
                if (this.menuListener != null) {
                    this.menuListener.onPrimaryAction();
                }
            });
        }

        /** Paints the resolved game icon; a null icon leaves the ring empty. */
        public void bindGameIcon(Drawable icon) {
            int size = dp(getContext(), 80);
            Drawable circular = circularIcon(getResources(), icon, size);
            iconView.setImageDrawable(circular);
            iconView.setVisibility(circular == null ? View.INVISIBLE : View.VISIBLE);
        }

        public void setGameTitle(String title) {
            titleView.setText(title);
        }

        /** Idle/ready styling — the default "READY TO PLAY" state. */
        public void showReady() {
            statusView.setText(R.string.status_ready);
            statusView.setTextColor(COLOR_READY);
            pulse.setPulsing(true);
            hintView.setText(R.string.tap_hint);
            buttonView.setText(R.string.launch_game);
            setButtonEnabled(true);
        }

        /** Game absent from the device: offer a Play Store install. */
        public void showNotInstalled(String label) {
            statusView.setText(R.string.status_not_installed);
            statusView.setTextColor(COLOR_ERROR);
            pulse.setPulsing(false);
            hintView.setText(getContext().getString(R.string.tap_hint_install, label));
            buttonView.setText(R.string.install_game);
            setButtonEnabled(true);
        }

        public void showBusy(int statusRes, int hintRes) {
            statusView.setText(statusRes);
            statusView.setTextColor(COLOR_BUSY);
            pulse.setPulsing(true);
            hintView.setText(hintRes);
            setButtonEnabled(false);
        }

        public void showRunning() {
            statusView.setText(R.string.status_running);
            statusView.setTextColor(COLOR_READY);
            pulse.setPulsing(true);
            hintView.setText(R.string.tap_hint_running);
            buttonView.setText(R.string.launch_game);
            setButtonEnabled(true);
        }

        public void showError(String message) {
            statusView.setText(R.string.status_error);
            statusView.setTextColor(COLOR_ERROR);
            pulse.setPulsing(false);
            hintView.setText(message);
            setButtonEnabled(true);
        }

        private void setButtonEnabled(boolean enabled) {
            buttonView.setEnabled(enabled);
            buttonView.setAlpha(enabled ? 1f : 0.55f);
        }

        /** Lets the host disable the action while the package is still resolving. */
        public void setPrimaryEnabled(boolean enabled) {
            setButtonEnabled(enabled);
        }

        // ------------------------------------------------------------ builders

        private ImageView buildWallpaper(Context context) {
            ImageView view = new ImageView(context);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadWallpaper(context, view);
            return view;
        }

        /**
         * Decrypts the wallpaper off the main thread and paints it when ready.
         *
         * <p>The image ships only as AES-GCM ciphertext in {@code assets/}; if it
         * fails authentication (tampered APK) nothing is drawn and the dark
         * background colour shows through instead.</p>
         */
        private void loadWallpaper(Context context, ImageView target) {
            final Context app = context.getApplicationContext();
            final int width = getResources().getDisplayMetrics().widthPixels;
            final int height = getResources().getDisplayMetrics().heightPixels;

            Thread worker = new Thread(() -> {
                Bitmap bitmap = AssetCrypto.decodeBitmap(app, ASSET_WALLPAPER, width, height);
                if (bitmap == null) {
                    return;
                }
                post(() -> {
                    target.setImageBitmap(bitmap);
                    target.setAlpha(0f);
                    target.animate().alpha(1f).setDuration(320L).start();
                });
            }, "wallpaper-decrypt");
            worker.setDaemon(true);
            worker.start();
        }

        private View buildScrim(boolean top) {
            View view = new View(getContext());
            int[] colors = top
                    ? new int[]{0xC4000000, 0x00000000}
                    : new int[]{0x00000000, 0xE6000000};
            view.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, colors));
            return view;
        }

        private TextView buildMenuButton(Context context) {
            TextView menu = new TextView(context);
            menu.setText("\u22EE");
            menu.setTextColor(COLOR_TEXT);
            menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            menu.setGravity(Gravity.CENTER);
            menu.setSingleLine(true);

            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x33FFFFFF);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            menu.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x40FFFFFF), circle, mask));
            menu.setClickable(true);
            return menu;
        }

        private Drawable circleOutline(Context context) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0x1FFFFFFF);
            ring.setStroke(dp(context, 2), 0x59FFFFFF);
            return ring;
        }

        private Drawable pillButton(Context context) {
            GradientDrawable fill = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFFFF7A18, 0xFFE0201B});
            fill.setCornerRadius(dp(context, 28));

            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(context, 28));
            mask.setColor(Color.WHITE);

            return new RippleDrawable(ColorStateList.valueOf(0x59FFFFFF), fill, mask);
        }

        /**
         * Turns an arbitrary app icon (including adaptive icons) into a circular
         * drawable at a fixed pixel size.
         */
        private static Drawable circularIcon(android.content.res.Resources resources,
                                             Drawable source, int sizePx) {
            if (source == null || sizePx <= 0) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            source.setBounds(0, 0, sizePx, sizePx);
            source.draw(canvas);

            RoundedBitmapDrawable rounded =
                    RoundedBitmapDrawableFactory.create(resources, bitmap);
            rounded.setCircular(true);
            rounded.setAntiAlias(true);
            return rounded;
        }

        private static TextView text(Context context, int stringRes, float sp, int color) {
            TextView view = new TextView(context);
            if (stringRes != 0) {
                view.setText(stringRes);
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            view.setTextColor(color);
            view.setIncludeFontPadding(false);
            return view;
        }

        private static LinearLayout.LayoutParams wrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

        static int dp(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Scrims are proportional to the screen height so the artwork's focal
            // point stays visible on tall and short devices alike. Referenced by
            // field, not child index, so the layout can be restructured safely.
            LayoutParams top = (LayoutParams) scrimTopView.getLayoutParams();
            top.height = Math.round(h * 0.34f);
            scrimTopView.setLayoutParams(top);

            LayoutParams bottom = (LayoutParams) scrimBottomView.getLayoutParams();
            bottom.height = Math.round(h * 0.46f);
            scrimBottomView.setLayoutParams(bottom);
        }
    }

    // =====================================================================
    //  Activity indicator — the existing three-dot pulse, unchanged.
    // =====================================================================

    /**
     * Three dots that breathe in sequence — the loader's activity indicator.
     *
     * <p>Only {@code alpha} and {@code scaleX/scaleY} are animated so every frame
     * stays on the GPU compositor (no layout passes, no re-draw of the wallpaper
     * underneath). Animations are started and cancelled with the view's window
     * attachment, so nothing keeps running in the background.</p>
     */
    public static class PulseDots extends LinearLayout {

        private static final int DOT_COUNT = 3;
        private static final long PERIOD_MS = 620L;
        private static final long STAGGER_MS = 190L;

        private final java.util.List<View> dots = new java.util.ArrayList<>();
        private final java.util.List<android.animation.Animator> animators =
                new java.util.ArrayList<>();
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

        private android.animation.Animator buildAnimator(View dot, int index) {
            android.animation.PropertyValuesHolder fade =
                    android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.25f, 1f);
            android.animation.PropertyValuesHolder grow =
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.82f, 1.18f);
            android.animation.PropertyValuesHolder growY =
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.82f, 1.18f);

            android.animation.ObjectAnimator animator =
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(dot, fade, grow, growY);
            animator.setDuration(PERIOD_MS);
            animator.setStartDelay(index * STAGGER_MS);
            animator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            animator.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
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
            for (android.animation.Animator animator : animators) {
                animator.start();
            }
        }

        private void stop() {
            for (android.animation.Animator animator : animators) {
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
    }

    // =====================================================================
    //  Encrypted assets — the existing crypto engine, unchanged.
    // =====================================================================

    /**
     * Decrypts the assets shipped in {@code assets/} at runtime.
     *
     * <p>Each asset is AES-256-GCM: {@code "BSAE" | version | nonce[12] |
     * ciphertext+tag[16]}, with the asset name bound in as GCM associated data, so
     * a swapped or edited blob fails authentication instead of being drawn. The
     * key is stored split-and-masked, which is tamper-evidence plus casual
     * obfuscation — the key must ship inside the APK to work offline.</p>
     */
    public static final class AssetCrypto {

        private static final String CRYPTO_TAG = "AssetCrypto";

        private static final byte[] MAGIC = {'B', 'S', 'A', 'E'};
        private static final int VERSION = 1;
        private static final int NONCE_LEN = 12;
        private static final int TAG_BITS = 128;

        /**
         * 256-bit key, split into 8-byte chunks and XOR-masked so the raw key
         * never appears as a contiguous literal in the dex.
         */
        private static final String[] KEY_PARTS = {
                "VBZafpA0goU=",
                "sAdyoKRYnJY=",
                "i8r3zm4O96Q=",
                "iLZtwxTE7Jw=",
        };
        private static final int[] KEY_MASKS = {0x5A, 0xC3, 0x7E, 0x91};

        private AssetCrypto() {
        }

        /** Thrown when an asset is missing, corrupt, or was tampered with. */
        public static class AssetException extends IOException {
            public AssetException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        private static byte[] key() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32);
            for (int i = 0; i < KEY_PARTS.length; i++) {
                byte[] masked = Base64.decode(KEY_PARTS[i], Base64.DEFAULT);
                int mask = KEY_MASKS[i % KEY_MASKS.length];
                for (byte b : masked) {
                    out.write(b ^ mask);
                }
            }
            return out.toByteArray();
        }

        /** Reads and authenticates {@code name} from the assets folder. */
        public static byte[] read(Context context, String name) throws IOException {
            byte[] blob;
            try (InputStream in = context.getAssets().open(name)) {
                blob = readAll(in);
            } catch (IOException e) {
                throw new AssetException("asset missing: " + name, e);
            }

            if (blob.length < MAGIC.length + 1 + NONCE_LEN) {
                throw new AssetException("asset truncated: " + name, null);
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (blob[i] != MAGIC[i]) {
                    throw new AssetException("bad asset header: " + name, null);
                }
            }
            if ((blob[MAGIC.length] & 0xFF) != VERSION) {
                throw new AssetException("unsupported asset version: " + name, null);
            }

            int nonceStart = MAGIC.length + 1;
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(blob, nonceStart, nonce, 0, NONCE_LEN);

            int bodyStart = nonceStart + NONCE_LEN;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key(), "AES"),
                        new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(aad(name));
                return cipher.doFinal(blob, bodyStart, blob.length - bodyStart);
            } catch (Exception e) {
                // AEADBadTagException lands here: wrong key or modified ciphertext.
                Log.w(CRYPTO_TAG, "asset failed authentication: " + name, e);
                throw new AssetException("asset failed authentication: " + name, e);
            }
        }

        /**
         * Decrypts and downsamples an encrypted image asset to roughly
         * {@code reqWidth x reqHeight}. Returns {@code null} if the asset cannot be
         * authenticated or decoded, so callers can fall back gracefully.
         */
        public static Bitmap decodeBitmap(Context context, String name, int reqWidth, int reqHeight) {
            try {
                byte[] data = read(context, name);

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                        reqWidth, reqHeight);
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (IOException e) {
                Log.w(CRYPTO_TAG, "could not load encrypted asset: " + name, e);
                return null;
            }
        }

        private static byte[] aad(String name) {
            byte[] suffix = name.getBytes(StandardCharsets.UTF_8);
            byte[] aad = new byte[MAGIC.length + 1 + suffix.length];
            System.arraycopy(MAGIC, 0, aad, 0, MAGIC.length);
            aad[MAGIC.length] = (byte) VERSION;
            System.arraycopy(suffix, 0, aad, MAGIC.length + 1, suffix.length);
            return aad;
        }

        private static int sampleSize(int width, int height, int reqWidth, int reqHeight) {
            int sample = 1;
            if (reqWidth <= 0 || reqHeight <= 0) {
                return sample;
            }
            while ((width / (sample * 2)) >= reqWidth && (height / (sample * 2)) >= reqHeight) {
                sample *= 2;
            }
            return sample;
        }

        private static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
