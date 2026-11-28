package com.zyn.bs;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Install-then-launch, driven purely by the core's public API.
 *
 * <p>The game is always cloned from the copy that is <em>already installed on the
 * device</em>, by package name:</p>
 *
 * <pre>BlackBoxCore.get().installPackageAsUser(packageName, userId)</pre>
 *
 * <p>That call resolves the system APK through the host {@code PackageManager}
 * and registers it with {@code InstallOption.installBySystem()}. The system flag
 * means the box references the existing system APK instead of copying it
 * ({@code CopyExecutor} does nothing for it), so the call is cheap, needs no
 * storage permission and no file picker — it only takes the package name.</p>
 *
 * <p>It is deliberately run on <em>every</em> launch rather than only when
 * {@code isInstalled()} is false: a sandbox entry left behind by an earlier
 * install (or an earlier engine) can be present but broken, in which case the
 * old flow skipped the install and {@code launchApk} then silently did nothing.
 * Re-registering from the system package is cheap and always leaves a valid
 * entry.</p>
 *
 * <p>There is no worker thread and no artificial delay — every call here is
 * metadata-only. The payload is not touched here at all: it is loaded inside the
 * guest process by
 * {@code BoxApplication.GuestPayloadLoader.beforeApplicationOnCreate}.</p>
 */
public class LaunchController {

    private static final String TAG = "LaunchController";

    /** Sandbox user slot the game is cloned into. */
    private static final int USER_ID = 0;

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
        resolveAndRun();
    }

    private void resolveAndRun() {
        // Install GMS into the sandbox first. The game depends on Play
        // services; cloning them from the device is cheap (system-flag install,
        // no file copy) and harmless if they are already there.
        ensureGms();

        if (!isInstalledOnDevice()) {
            Log.e(TAG, packageName + " is not installed on the device");
            fail(context.getString(R.string.error_not_installed));
            return;
        }

        if (!installFromSystem()) {
            return;
        }

        if (launchGame()) {
            return;
        }

        // The entry existed but produced no launch intent. Wipe it and re-clone
        // once — this is the recovery path for a stale sandbox entry.
        Log.w(TAG, "launch failed, re-cloning " + packageName + " from the system package");
        try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, USER_ID);
        } catch (Throwable t) {
            Log.w(TAG, "uninstall before retry failed", t);
        }
        if (!installFromSystem()) {
            return;
        }
        if (!launchGame()) {
            // Without this the UI would sit on "ENTERING SANDBOX…" forever with
            // the button disabled, which is exactly how a failed launch used to
            // present itself as "nothing happens".
            fail(context.getString(R.string.error_launch_failed));
        }
    }

    /**
     * Clones Google Play services into the sandbox if the host has them. GMS
     * is installed from the system package (no copy, no extra permission) and
     * a failure is logged but not fatal — the game may still run without it.
     */
    private void ensureGms() {
        try {
            if (BlackBoxCore.get().isInstallGms(USER_ID)) {
                return;
            }
            if (BlackBoxCore.getContext().getPackageManager()
                    .getPackageInfo(GmsCore.GMS_PKG, 0) == null) {
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "GMS not present on the device, skipping");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "GMS check failed, skipping", t);
            return;
        }

        Log.i(TAG, "installing GMS into the sandbox");
        try {
            InstallResult gmsResult = BlackBoxCore.get().installGms(USER_ID);
            if (gmsResult != null && !gmsResult.success) {
                Log.w(TAG, "GMS install failed: " + gmsResult.msg);
            } else {
                Log.i(TAG, "GMS installed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "GMS install threw", t);
        }
    }

    /**
     * Registers the device's own APK with the sandbox.
     *
     * @return true when the sandbox holds a usable entry afterwards.
     */
    private boolean installFromSystem() {
        emit(State.INSTALLING, context.getString(R.string.status_installing));
        try {
            InstallResult result = BlackBoxCore.get()
                    .installPackageAsUser(packageName, USER_ID);
            if (result != null && !result.success) {
                Log.e(TAG, "installPackageAsUser failed: " + result.msg);
                // An older copy may still be usable; prefer launching it over
                // refusing outright, but only if it really is registered.
                if (isInSandbox()) {
                    Log.w(TAG, "keeping the existing sandbox copy");
                    return true;
                }
                fail(result.msg != null && !result.msg.isEmpty()
                        ? result.msg
                        : context.getString(R.string.error_install_failed));
                return false;
            }
            Log.i(TAG, "cloned " + packageName + " from the system package");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "installPackageAsUser threw", t);
            fail(context.getString(R.string.error_install_failed) + "\n" + t.getMessage());
            return false;
        }
    }

    private boolean isInSandbox() {
        try {
            return BlackBoxCore.get().isInstalled(packageName, USER_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hands the launch to the core.
     *
     * @return true when the core reported that it started the game.
     */
    private boolean launchGame() {
        emit(State.LAUNCHING, context.getString(R.string.status_launching));
        try {
            if (!BlackBoxCore.get().launchApk(packageName, USER_ID)) {
                Log.e(TAG, "launchApk returned false (no launch intent for " + packageName + ")");
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "launchApk threw", t);
            return false;
        }
        busy = false;
        emit(State.RUNNING, context.getString(R.string.status_running));
        return true;
    }

    private void fail(String message) {
        busy = false;
        Log.e(TAG, "launch sequence failed: " + message);
        emit(State.ERROR, message);
    }

    private void emit(State state, String message) {
        if (callback != null) {
            callback.onStateChanged(state, message);
        }
    }

    /** Releases state; call from {@code onDestroy}. */
    public void destroy() {
        busy = false;
    }
}
