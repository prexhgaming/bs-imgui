package com.zyn.bs;

import android.net.Uri;

/**
 * The single place that decides <em>which</em> game this loader targets.
 *
 * <p>Nothing else in the app hardcodes a title or package: the display name and
 * icon are read from the device's {@code PackageManager} at runtime (see
 * {@link GameResolver}), so changing {@link #PACKAGE_NAME} is all it takes to
 * point the loader at a different game.</p>
 */
public final class GameConfig {

    /**
     * Target game package name.
     *
     * <p>Change this to load a different title. If the package is not installed
     * the UI shows an "Install Game" action that opens the Play Store listing
     * for this exact package.</p>
     */
    public static final String PACKAGE_NAME = "com.netease.newspike";

    private GameConfig() {
    }

    /** {@code market://} deep link to this package's Play Store listing. */
    public static Uri playStoreUri() {
        return Uri.parse("market://details?id=" + PACKAGE_NAME);
    }

    /**
     * Web fallback for devices without the Play Store app. The Play Store
     * website redirects into the app when it is available.
     */
    public static Uri playStoreWebUri() {
        return Uri.parse("https://play.google.com/store/apps/details?id=" + PACKAGE_NAME);
    }
}
