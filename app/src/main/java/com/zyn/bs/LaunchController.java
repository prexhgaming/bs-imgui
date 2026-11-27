package com.zyn.bs;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * The launch sequence, in the same order the reference loader used:
 *
 * <ol>
 *   <li>already cloned inside the sandbox → launch</li>
 *   <li>installed on the device → clone it into the sandbox, then launch</li>
 *   <li>not installed anywhere → error</li>
 * </ol>
 *
 * <p>Cloning is a single core call: {@code installPackageAsUser} copies the APK
 * into the sandbox, so this class never touches storage itself. There is no OBB
 * step: the game ships as one APK with no expansion file.</p>
 *
 * <p>Every core call goes through {@link BlackBoxCore}. There is no activation
 * step: the core carries no licence gate and sets itself up in
 * {@code BoxApplication.attachBaseContext}.</p>
 *
 * <p>Launch itself waits 3 s (the reference showed a loading animation for that
 * long) and the payload hook fires 10 s later, mirroring the reference's ESP
 * start.</p>
 */
public class LaunchController {

    private static final String TAG = "LaunchController";

    /** Sandbox user slot the game is cloned into. */
    private static final int USER_ID = 0;
    /** Delay before {@code launchApk}, matching the reference's dialog. */
    private static final long LAUNCH_DELAY_MS = 3000L;
    /** Delay before the native payload hook, matching the reference's ESP start. */

    public enum State {
        IDLE,
        INSTALLING,
        LAUNCHING,
        RUNNING,
        ERROR
    }

    public interface Callback {
        void onStateChanged(State state, String message);
    }

    private final Context context;
    private final String packageName;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback callback;

    private volatile boolean busy;

    public LaunchController(Context context, String packageName, Callback callback) {
        this.context = context.getApplicationContext();
        this.packageName = packageName;
        this.callback = callback;
    }

    public boolean isBusy() {
        return busy;
    }

    /** Entry point — safe to call repeatedly; ignored while a launch is running. */
    public void start() {
        if (busy) {
            return;
        }
        busy = true;
        emit(State.IDLE, null);

        new Thread(this::resolveAndRun, "launch-resolve").start();
    }

    private void resolveAndRun() {
        final boolean inSandbox;
        final boolean onDevice;
        try {
            inSandbox = BlackBoxCore.get().isInstalled(packageName, USER_ID);
            onDevice = isInstalledOnDevice();
        } catch (Throwable t) {
            Log.e(TAG, "state resolution failed", t);
            fail("Could not read the sandbox state.");
            return;
        }

        if (inSandbox) {
            post(this::launchGame);
        } else if (onDevice) {
            post(this::installGame);
        } else {
            fail(context.getString(R.string.error_not_installed));
        }
    }

    private boolean isInstalledOnDevice() {
        try {
            context.getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Step 2 — clone the device install into the sandbox, then launch.
     *
     * <p>No OBB handling anywhere: the game ships as a single APK with no
     * expansion file, so the installer only has to copy the package.</p>
     */
    private void installGame() {
        emit(State.INSTALLING, context.getString(R.string.status_installing));
        mainHandler.postDelayed(() -> {
            try {
                InstallResult result = BlackBoxCore.get()
                        .installPackageAsUser(packageName, USER_ID);
                if (result != null && !result.success) {
                    Log.e(TAG, "installPackageAsUser failed: " + result.msg);
                    fail(context.getString(R.string.error_install_failed));
                    return;
                }
            } catch (Throwable t) {
                Log.e(TAG, "installPackageAsUser threw", t);
                fail(context.getString(R.string.error_install_failed));
                return;
            }
            launchGame();
        }, 2000L);
    }

    /** Step 1/3 — launch the sandboxed game; the guest then loads the payload. */
    private void launchGame() {
        emit(State.LAUNCHING, context.getString(R.string.status_launching));

        mainHandler.postDelayed(() -> {
            final boolean started;
            try {
                started = BlackBoxCore.get().launchApk(packageName, USER_ID);
            } catch (Throwable t) {
                Log.e(TAG, "launchApk threw", t);
                fail(context.getString(R.string.error_install_failed));
                return;
            }
            if (!started) {
                Log.e(TAG, "launchApk returned false (no launch intent?)");
                fail(context.getString(R.string.error_install_failed));
                return;
            }
            emit(State.RUNNING, context.getString(R.string.status_running));
            // The payload is deliberately NOT armed here. It creates the game's
            // lifecycle, so it must be loaded in the guest process where that
            // lifecycle exists — see BoxApplication.GuestPayloadLoader. Loading
            // it in this process did nothing at all.
        }, LAUNCH_DELAY_MS);
    }

    private void fail(String message) {
        busy = false;
        emit(State.ERROR, message);
    }

    private void emit(State state, String message) {
        busy = state == State.RUNNING;
        if (callback != null) {
            callback.onStateChanged(state, message);
        }
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /** Releases pending callbacks; call from {@code onDestroy}. */
    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        busy = false;
    }
}
