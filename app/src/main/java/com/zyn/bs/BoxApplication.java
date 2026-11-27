package com.zyn.bs;

import android.app.Application;

/**
 * Host application — deliberately empty.
 *
 * <p>The Mundo core bootstraps itself. Its AAR ships its own manifest entries
 * ({@code MundoSupervisorProvider} and a pool of {@code :stubN} processes) which
 * the manifest merger folds into this app, and those ContentProviders are
 * instantiated before {@link #onCreate()} runs. That is why the reference app
 * never calls an "attach"/"create" method, and why this class no longer needs
 * the old bcore lifecycle calls
 * ({@code BlackBoxCore.doAttachBaseContext} / {@code doCreate} and the
 * {@code ClientConfiguration} subclass that fed them) — that API does not exist
 * in Mundo at all.</p>
 *
 * <p><b>Licensing is intentionally not used.</b> {@code
 * MetaActivationManager.activateSdk(String)} only forwards a key to the core's
 * binder service and reports status through {@code getActivationStatus()} /
 * {@code getActivationMessage()}; every capability this loader needs
 * ({@code MetaActivityManager}, {@code MetaApplicationInstaller},
 * {@code MetaPackageManager}, {@code MetaStorageManager}) is a public static
 * entry point that is reachable without it, so the call is skipped entirely.</p>
 */
public class BoxApplication extends Application {
}
