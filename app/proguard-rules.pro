# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.
#
# The SpaceCore engine keeps ITSELF through the consumer ProGuard rules inside
# the AAR, which AGP merges into this build automatically because the app depends
# on :Spacecore. The explicit keeps further down are belt-and-braces for the
# classes the engine reaches by name.

# ---------------------------------------------------------------------------
# JNI — native methods are resolved by their Java name, which must not change.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# SpaceCore engine (com.fvbox:flib)
# ---------------------------------------------------------------------------
# The AAR's consumer rules already keep FCore, the proxy pools, the
# blackreflection mirrors and MMKV. They also enable -repackageclasses, which
# renames everything that is not explicitly kept — so the engine's name-based
# lookups are pinned here as well. A future AAR swap then cannot silently lose
# them.
-keep class com.fvbox.lib.** { *; }
-keep class com.fvbox.mirror.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep class org.lsposed.** { *; }

# ---------------------------------------------------------------------------
# Harden against reversing
# ---------------------------------------------------------------------------
# Original source file names would otherwise survive as debug metadata.
-renamesourcefileattribute SourceFile

# Keep annotations (needed by some libs) but not the extra debug attributes.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Silence warnings for optional/platform classes the dependencies probe for.
-dontwarn org.lsposed.**
-dontwarn kotlin.**
