package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/**
 * Host application — attaches the sandbox core before anything else runs.
 *
 * <p>The engine is BlackBox Reborn ({@code top.niunaijun.blackbox}), which has a
 * slightly wider boot contract than the previous core. The order below is the
 * one its own host app uses and must be kept:</p>
 *
 * <ol>
 *   <li>{@link BlackBoxCore#onBeforeMainApplicationAttach} — lets callbacks see
 *       the host Application before the core rewires it;</li>
 *   <li>{@link BlackBoxCore#doAttachBaseContext} — unseals the hidden-API
 *       reflection, picks the process type and installs the hooks;</li>
 *   <li>{@link BlackBoxCore#onAfterMainApplicationAttach} — the matching
 *       "after" notification;</li>
 *   <li>{@link BlackBoxCore#doCreate} in {@code onCreate} — finishes the
 *       content-provider and service wiring.</li>
 * </ol>
 *
 * <p>There is deliberately no activation call: the core carries no licence gate
 * and activates itself inside {@code doAttachBaseContext}.</p>
 *
 * <p>There is also deliberately <em>no</em> background warm-up. An earlier
 * version kicked off {@code BlackBoxSystem.startup()} on its own thread to
 * overlap the package scan with process start-up, but that raced the core's own
 * initialisation and made a tap-to-launch return immediately instead of
 * starting the game. The core initialises itself on first use; nothing here may
 * start it early.</p>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base);

            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
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

                // No sandbox launcher/UI inside virtual apps.
                @Override
                public boolean isEnableLauncherActivity() {
                    return false;
                }
            });

            BlackBoxCore.get().onAfterMainApplicationAttach(this, base);
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
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "core create failed", t);
        }
    }
}
