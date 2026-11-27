package com.zyn.bs;

import android.util.Log;

/**
 * Loads our native payload ({@code libzyn-bs.so}, built by Gradle from
 * {@code app/src/main/jni/} via ndk-build).
 *
 * <p><b>It must be loaded in the GUEST process, not the loader process.</b>
 * The game runs as a virtual app in a {@code :pN} process and its lifecycle
 * exists only there. {@link BoxApplication.GuestPayloadLoader} calls this from
 * the engine's {@code beforeApplicationOnCreate} hook, which fires inside that
 * guest process right before the game's Application is created — so the library
 * attaches to the game process exactly like a normal app's own {@code .so}.</p>
 *
 * <p>Keep it to a plain {@code System.loadLibrary}: the guest process belongs
 * to our own APK, so its native library directory is already on the linker's
 * search path. No absolute-path fallback, no manual dlopen.</p>
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String LIBRARY = "zyn-bs";

    private static volatile boolean sLoaded;

    private NativeBridge() {
    }

    /** Loads the payload in the current (guest) process. Idempotent. */
    public static boolean loadInGuestProcess() {
        if (sLoaded) {
            return true;
        }
        try {
            System.loadLibrary(LIBRARY);
            sLoaded = true;
            Log.i(TAG, "payload loaded in guest process: loadLibrary(" + LIBRARY + ")");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary(" + LIBRARY + ") failed in guest process", t);
            return false;
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }
}
