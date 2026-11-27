package com.zyn.bs;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

/**
 * Initialises bcore (BlackBox) exactly like the reference loader did:
 * {@code doAttachBaseContext} in {@link #attachBaseContext(Context)} and
 * {@code doCreate} in {@link #onCreate()}.
 *
 * <p>The virtualisation engine is what lets the target game run inside this
 * app's sandbox, so this ordering must not change.</p>
 */
public class BoxApplication extends Application {

    private static final String TAG = "BoxApplication";

    @Override
    protected void attachBaseContext(Context base) {
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
                public boolean isHideXposed() {
                    return true;
                }

                @Override
                public boolean isEnableDaemonService() {
                    return false;
                }

                @Override
                public boolean requestInstallPackage(File file) {
                    // Same behaviour as the reference: report the archive but
                    // let bcore perform the install itself.
                    PackageInfo ignored = base.getPackageManager()
                            .getPackageArchiveInfo(file.getAbsolutePath(), 0);
                    return false;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "doAttachBaseContext failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BlackBoxCore.get().doCreate();
    }
}
