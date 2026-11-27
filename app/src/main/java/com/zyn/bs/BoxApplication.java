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

                @Override
                public boolean isHideXposed() {
                    return true;
                }

                // We never want a background daemon process: it only adds another
                // process to keep alive and this loader launches on demand.
                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }
            });
        } catch (Throwable t) {
            // A broken core must not make the loader unlaunchable — the UI can
            // still show the game and explain the failure.
            Log.e(TAG, "core attach failed", t);
        }
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
