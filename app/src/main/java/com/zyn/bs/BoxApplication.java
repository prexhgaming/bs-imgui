package com.zyn.bs;

import android.app.Application;

/**
 * Host application.
 *
 * <p>The core mostly bootstraps itself: its AAR ships its own manifest entries
 * ({@code MundoSupervisorProvider} and a pool of {@code :stubN} processes) which
 * the manifest merger folds into this app, and those ContentProviders are
 * instantiated before {@link #onCreate()} runs. That is why there is no
 * "attach"/"create" method to call, and why the old bcore lifecycle calls
 * ({@code BlackBoxCore.doAttachBaseContext} / {@code doCreate} plus the
 * {@code ClientConfiguration} subclass that fed them) are gone — that API does
 * not exist in Mundo.</p>
 *
 * <p><b>Activation is required, contrary to what this class used to claim.</b>
 * Booting the core is not enough; until the licence is presented the core's
 * native side reports "not prepared" and blocks every operation. So this is
 * where {@link CoreActivation} is kicked off, exactly as the reference app did
 * it, and {@link LaunchController} retries it before each launch in case the
 * device was offline at boot.</p>
 */
public class BoxApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Cheap and idempotent: it re-syncs the core's status if it is already up.
        CoreActivation.activate();
    }
}
