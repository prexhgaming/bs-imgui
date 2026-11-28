package com.zyn.bs;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.fvbox.lib.FCore;
import com.fvbox.lib.common.pm.InstallResult;
import com.fvbox.lib.common.user.FUserInfo;
import com.fvbox.lib.rule.common.PackageRule;

import java.util.List;

/**
 * Install-then-launch, driven purely by the engine's public API
 * ({@code com.fvbox.lib.FCore}).
 *
 * <p>The game is always cloned from the copy that is <em>already installed on the
 * device</em>, by package name:</p>
 *
 * <pre>FCore.get().installPackageAsUser(packageName, userId)</pre>
 *
 * <p>That call resolves the system APK through the host {@code PackageManager}
 * and installs it with {@code InstallOption.installBySystem()} (FLAG_SYSTEM),
 * so the box references the existing system APK instead of copying it. The call
 * is therefore cheap, needs no storage permission and no file picker — it only
 * takes the package name. It is deliberately run on <em>every</em> launch rather
 * than only when {@code isInstalled()} is false: a sandbox entry left behind by
 * an earlier install (or by the previous engine, whose data layout is
 * incompatible) can be present but broken, and re-registering is cheap.</p>
 *
 * <p>Two things differ from the old BlackBox flow:</p>
 * <ul>
 *   <li>{@code FCore.launchApk()} returns {@code void}, not a boolean. The
 *       failure signal is {@code getLaunchIntentForPackage()} — the same call
 *       {@code launchApk()} uses internally — returning {@code null}.</li>
 *   <li>There is no {@code installGms()} / {@code isInstallGms()} in this
 *       engine, so the GMS pre-install step is gone. SpaceCore ships its own
 *       Google-services handling inside the AAR.</li>
 * </ul>
 *
 * <p>There is no worker thread and no artificial delay — every call here is
 * metadata-only. The payload is not touched here at all: it is loaded inside the
 * guest process by
 * {@code BoxApplication.GuestPayloadLoader.beforeCreateApplication}.</p>
 */
public class LaunchController {

    private static final String TAG = "LaunchController";

    /** Sandbox user slot the game is cloned into. */
    private static final int USER_ID = 0;

    /** Package rules only have to be pushed once per process. */
    private static volatile boolean sRuleApplied;

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
        ensureUser();
        applyRules();

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
            FCore.get().uninstallPackageAsUser(packageName, USER_ID);
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
     * SpaceCore keeps an explicit user table; unlike the previous engine, slot 0
     * is not assumed to exist. Create it if the table does not list it yet, so
     * the install below has somewhere to go.
     */
    private void ensureUser() {
        try {
            List<FUserInfo> users = FCore.get().getUsers();
            if (users != null) {
                for (FUserInfo user : users) {
                    if (user != null && user.id == USER_ID) {
                        return;
                    }
                }
            }
            Log.i(TAG, "creating sandbox user " + USER_ID);
            FCore.get().createUser(USER_ID);
        } catch (Throwable t) {
            // Not fatal: the install below reports the real error if the user
            // really is required.
            Log.w(TAG, "user check failed, continuing with user " + USER_ID, t);
        }
    }

    /**
     * Per-package rules are this engine's replacement for the previous engine's
     * {@code ClientConfiguration} switches. That one hid root globally
     * ({@code isHideRoot()} → true); here the same intent is a package rule.
     * Applied once per process, and never fatal.
     */
    private void applyRules() {
        if (sRuleApplied) {
            return;
        }
        sRuleApplied = true;
        try {
            PackageRule rule = new PackageRule.Builder(packageName)
                    .addUserId(USER_ID)
                    .isHideRoot(true)
                    .build();
            FCore.get().addRule(rule);
            Log.i(TAG, "hide-root rule registered for " + packageName);
        } catch (Throwable t) {
            Log.w(TAG, "addRule failed, continuing", t);
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
            InstallResult result = FCore.get()
                    .installPackageAsUser(packageName, USER_ID);
            if (result != null && !result.getSuccess()) {
                Log.e(TAG, "installPackageAsUser failed: " + result.getMsg());
                // An older copy may still be usable; prefer launching it over
                // refusing outright, but only if it really is registered.
                if (isInSandbox()) {
                    Log.w(TAG, "keeping the existing sandbox copy");
                    return true;
                }
                String message = result.getMsg();
                fail(message != null && !message.isEmpty()
                        ? message
                        : context.getString(R.string.error_install_failed));
                return false;
            }
            Log.i(TAG, "cloned " + packageName + " from the system package");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "installPackageAsUser threw", t);
            // getMessage() is frequently null, which is how this used to show
            // up as "Could not install the game into the sandbox. null" and
            // tell us nothing. The class name is always useful.
            CrashLog.e(TAG, "installPackageAsUser threw for " + packageName, t);
            fail(context.getString(R.string.error_install_failed)
                    + "\n" + CrashLog.describe(t));
            return false;
        }
    }

    private boolean isInSandbox() {
        try {
            return FCore.get().isInstalled(packageName, USER_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the real device (outside the sandbox) has this package. */
    private boolean isInstalledOnDevice() {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "device install check failed", t);
            return false;
        }
    }

    /**
     * Hands the launch to the engine.
     *
     * <p>{@code FCore.launchApk()} is {@code void}, so the launch intent is the
     * failure signal: {@code getLaunchIntentForPackage()} is what
     * {@code launchApk()} resolves internally, and a {@code null} result means
     * there is nothing to start.</p>
     *
     * @return true when the engine was handed a real launch intent.
     */
    private boolean launchGame() {
        emit(State.LAUNCHING, context.getString(R.string.status_launching));
        try {
            Intent intent = FCore.get().getLaunchIntentForPackage(packageName, USER_ID);
            if (intent == null) {
                Log.e(TAG, "no launch intent for " + packageName);
                return false;
            }
            FCore.get().launchApk(packageName, USER_ID);
        } catch (Throwable t) {
            CrashLog.e(TAG, "launchApk threw for " + packageName, t);
            return false;
        }
        busy = false;
        emit(State.RUNNING, context.getString(R.string.status_running));
        return true;
    }

    private void fail(String message) {
        busy = false;
        // The UI message is what the user sees, so it belongs in the file too:
        // a screenshot of the toast and this line are then the same thing.
        CrashLog.e(TAG, "launch sequence failed: " + message, null);
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
