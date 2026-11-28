# bs-imgui — working notes

## Engine (elite, not Spacecore)
- The sandbox engine is `elite`, consumed as a plain **local AAR**: `app/libs/elite.aar`
  (declared in `app/build.gradle` as `fileTree(dir: "libs", includes: ["*aar"])`).
  There is no engine Gradle module and no `include` for it in `settings.gradle`.
- Host entry points used: `com.elite.EliteInstaller.get()` —
  `doAttachBaseContext(Context, ClientConfiguration)`, `doCreate()`,
  `addAppLifecycleCallback(...)`, `isInstalled/installPackageAsUser/launchApk/stopPackage/clearPackage`,
  `isInstallGms/installGms`, `getBAccountManager()`.
- SDK activation is automatic in `BoxApplication.onCreate()` via
  `net_62v.external.MetaActivationManager.activateSdk(BoxApplication.SDK_KEY)`.
  The key is compiled in — never prompt the user for it.

## Java surface is exactly two classes (on purpose)
- `com.zyn.bs.BoxApplication` — Application: attachBaseContext → activateSdk → doCreate →
  lifecycle callback that loads the payload (`libzyn-bs.so`) in the guest process.
- `com.zyn.bs.LauncherActivity` — the launcher UI **and** the asset crypto engine
  (`a7f3c9.bin` → wallpaper), plus install/launch/clear/GMS actions.
  Keep the UI and its `R.string.*` keys intact when editing; do not split it back into
  `ui/` + `crypto/` packages unless asked.

## Target
- One game only: `com.netease.newspike` (`TARGET_PACKAGE` in both classes).
  No game picker. **No OBB logic at all.**

## Build
- `app/src/main/jni` is the payload's native code only — do not read or modify it.
- CI: `.github/workflows/android-build.yml` builds with Gradle + ndk-build (`assembleDebug`),
  fingerprints `app/libs/elite.aar` and checks `lib/armeabi-v7a/libzyn-bs.so` in the APK.
- No Android SDK in the sandbox: only syntax-check Java (`javac` with missing deps filtered),
  never expect a full local build.
