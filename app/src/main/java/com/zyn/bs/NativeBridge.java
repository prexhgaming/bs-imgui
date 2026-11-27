package com.zyn.bs;

import android.content.Context;
import android.util.Log;
import java.io.File;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * JNI hook point for our own native payload ({@code libzyn-bs.so}, built by
 * Gradle from {@code app/src/main/jni/} via ndk-build).
 *
 * <p><b>The payload is loaded inside the GUEST process, not the loader
 * process.</b> The game is started as a virtual app in a {@code :pN} process and
 * its lifecycle (Application/Activity) exists only there. Loading the library
 * from {@code LaunchController} used to put it in the loader process, where
 * nothing ever ran the game's lifecycle, so the payload appeared to do nothing.
 * It is now loaded from an {@code AppLifecycleCallback} —
 * see {@code BoxApplication.GuestPayloadLoader}.</p>
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String LIBRARY = "zyn-bs";
    private static final String LIB_FILE = "libzyn-bs.so";

    private static volatile boolean sLoaded;

    private NativeBridge() {
    }

    /**
     * Loads the payload in the current (guest) process. Safe to call repeatedly:
     * the loader only ever gets one shot per process.
     *
     * @return {@code true} when the payload is loaded and ready.
     */
    public static boolean loadInGuestProcess() {
        if (sLoaded) {
            return true;
        }
        // The guest process still belongs to our own APK, so the dynamic linker
        // normally has our native lib dir on its search path already.
        try {
            System.loadLibrary(LIBRARY);
            sLoaded = true;
            Log.i(TAG, "payload loaded in guest process via loadLibrary(" + LIBRARY + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary(" + LIBRARY + ") failed in guest, using absolute path", t);
        }
        // Fallback: the host APK's own native lib dir. BlackBoxCore.getContext()
        // is the loader context, which is what we want here — the guest
        // ApplicationInfo points at the sandbox instead.
        try {
            Context host = BlackBoxCore.getContext();
            if (host == null) {
                Log.e(TAG, "no host context; cannot locate " + LIB_FILE);
                return false;
            }
            File lib = new File(host.getApplicationInfo().nativeLibraryDir, LIB_FILE);
            if (!lib.exists()) {
                Log.e(TAG, "payload missing for this ABI: " + lib);
                return false;
            }
            System.load(lib.getAbsolutePath());
            sLoaded = true;
            Log.i(TAG, "payload loaded in guest process from " + lib);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "payload load failed in guest process", t);
            return false;
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }
}
