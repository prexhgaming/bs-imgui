package com.zyn.bs;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.zyn.bs.ui.LauncherView;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * The loader's only activity: the sandbox flow, with the UI delegated to
 * {@link LauncherView} in {@code com.zyn.bs.ui}.
 *
 * <p><b>Startup does the setup, not the button.</b> As soon as the screen opens
 * one background pass runs:</p>
 * <ol>
 *   <li>look for the game on the device;</li>
 *   <li>if present, clone it into the sandbox ({@code installPackageAsUser});
 *       if it is already cloned this is a no-op;</li>
 *   <li>install Google services into the sandbox when supported and missing
 *       ({@code isSupportGms}/{@code isInstallGms}/{@code installGms});</li>
 *   <li>if the game is absent from the device, stop and offer the Play Store
 *       listing built from the package name.</li>
 * </ol>
 *
 * <p>The primary button is disabled for the whole pass and only becomes live in
 * a terminal state, so it can never be tapped into a half-prepared sandbox —
 * which is what makes the eventual launch immediate.</p>
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

    /** What the primary button currently means. */
    private enum State {
        /** Setup pass running: the button is disabled. */
        SETUP,
        /** Sandbox prepared, the game can be launched. */
        READY,
        /** Game missing from the device: the button opens the Play Store. */
        NEEDS_STORE,
        /** Setup failed: the button retries the setup pass. */
        ERROR
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LauncherView view;

    private volatile State state = State.SETUP;
    private volatile boolean setupRunning;

    // ------------------------------------------------------------------ boot

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new LauncherView(this);
        view.setListener(this::onPrimaryAction);
        setContentView(view);

        view.setGameTitle(TARGET_PACKAGE);
        view.setPrimaryEnabled(false);

        requestCrashLogPermission();
        runSetup();
    }

    /**
     * Asks for the storage grant that lets {@link CrashHandler} write into the
     * public Download/Documents folders. The handler itself cannot ask, so
     * without this it silently falls back to the app-specific directory. A
     * denial is harmless and never blocks the launcher.
     */
    private void requestCrashLogPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            }
        } catch (Throwable t) {
            Log.w(TAG, "storage permission request failed", t);
        }
    }

    // ------------------------------------------------------------ setup pass

    /**
     * One automatic pass that leaves the sandbox ready to launch, or tells the
     * user to fetch the game from the Play Store. Runs off the main thread; all
     * UI updates are posted back.
     */
    private void runSetup() {
        if (setupRunning) {
            return;
        }
        setupRunning = true;
        state = State.SETUP;
        ui.post(() -> {
            if (alive()) {
                view.showChecking();
            }
        });

        new Thread(() -> {
            // 1. Is the game on this device at all?
            final String label = resolveLabel();
            final Drawable icon = resolveIcon();
            final boolean onDevice = isOnDevice();

            ui.post(() -> {
                if (!alive()) {
                    return;
                }
                view.setGameTitle(label);
                view.bindGameIcon(icon);
            });

            if (!onDevice) {
                // Nothing to clone: the only useful action is the store.
                Log.i(TAG, TARGET_PACKAGE + " is not on the device; offering the store");
                setupRunning = false;
                state = State.NEEDS_STORE;
                ui.post(() -> {
                    if (alive()) {
                        view.showNeedsStore(label);
                    }
                });
                return;
            }

            // 2. Clone it into the sandbox (no-op when already cloned).
            ui.post(() -> {
                if (alive()) {
                    view.showBusy(R.string.status_installing, R.string.hint_installing);
                }
            });
            if (!ensureCloned()) {
                fail(getString(R.string.error_install_failed));
                return;
            }

            // 3. Google services, when this core supports them.
            ensureGms();

            // 4. Ready: the button now launches straight away.
            setupRunning = false;
            state = State.READY;
            ui.post(() -> {
                if (alive()) {
                    view.showReady();
                }
            });
        }, "setup").start();
    }

    /** True when the package is installed on the device (not in the sandbox). */
    private boolean isOnDevice() {
        try {
            getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String resolveLabel() {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(TARGET_PACKAGE, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "label lookup failed", t);
        }
        return TARGET_PACKAGE;
    }

    private Drawable resolveIcon() {
        try {
            return getPackageManager().getApplicationIcon(TARGET_PACKAGE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Registers the device's own APK with the sandbox.
     *
     * @return true when the game ends up cloned (already or now)
     */
    private boolean ensureCloned() {
        try {
            if (BlackBoxCore.get().isInstalled(TARGET_PACKAGE, USER_ID)) {
                Log.i(TAG, TARGET_PACKAGE + " already cloned");
                return true;
            }
            InstallResult result =
                    BlackBoxCore.get().installPackageAsUser(TARGET_PACKAGE, USER_ID);
            if (result == null || !result.success) {
                Log.e(TAG, "installPackageAsUser failed: "
                        + (result != null ? result.msg : "null result"));
                return false;
            }
            Log.i(TAG, "cloned " + TARGET_PACKAGE + " into the sandbox");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "installPackageAsUser threw", t);
            return false;
        }
    }

    /**
     * Installs Google services into the sandbox when the core supports it and
     * they are not there yet. Never fatal: a game without GMS may still run,
     * and a failure here must not block the launch.
     */
    private void ensureGms() {
        try {
            if (!BlackBoxCore.get().isSupportGms()) {
                Log.i(TAG, "core does not support GMS");
                return;
            }
            if (BlackBoxCore.get().isInstallGms(USER_ID)) {
                Log.i(TAG, "GMS already installed in the sandbox");
                return;
            }
            ui.post(() -> {
                if (alive()) {
                    view.showGmsBusy();
                }
            });
            InstallResult result = BlackBoxCore.get().installGms(USER_ID);
            if (result != null && result.success) {
                Log.i(TAG, "GMS installed into the sandbox");
            } else {
                Log.w(TAG, "installGms failed: "
                        + (result != null ? result.msg : "null result"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "GMS setup failed", t);
        }
    }

    // ------------------------------------------------------------- main flow

    private void onPrimaryAction() {
        switch (state) {
            case READY:
                startGame();
                break;
            case NEEDS_STORE:
                openPlayStore();
                break;
            case ERROR:
                // Let the user retry the whole setup pass.
                runSetup();
                break;
            case SETUP:
            default:
                // Deliberately inert: setup owns the screen right now.
                break;
        }
    }

    /**
     * Launches the already-cloned game. The clone happened during setup, so this
     * is only the launch call — no install work on the tap.
     */
    private void startGame() {
        state = State.SETUP;
        view.showBusy(R.string.status_launching, R.string.hint_launching);

        try {
            BlackBoxCore.get().launchApk(TARGET_PACKAGE, USER_ID);
            state = State.READY;
            view.showRunning();
            Toast.makeText(this, "Launching the game...", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "launchApk failed", t);
            fail(getString(R.string.error_launch_failed) + "\n" + describe(t));
        }
    }

    /** Leaves the screen in the error state, with the button as "retry". */
    private void fail(String message) {
        setupRunning = false;
        state = State.ERROR;
        ui.post(() -> {
            if (!alive()) {
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

    /** Store listing built from the package name: app first, web as fallback. */
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

    // ------------------------------------------------------------ life cycle

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        // Coming back from the Play Store: if the game was missing, try again.
        if (state == State.NEEDS_STORE && !setupRunning) {
            runSetup();
        }
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
