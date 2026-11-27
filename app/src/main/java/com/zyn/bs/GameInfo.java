package com.zyn.bs;

import android.graphics.drawable.Drawable;

/**
 * Immutable snapshot of the target game as resolved from the device.
 *
 * <p>{@link #label} and {@link #icon} come from {@code PackageManager}, never
 * from a hardcoded string. When the package is absent they fall back to the
 * package name itself and a null icon, and {@link #installed} is false.</p>
 */
public final class GameInfo {

    public final String packageName;
    public final String label;
    public final Drawable icon;
    public final boolean installed;

    public GameInfo(String packageName, String label, Drawable icon, boolean installed) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.installed = installed;
    }

    /** Used when the package is not present on the device. */
    public static GameInfo missing(String packageName) {
        return new GameInfo(packageName, packageName, null, false);
    }

    @Override
    public String toString() {
        return "GameInfo{" + packageName + ", label=" + label + ", installed=" + installed + '}';
    }
}
