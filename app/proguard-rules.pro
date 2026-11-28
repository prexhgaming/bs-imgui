# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.
#
# The BlackBox engine keeps ITSELF through the consumer ProGuard rules inside
# app/libs/Bcore-release.aar, which AGP merges into this build automatically. The
# explicit keeps further down are belt-and-braces for the classes the engine
# reaches by name (its reflection mirrors and the proxy pools).

# ---------------------------------------------------------------------------
# JNI — native methods are resolved by their Java name, which must not change.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# BlackBox engine (app/libs/Bcore-release.aar)
# ---------------------------------------------------------------------------
# The AAR ships consumer rules, but the engine also resolves mirror classes by
# name at runtime, so the whole surface is pinned here as well. There is no
# licence/activation class in this engine — nothing of that kind to keep.
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class mirror.** { *; }
-keep class black.** { *; }
-keep class com.android.** { *; }
-keep class org.lsposed.lsparanoid.** { *; }

# ---------------------------------------------------------------------------
# Harden against reversing
# ---------------------------------------------------------------------------
# Original source file names would otherwise survive as debug metadata.
-renamesourcefileattribute SourceFile

# Keep annotations (needed by some libs) but not the extra debug attributes.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ---------------------------------------------------------------------------
# The engine reflects into android.* and its own mirrors, so those framework
# classes are resolved by name at runtime.
# ---------------------------------------------------------------------------
-keep class android.** { *; }
