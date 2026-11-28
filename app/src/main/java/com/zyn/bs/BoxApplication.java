package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

import java.io.File;

/**
 * Host Application for the {@code BlackBox} sandbox engine (Bcore).
 *
 * <p>Boot sequence, in the order BlackBox expects:</p>
 * <ol>
 *   <li><b>Static initializer</b> → {@link CrashHandler#install()}, so crashes are
 *       logged even if the engine fails while attaching.</li>
 *   <li>{@code attachBaseContext()} → crash context attached first, then
 *       {@code BlackBoxCore.get().doAttachBaseContext()} with a
 *       {@link ClientConfiguration} that reports the host package, hides root and
 *       disables the daemon service.</li>
 *   <li>{@code onCreate()} → {@code BlackBoxCore.get().doCreate()} → engine ready.</li>
 *   <li>In a sandboxed process, the native payload is loaded right away, which is
 *       before the guest Application is bound.</li>
 * </ol>
 *
 * <p>This engine has <b>no licence/SDK activation of any kind</b>: there is no key
 * to enter, no activation call and nothing to keep valid at runtime.</p>
 */
public class BoxApplication extends Application {

    static {
        // Runs at class load — before attachBaseContext(), before the engine is
        // touched and before any other app code. This is what lets the crash
        // handler catch a failure inside the engine's own boot.
        CrashHandler.install();
    }

    private static final String TAG = "BoxApplication";

    /**
     * Payload library name — the single string to change when swapping payloads.
     *
     * <p>The guest copy is expected at
     * {@code <filesDir>/loader/lib<TARGET_LIB>.so}; if it is absent the copy
     * packaged in this APK as {@code lib<TARGET_LIB>.so} is loaded instead.</p>
     */
    private static final String TARGET_LIB = "zyn-bs";

    /** Folder and file name derived from {@link #TARGET_LIB}. */
    private static final String LOADER_DIR = "loader";
    private static final String LIB_FILE = "lib" + TARGET_LIB + ".so";

    private static BoxApplication singleton;
    private static volatile boolean sPayloadLoaded;

    public static BoxApplication getInstance() {
        return singleton;
    }

    @Override
    protected void attachBaseContext(Context base) {
        // First statement on purpose: gives the crash handler a context before
        // anything else (super or the engine) can throw.
        CrashHandler.attach(base);
        super.attachBaseContext(base);
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isHideRoot() {
                    return true;
                }

                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }

                @Override
                public boolean isDisableFlagSecure() {
                    return true;
                }

                @Override
                public boolean requestInstallPackage(File file, int userId) {
                    // The host installs nothing on the engine's behalf; the
                    // launcher registers the game's own APK explicitly.
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

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        try {
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "doCreate failed", t);
        }

        // The engine exposes no guest-Application lifecycle callback, so the
        // payload is loaded here instead: in a sandboxed process this runs at
        // process start, before the engine binds the guest Application.
        if (isSandboxProcess()) {
            loadPayload();
        }

        // The engine may have installed its own default handler while booting;
        // put ours back on top so its crashes are still written to disk.
        CrashHandler.reassert();
    }

    /** True only inside a process the engine created for a guest app. */
    private boolean isSandboxProcess() {
        try {
            return BlackBoxCore.get().isBlackProcess();
        } catch (Throwable t) {
            Log.w(TAG, "isBlackProcess failed", t);
            return false;
        }
    }

    /**
     * Loads the native payload in the sandboxed process.
     *
     * <p>Prefers the staged copy at {@code <filesDir>/loader/lib<TARGET_LIB>.so}
     * and falls back to the copy packaged in this APK, so the loader still works
     * when nothing was staged. Never fatal: a missing payload must not kill the
     * game process.</p>
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
