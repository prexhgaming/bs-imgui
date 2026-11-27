# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.

# ---------------------------------------------------------------------------
# bcore (BlackBox) — a reflective virtualisation engine.
# It resolves its own classes by name from a separate class loader at runtime,
# so renaming them breaks it. Kept; our code is not.
# ---------------------------------------------------------------------------
-keep class top.niunaijun.blackbox.** { *; }
-dontwarn top.niunaijun.blackbox.**

# The blackreflection library is not bundled in the AAR; bcore's generated
# reflection stubs reference it. Without these rules R8 fails the release build
# with "Missing class top.niunaijun.blackreflection...".
-dontwarn top.niunaijun.blackreflection.**
-dontwarn black.**

# ---------------------------------------------------------------------------
# JNI — native methods are resolved by their Java name, which must not change.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

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
-dontwarn com.topjohnwu.superuser.**
-dontwarn kotlin.**
