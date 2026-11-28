package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import java.io.File;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/**
 * Loader-side boot. The order and the set of enabled knobs mirror the engine's
 * own reference host app exactly, because anything the reference enables and we
 * leave out shows up as "the sandbox is not set up right" at launch time.
 *
 * <p>Boot sequence, same as the reference: {@code closeCodeInit()} →
 * {@code onBeforeMainApplicationAttach()} → {@code doAttachBaseContext()} →
 * {@code onAfterMainApplicationAttach()}, then {@code doCreate()} plus the
 * service-availability callback in {@code onCreate()}.</p>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    /** Package name of the loader itself, needed by ClientConfiguration. */
    private static volatile String sHostPackage;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        sHostPackage = base.getPackageName();

        // 1. Same first step as the reference loader.
        try {
            BlackBoxCore.get().closeCodeInit();
        } catch (Throwable t) {
            Log.e(TAG, "closeCodeInit failed", t);
        }
        // 2. Before the main application is attached.
        try {
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base);
        } catch (Throwable t) {
            Log.e(TAG, "onBeforeMainApplicationAttach failed", t);
        }
        // 3. Hand the core our ClientConfiguration.
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new LoaderConfiguration());
        } catch (Throwable t) {
            Log.e(TAG, "doAttachBaseContext failed", t);
        }
        // 4. After the main application is attached.
        try {
            BlackBoxCore.get().onAfterMainApplicationAttach(this, base);
        } catch (Throwable t) {
            Log.e(TAG, "onAfterMainApplicationAttach failed", t);
        }
        // 5. Register the guest-side hook that loads the payload in the process
        //    the game actually runs in.
        try {
            BlackBoxCore.get().addAppLifecycleCallback(new GuestPayloadLoader());
        } catch (Throwable t) {
            Log.e(TAG, "addAppLifecycleCallback failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "doCreate failed", t);
        }
        try {
            BlackBoxCore.get()
                    .addServiceAvailableCallback(
                            () -> Log.i(TAG, "sandbox services available"));
        } catch (Throwable t) {
            Log.e(TAG, "addServiceAvailableCallback failed", t);
        }
    }

    /**
     * Runs inside the virtual app's own process. This is the only place where
     * loading {@code libzyn-bs.so} can have an effect: the library creates the
     * game's lifecycle, and that lifecycle lives here.
     */
    private static final class GuestPayloadLoader extends AppLifecycleCallback {

        @Override
        public void beforeApplicationOnCreate(
                String packageName, String processName, Application application, int userId) {
            if (!GameConfig.PACKAGE_NAME.equals(packageName)) {
                return;
            }
            Log.i(TAG, "guest " + processName + " for " + packageName + " -> loading payload");
            NativeBridge.loadInGuestProcess();
        }
    }

    /**
     * Same overrides the reference host app exposes. Note what is deliberately
     * absent: {@code isEnableLauncherActivity} is NOT overridden. It does not
     * mean "no launcher UI" — {@code BlackBoxCore.startActivity()} uses it to
     * choose the launch path, and forcing it false makes {@code launchApk()}
     * report success while never bringing the game up.
     */
    private static final class LoaderConfiguration extends ClientConfiguration {

        @Override
        public String getHostPackageName() {
            return sHostPackage;
        }

        @Override
        public boolean isHideRoot() {
            return true;
        }

        // This engine replaced `isUseVpnNetwork()` / `isDisableFlagSecure()`
        // (which the previous engines had) with `isHideXposed()`. Overriding the
        // old two is a compile error here: "method does not override or
        // implement a method from a supertype".
        @Override
        public boolean isHideXposed() {
            return true;
        }

        @Override
        public boolean isEnableDaemonService() {
            return false;
        }

        @Override
        public boolean requestInstallPackage(File file, int userId) {
            return false;
        }
    }
}
