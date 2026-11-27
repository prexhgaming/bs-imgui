package com.zyn.bs;

import android.util.Log;

import net_62v.external.MetaActivationManager;

/**
 * Acquaints the Mundo core with our licence, because the core refuses to do
 * anything until it considers itself "prepared".
 *
 * <p><b>Why this exists.</b> The core gates every operation on an internal flag
 * that it does not expose through Java. The gate is a JNI round trip —
 * {@code MNative.ioctl(43012, null)} → {@code Boolean.parseBoolean(result)} — and
 * while it returns {@code false} the core pops a toast reading "SDK aún no está
 * bien preparado..." and refuses the launch. That flag is flipped by the native
 * library ({@code libzyluqeh.so}, which exports {@code Mundo_Activate_SDK}) when
 * {@link MetaActivationManager#activateSdk(String)} hands it a valid key, so the
 * call cannot simply be skipped.</p>
 *
 * <p>Failures are swallowed on purpose. Activation needs a network round trip, so
 * a device that is offline at boot will fail here; every launch attempt retries
 * (see {@link LaunchController}), and the core reports the real problem itself.
 * Throwing would only replace a recoverable state with a crash.</p>
 */
public final class CoreActivation {

    private static final String TAG = "CoreActivation";

    /**
     * Licence key for this loader's core. Kept as a plain constant because the APK
     * is distributed by us and the key has to be readable here to be rotated.
     */
    private static final String LICENSE = "JX2OWBCSXBO0SIZGLGC3";

    private static final Object LOCK = new Object();

    private CoreActivation() {
    }

    /**
     * Talks to the activation service and the native core. Safe to call from any
     * thread and safe to call repeatedly; later calls re-sync the status.
     *
     * @return whatever the core reports afterwards — {@code false} simply means
     *         "not ready yet", never "broken".
     */
    public static boolean activate() {
        synchronized (LOCK) {
            try {
                MetaActivationManager.activateSdk(LICENSE);
            } catch (Throwable t) {
                Log.w(TAG, "activateSdk failed", t);
                return false;
            }
            final boolean ready;
            try {
                ready = MetaActivationManager.getActivationStatus();
            } catch (Throwable t) {
                Log.w(TAG, "getActivationStatus failed", t);
                return false;
            }
            if (!ready) {
                // The core keeps a human-readable reason; it is the only diagnostic
                // we get from a closed-source licence check, so always surface it.
                Log.w(TAG, "core not prepared yet: " + statusMessage());
            }
            return ready;
        }
    }

    /** {@code true} once the core's native side reports itself prepared. */
    public static boolean isPrepared() {
        try {
            return MetaActivationManager.getActivationStatus();
        } catch (Throwable t) {
            Log.w(TAG, "getActivationStatus failed", t);
            return false;
        }
    }

    /** The core's own explanation of the current activation state (may be empty). */
    public static String statusMessage() {
        try {
            String message = MetaActivationManager.getActivationMessage();
            return message == null ? "" : message;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Activates only when the core is not prepared yet — the common case. */
    public static boolean ensureActivated() {
        if (isPrepared()) {
            return true;
        }
        return activate();
    }
}
