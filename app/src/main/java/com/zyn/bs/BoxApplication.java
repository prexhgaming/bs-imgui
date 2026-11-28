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

    /**
     * Payload library name — the single string to change when swapping payloads.
     *
     * <p>The guest copy is expected at
     * {@code <filesDir>/loader/lib<TARGET_LIB>.so}, the same staging location the
     * reference host uses; if it is absent the copy packaged in this APK as
     * {@code lib<TARGET_LIB>.so} is loaded instead.</p>
     */
    private static final String TARGET_LIB = "zyn-bs";

    /** Folder and file name derived from {@link #TARGET_LIB}. */
    private static final String LOADER_DIR = "loader";
    private static final String LIB_FILE = "lib" + TARGET_LIB + ".so";

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

                // The engine resolves these two reflectively, so like the
                // reference host they carry no @Override: ClientConfiguration
                // does not declare them, annotating them fails the build.
                public boolean isHideRoot() {
                    return true;
                }

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
                    // Same hook the reference host uses: inside the guest process,
                    // immediately before the game's own Application is created.
                    // Restricted to the game's main process, again like the
                    // reference (drop the processName test to cover sub-processes).
                    if (!TARGET_PACKAGE.equals(packageName)
                            || !TARGET_PACKAGE.equals(processName)) {
                        return;
                    }
                    loadPayload();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "addAppLifecycleCallback failed", t);
        }
    }

    /**
     * Loads the native payload in the guest process that is about to create the
     * game's Application.
     *
     * <p>Same staging location the reference host uses: {@code lib<TARGET_LIB>.so}
     * inside the {@code loader} folder of the files directory. If that copy is
     * absent, the one packaged in this APK is loaded instead, so the loader still
     * works when nothing was staged. Never fatal, so a missing payload cannot
     * kill the game process.</p>
     */
    private void loadPayload() {
        if (sPayloadLoaded) {
            return;
        }
        File staged = new File(getFilesDir(), LOADER_DIR + File.separator + LIB_FILE);
        try {
            if (staged.exists()) {
                System.load(staged.getAbsolutePath());
                Log.i(TAG, LIB_FILE + " loaded from " + staged.getAbsolutePath());
            } else {
                Log.w(TAG, LIB_FILE + " not staged at " + staged.getAbsolutePath()
                        + "; loading the packaged copy");
                System.loadLibrary(TARGET_LIB);
                Log.i(TAG, LIB_FILE + " loaded from the APK");
            }
            sPayloadLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, LIB_FILE + " not loaded", t);
        }
    }
}
