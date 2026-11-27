# R8 / ProGuard rules for the loader.
#
# Deliberately NO "-keep class com.zyn.bs.**": our own code is meant to be
# shrunk and renamed. Components declared in AndroidManifest.xml (Application,
# Activity, services) are kept automatically by AGP, so they need no rule here.

# ---------------------------------------------------------------------------
# Mundo core (the BlackBox fork our AAR is built from).
#   net_62v.external.*  — the public facade this app calls directly.
#   top.bienvenido.*    — the engine internals, resolved by name from a separate
#                         class loader at runtime, so renaming them breaks it.
# Both trees are kept; our own code is not.
# ---------------------------------------------------------------------------
-keep class net_62v.** { *; }
-keep class top.bienvenido.** { *; }
-dontwarn net_62v.**
-dontwarn top.bienvenido.**

# mundo-release.aar also ships its own consumer rules, which already
# "-dontwarn *" and keep the native-method holders plus MundoReflectionStub$**.

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
-dontwarn kotlin.**
