package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.hello.sandbox.SandBoxCore;
import com.hello.sandbox.app.configuration.ClientConfiguration;

/**
 * Host application — attaches the sandbox core before anything else runs.
 *
 * <p>Two calls, in this order, and nothing else:</p>
 * <ol>
 *   <li>{@link SandBoxCore#doAttachBaseContext} in {@code attachBaseContext},
 *       before the rest of the process is set up, so the core can unseal the
 *       hidden-API reflection, pick the process type and install its hooks;</li>
 *   <li>{@link SandBoxCore#doCreate} in {@code onCreate}, which finishes the
 *       content-provider and black-manager wiring.</li>
 * </ol>
 *
 * <p>There is deliberately no activation call: the core is a licence-free build
 * and activates itself inside {@code doAttachBaseContext}. The old
 * {@code CoreActivation} helper existed only to present a key to a remote
 * server, and both are gone.</p>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            SandBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isHideRoot() {
                    return true;
                }

                // We never want a background daemon process: it only adds another
                // process to keep alive and this loader launches on demand.
                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }
            });
            // Bring the core's services up now, on a background thread, so the package
            // scan / user / storage / jar-extraction work overlaps with the rest of
            // process start-up instead of blocking the first frame. Core calls made
            // meanwhile simply wait for it (BlackBoxSystem.startup() blocks concurrent
            // callers), so nothing can observe a half-initialised core.
            warmCoreAsync();
        } catch (Throwable t) {
            // A broken core must not make the loader unlaunchable — the UI can
            // still show the game and explain the failure.
            Log.e(TAG, "core attach failed", t);
        }
    }

    /**
     * Starts the sandbox core on a background thread.
     *
     * <p>{@code BlackBoxSystem.startup()} registers every system service and does the initial
     * package scan, user read, storage set-up and jar extraction. Previously that all ran
     * synchronously on the main thread on the first core call, i.e. inside {@code onCreate},
     * which delayed the first frame. Running it here lets it overlap with the remainder of
     * process set-up.
     *
     * <p>Deliberately fire-and-forget: a failure is non-fatal because the first real core call
     * retries the same work, and the blocking guard in {@code startup()} keeps concurrent
     * callers safe.
     */
    private void warmCoreAsync() {
        Thread warm = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SandBoxCore.get().warmUp();
                } catch (Throwable t) {
                    Log.w(TAG, "core warm-up failed; it will retry on first use", t);
                }
            }
        }, "sandbox-warmup");
        warm.setDaemon(true);
        warm.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            SandBoxCore.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "core create failed", t);
        }
    }
}
