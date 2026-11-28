# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.
#
# The elite engine keeps ITSELF through the consumer ProGuard rules inside
# app/libs/elite.aar, which AGP merges into this build automatically. The
# explicit keeps further down are belt-and-braces for the classes the engine
# reaches by name (its proxy pools and the SDK activation entry point).

# ---------------------------------------------------------------------------
# JNI — native methods are resolved by their Java name, which must not change.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# elite engine (app/libs/elite.aar)
# ---------------------------------------------------------------------------
# The AAR ships no consumer rules, so the classes it looks up by name — the
# installer entry point, the proxy activity/provider/service pools and the SDK
# activation manager — are pinned here.
-keep class com.elite.** { *; }
-keep class net_62v.** { *; }
-keep class android.Meta.** { *; }
-keep class android.MetaCore.** { *; }
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

# Silence warnings for optional/platform classes the dependencies probe for.
-dontwarn org.lsposed.**
-dontwarn kotlin.**
