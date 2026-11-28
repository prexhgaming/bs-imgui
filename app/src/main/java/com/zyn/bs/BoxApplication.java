package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.elite.EliteInstaller;
import com.elite.app.configuration.AppLifecycleCallback;
import com.elite.app.configuration.ClientConfiguration;

import net_62v.external.MetaActivationManager;

import java.io.File;

/**
 * Host Application for the {@code elite} sandbox engine.
 *
 * <p>The boot sequence is the one the vbox host app uses, in the same order:</p>
 * <ol>
 *   <li>{@code attachBaseContext()} → {@code EliteInstaller.get().doAttachBaseContext()}
 *       with a {@link ClientConfiguration} that hides root/xposed and disables the
 *       daemon service.</li>
 *   <li>{@code onCreate()} → {@code MetaActivationManager.activateSdk(KEY)}: the SDK
 *       key is compiled in, so activation happens automatically on every start and
 *       the user is never asked for it.</li>
 *   <li>{@code EliteInstaller.get().doCreate()} → engine ready.</li>
 *   <li>{@code addAppLifecycleCallback()} → the native payload is loaded inside the
 *       guest process, just before the game's own Application is created.</li>
 * </ol>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    /** Sandbox SDK key — activated automatically in {@link #onCreate()}. */
    private static final String SDK_KEY = "Z4T2ZYUIYDWOG1JD7635";

    /** Payload library, packaged in this APK as {@code lib<name>.so}. */
    private static final String PAYLOAD_LIB = "zyn-bs";

    /** The one game this loader targets; only its guest process gets the payload. */
    private static final String TARGET_PACKAGE = "com.netease.newspike";

    private static BoxApplication singleton;
    private static volatile boolean sPayloadLoaded;

    public static BoxApplication getInstance() {
        return singleton;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            EliteInstaller.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isHideRoot() {
                    return true;
                }

                @Override
                public boolean isHideXposed() {
                    return true;
                }

                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }

                @Override
                public boolean requestInstallPackage(File file) {
                    return false;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "doAttachBaseContext failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        singleton = this;

        // 1. Automatic SDK key activation — no UI, no user input.
        try {
            MetaActivationManager.activateSdk(SDK_KEY);
            Log.i(TAG, "SDK key activated");
        } catch (Throwable t) {
            Log.e(TAG, "activateSdk failed", t);
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // 2. Engine create.
        try {
            EliteInstaller.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "doCreate failed", t);
        }

        // 3. Guest-side hook that loads the payload.
        try {
            EliteInstaller.get().addAppLifecycleCallback(new AppLifecycleCallback() {
                @Override
                public void beforeApplicationOnCreate(String packageName, String processName,
                                                      Application application, int userId) {
                    if (!TARGET_PACKAGE.equals(packageName)) {
                        return;
                    }
                    loadPayload(packageName, processName);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "addAppLifecycleCallback failed", t);
        }
    }

    /**
     * Loads the native payload in the guest process that is about to create the
     * game's Application: a payload staged in {@code files/loader/} wins,
     * otherwise the copy packaged in the APK is used. Never fatal, so a missing
     * payload cannot kill the game process.
     */
    private void loadPayload(String packageName, String processName) {
        if (sPayloadLoaded) {
            return;
        }
        try {
            File staged = new File(getFilesDir(), "loader/lib" + PAYLOAD_LIB + ".so");
            if (staged.exists()) {
                System.load(staged.getAbsolutePath());
                Log.i(TAG, "payload loaded from " + staged.getAbsolutePath());
            } else {
                System.loadLibrary(PAYLOAD_LIB);
                Log.i(TAG, "payload loaded from the APK");
            }
            sPayloadLoaded = true;
            Log.i(TAG, "guest " + processName + " ready for " + packageName);
        } catch (Throwable t) {
            Log.e(TAG, "payload not loaded for " + packageName + " in " + processName, t);
        }
    }
}
