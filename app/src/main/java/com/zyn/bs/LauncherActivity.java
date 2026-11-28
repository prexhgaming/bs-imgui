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
 * <p>Engine calls go through {@link BlackBoxCore} —
 * {@code isInstalled}, {@code installPackageAsUser} and {@code launchApk} — on
 * the same sandbox user slot, so behaviour matches the engine's contract.</p>
 *
 * <p>A single target game is used ({@link #TARGET_PACKAGE}); its label and icon
 * are read from the device at runtime, never hardcoded. There is no OBB step and
 * no game picker. The wallpaper is compiled in as raw bytes ({@link Wallpaper});
 * the screen itself lives in {@code com.zyn.bs.ui}.</p>
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
        setContentView(view);

        view.setGameTitle(TARGET_PACKAGE);
        view.setPrimaryEnabled(false);
        requestCrashLogPermission();
        resolveGame();
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
                if (!BlackBoxCore.get().isInstalled(TARGET_PACKAGE, USER_ID)) {
                    InstallResult result =
                            BlackBoxCore.get().installPackageAsUser(TARGET_PACKAGE, USER_ID);
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
            BlackBoxCore.get().launchApk(TARGET_PACKAGE, USER_ID);
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
}
