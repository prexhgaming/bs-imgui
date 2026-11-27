package com.zyn.bs;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.zyn.bs.ui.LauncherView;

/**
 * Home screen of the loader.
 *
 * <p>Resolves the configured package ({@link GameConfig#PACKAGE_NAME}) into a
 * label + icon via {@link GameResolver}. If the game is present the button runs
 * the bcore launch sequence; if it is missing the button becomes an install
 * action that opens the package's Play Store listing.</p>
 */
public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "LauncherActivity";

    private LauncherView view;
    private LaunchController launchController;
    private GameInfo gameInfo;

    /** True while the game is not on the device, so the button installs it. */
    private boolean installMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new LauncherView(this);
        view.setListener(this::onPrimaryAction);
        setContentView(view);

        view.setGameTitle(GameConfig.PACKAGE_NAME);
        view.setPrimaryEnabled(false);
        resolveGame();
    }

    /**
     * Looks the package up off the main thread, then binds the real name and
     * icon. Until this returns the package name stands in as the title.
     */
    private void resolveGame() {
        new Thread(() -> {
            final GameInfo info = GameResolver.resolve(getApplicationContext());
            runOnUiThread(() -> bindGame(info));
        }, "resolve-game").start();
    }

    private void bindGame(GameInfo info) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        gameInfo = info;
        installMode = !info.installed;

        view.setGameTitle(info.label);
        view.bindGameIcon(info.icon);

        if (info.installed) {
            view.showReady();
        } else {
            view.showNotInstalled(info.label);
        }
    }

    private void onPrimaryAction() {
        if (gameInfo == null) {
            // Resolution still running — nothing sensible to do yet.
            return;
        }
        if (installMode || !gameInfo.installed) {
            openPlayStore();
            return;
        }
        startLaunch();
    }

    private void startLaunch() {
        if (launchController == null) {
            launchController = new LaunchController(this, GameConfig.PACKAGE_NAME,
                    this::onLaunchState);
        }
        launchController.start();
    }

    private void onLaunchState(LaunchController.State state, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        switch (state) {
            case INSTALLING:
                view.showBusy(R.string.status_installing, R.string.hint_installing);
                break;
            case LAUNCHING:
                view.showBusy(R.string.status_launching, R.string.hint_launching);
                break;
            case RUNNING:
                view.showRunning();
                break;
            case ERROR:
                view.showError(message != null ? message : getString(R.string.status_error));
                Toast.makeText(this,
                        message != null ? message : getString(R.string.status_error),
                        Toast.LENGTH_LONG).show();
                break;
            case IDLE:
            default:
                view.showReady();
                break;
        }
    }

    /**
     * Opens the Play Store listing for the configured package, falling back to
     * the web listing when no store app handles {@code market://}.
     */
    private void openPlayStore() {
        Intent store = new Intent(Intent.ACTION_VIEW, GameConfig.playStoreUri());
        store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(store);
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(TAG, "no market:// handler, falling back to web listing");
        }

        Intent web = new Intent(Intent.ACTION_VIEW, GameConfig.playStoreWebUri());
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
        // The user may have installed the game from the Play Store while away.
        if (gameInfo != null && !gameInfo.installed) {
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

    @Override
    protected void onDestroy() {
        if (launchController != null) {
            launchController.destroy();
            launchController = null;
        }
        super.onDestroy();
    }
}
