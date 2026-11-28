package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.fvbox.lib.FCore;
import com.fvbox.lib.abs.ApplicationCallback;

/**
 * Loader-side boot for the SpaceCore engine ({@code com.fvbox.lib.FCore}).
 *
 * <p>The boot contract is far smaller than the previous engine's. BlackBox
 * needed {@code closeCodeInit()} → {@code onBeforeMainApplicationAttach()} →
 * {@code doAttachBaseContext()} → {@code onAfterMainApplicationAttach()} in
 * {@code attachBaseContext} and {@code doCreate()} in {@code onCreate}. SpaceCore
 * needs exactly one call before anything else touches the engine:</p>
 *
 * <pre>FCore.get().init(this, true);</pre>
 *
 * <p>The boolean is the engine's {@code isSamePkg} flag. The upstream reference
 * host app (SpaceCore's own {@code BoxApplication.kt}) passes {@code true}, and
 * that is the configuration for guests that run inside this APK's own
 * {@code :sc0..:scN} processes — which is how this loader runs the game.</p>
 *
 * <p>Guest-side hooks go through {@link ApplicationCallback}, which is a single
 * callback object (not a list of them, as in the old engine).
 * {@link ApplicationCallback#beforeCreateApplication} fires inside the guest
 * process right before the game's Application is created, which is exactly where
 * the payload must be loaded.</p>
 *
 * <p>There is no ClientConfiguration in this engine: the old
 * {@code isHideRoot()} / {@code isHideXposed()} switches have no boot-time
 * equivalent. Per-package behaviour is expressed with
 * {@code PackageRule} instead — see
 * {@code LaunchController.applyRules()}.</p>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    /** Package name of the loader itself. */
    private static volatile String sHostPackage;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        sHostPackage = base.getPackageName();

        // 1. Engine init. Must run before any other FCore call, in every
        //    process of this APK (the guest processes instantiate this same
        //    Application class).
        try {
            FCore.get().init(this, true);
        } catch (Throwable t) {
            Log.e(TAG, "FCore.init failed", t);
        }

        // 2. Register the guest-side hook that loads the payload. Because this
        //    runs in every process, the callback is installed in the :scN guest
        //    process too, which is where it has to fire.
        try {
            FCore.get().setApplicationCallback(new GuestPayloadLoader());
        } catch (Throwable t) {
            Log.e(TAG, "setApplicationCallback failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Log.i(TAG, "loader started: host=" + sHostPackage
                    + " engine=" + FCore.get().getVersion()
                    + " process=" + FCore.get().getProcessType());
        } catch (Throwable t) {
            Log.w(TAG, "engine state unavailable", t);
        }
    }

    /**
     * Runs inside the virtual app's own {@code :scN} process. This is the only
     * place where loading {@code libzyn-bs.so} can have an effect: the library
     * creates the game's lifecycle, and that lifecycle lives here.
     *
     * <p>Note the signature difference from the previous engine: SpaceCore
     * hands over package name, process name and user id only — not the guest
     * {@link Application} instance.</p>
     */
    private static final class GuestPayloadLoader implements ApplicationCallback {

        @Override
        public void beforeCreateApplication(String packageName, String processName, int userId) {
            if (!GameConfig.PACKAGE_NAME.equals(packageName)) {
                return;
            }
            Log.i(TAG, "guest " + processName + " for " + packageName + " -> loading payload");
            NativeBridge.loadInGuestProcess();
        }

        @Override
        public void afterCreatedApplication(
                Application application, String packageName, String processName, int userId) {
            Log.i(TAG, "guest application created: " + packageName + " in " + processName);
        }
    }
}
