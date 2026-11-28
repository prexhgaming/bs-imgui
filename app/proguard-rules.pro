# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.
#
# The BlackBox core is built from the vendored bcore/ module and supplies its own
# consumer rules (bcore/consumer-rules.pro), which AGP merges automatically. The
# explicit keeps below are belt-and-braces for the classes it reaches by name
# (its reflection mirrors, the generated *_Proxy classes and the proxy pools).

# ---------------------------------------------------------------------------
# JNI — native methods are resolved by their Java name, which must not change.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# BlackBox core (bcore/ module)
# ---------------------------------------------------------------------------
# The core resolves mirror classes and the generated reflection proxies by name
# at runtime, so the whole surface is pinned here as well. There is no
# licence/activation class in this core — nothing of that kind to keep.
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
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
