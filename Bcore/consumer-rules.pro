# Consumer rules for :Bcore — applied to every app that depends on it.
#
# The core resolves most of its own classes by NAME at runtime: separate class
# loaders for guest processes, BlackReflection stubs, AIDL proxies and the
# android-mirror stubs under the `android.*` / `mirror.*` packages. Renaming any
# of them breaks the sandbox in ways that only show up at launch, so nothing
# here may be shrunk or obfuscated.

-keep class com.hello.sandbox.** { *; }
-keep class top.niunaijun.** { *; }
-keep class black.** { *; }
-keep class mirror.** { *; }
-keep class android.** { *; }
-keep class com.android.** { *; }

# Native methods are bound by their Java name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Reflective access relies on annotations and generic signatures surviving.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Optional / platform classes the core probes for but does not require.
-dontwarn com.hello.sandbox.**
-dontwarn top.niunaijun.**
-dontwarn black.**
-dontwarn mirror.**
-dontwarn android.**
-dontwarn com.android.**
-dontwarn org.lsposed.**
-dontwarn kotlin.**
