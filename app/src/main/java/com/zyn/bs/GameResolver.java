package com.zyn.bs;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * Resolves {@link GameConfig#PACKAGE_NAME} into a {@link GameInfo}: the real
 * display name and launcher icon, straight from the device.
 *
 * <p>Requires {@code QUERY_ALL_PACKAGES} (declared in the manifest) so the
 * package is visible on Android 11+.</p>
 */
public final class GameResolver {

    private static final String TAG = "GameResolver";

    private GameResolver() {
    }

    /**
     * Blocking lookup — call from a worker thread, never the main thread.
     */
    public static GameInfo resolve(Context context) {
        final String packageName = GameConfig.PACKAGE_NAME;
        final PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence rawLabel = pm.getApplicationLabel(info);
            String label = rawLabel != null && rawLabel.length() > 0
                    ? rawLabel.toString()
                    : packageName;
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(info);
            } catch (Throwable t) {
                Log.w(TAG, "icon lookup failed for " + packageName, t);
                icon = null;
            }
            return new GameInfo(packageName, label, icon, true);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, packageName + " is not installed on this device");
            return GameInfo.missing(packageName);
        } catch (Throwable t) {
            Log.e(TAG, "failed to resolve " + packageName, t);
            return GameInfo.missing(packageName);
        }
    }
}
