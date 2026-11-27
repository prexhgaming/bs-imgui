# R8/ProGuard rules shipped by :Bcore and merged into the host app's build.
#
# The engine resolves a large part of itself BY NAME at runtime (AIDL service
# names, the :p0..:p99 process pool, the BlackReflection mirrors, JNI entry
# points), so these paths must never be renamed, shrunk or stripped. Everything
# NOT listed here — including the host app's own classes — stays shrinkable.

# ---------------------------------------------------------------------------
# The engine
# ---------------------------------------------------------------------------
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }

# ---------------------------------------------------------------------------
# The platform mirrors and stubs
# ---------------------------------------------------------------------------
# black.** holds the @BClass-annotated mirror declarations, mirror.** is what
# BlackReflection generates from them, and android.** / com.android.** are the
# stubs that shadow the real framework classes.
-keep class black.** { *; }
-keep class mirror.** { *; }
-keep class android.** { *; }
-keep class com.android.** { *; }

# ---------------------------------------------------------------------------
# BlackReflection
# ---------------------------------------------------------------------------
# The mirrors are read through their annotations at runtime, so the annotation
# attribute itself must survive too.
-keepattributes *Annotation*
-keep @top.niunaijun.blackreflection.annotation.BClass class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassName class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * {*;}
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
}

# ---------------------------------------------------------------------------
# JNI
# ---------------------------------------------------------------------------
# Native methods are looked up by their Java name from libblackbox.so.
-keepclasseswithmembernames class * {
    native <methods>;
}
