package com.zyn.bs;

import android.util.Log;

/**
 * JNI hook point for our own native payload ({@code libzyn-bs.so}, built by
 * Gradle from {@code app/src/main/jni/} via ndk-build).
 *
 * <p>This is the slot where the reference loader started its ESP service. We
 * call it at the same moment in the launch sequence, so behaviour matches while
 * the payload itself is entirely ours.</p>
 *
 * <p>Note: the guest game runs in a separate bcore process. The library is
 * packaged into the APK for this ABI; calling {@link #load()} here only proves
 * the payload is present and loadable, and is safe to call repeatedly.</p>
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String LIBRARY = "zyn-bs";

    private static volatile boolean sLoaded;
    private static volatile boolean sAttempted;

    private NativeBridge() {
    }

    /**
     * @return {@code true} when the payload is loaded and ready.
     */
    public static boolean load() {
        if (sLoaded) {
            return true;
        }
        if (sAttempted) {
            return false;
        }
        sAttempted = true;
        try {
            System.loadLibrary(LIBRARY);
            sLoaded = true;
            Log.i(TAG, "loaded lib" + LIBRARY + ".so");
        } catch (Throwable t) {
            Log.w(TAG, "lib" + LIBRARY + ".so not packaged for this ABI yet", t);
        }
        return sLoaded;
    }

    public static boolean isLoaded() {
        return sLoaded;
    }
}
