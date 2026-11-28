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

## Java surface: three classes plus a `ui` package
- `com.zyn.bs.BoxApplication` — Application: attachBaseContext → activateSdk → doCreate →
  lifecycle callback that loads the payload in the guest process. Payload name is the single
  constant `TARGET_LIB` (`zyn-bs`); change that one string to swap payloads. It is staged as
  `<filesDir>/loader/lib<TARGET_LIB>.so` (reference host behaviour) with a packaged-APK fallback,
  and loaded only for the game's **main** process (`packageName == processName`).
- `com.zyn.bs.LauncherActivity` — the sandbox flow only (resolve / install / launch). All UI
  lives in `com.zyn.bs.ui`.
- `com.zyn.bs.Wallpaper` — the launcher wallpaper, compiled in as **raw bytes** (a 1080x1920
  JPEG, 313 KB) split across 63 `cNN()` slice methods. No `assets/`, no decryption: `decode(w, h)`
  concatenates the slices once and returns a downsampled `Bitmap`. The slices exist because a
  single method caps at 64 KB of bytecode, so never merge them into one array literal.
  Regenerate with `tmp/gen_wallpaper.py` (needs the source image at the workspace root).
- `com.zyn.bs.ui` — `Theme` (tokens, drawables, text), `Motion` (every animation helper),
  `LauncherView` (the screen), `IconOrbit`, `PulseDots`, `SweepBar`. Restyling starts in `Theme`:
  colours, gradients and radii all come from there.
- UI contract: `setListener`, `setPrimaryEnabled`, `bindGameIcon`, `setGameTitle`, `showReady`,
  `showNotInstalled`, `showBusy`, `showRunning`, `showError`.
- Animations only touch alpha/scale/translation/rotation and are gated by `Motion.dur` /
  `Motion.loopsEnabled`, which honour the system animator-duration setting.
- There is **no overflow menu**: the primary button is the only control. The stop / clear-data /
  GMS / accounts / secure-window helpers went away with it — re-add behind a gesture or a
  settings screen if they are wanted again.

## Target
- One game only: `com.netease.newspike` (`TARGET_PACKAGE` in both classes).
  No game picker. **No OBB logic at all.**

## Build
- `app/src/main/jni` is the payload's native code only — do not read or modify it.
- CI: `.github/workflows/android-build.yml` builds with Gradle + ndk-build (`assembleDebug`),
  fingerprints `app/libs/elite.aar` and checks `lib/armeabi-v7a/libzyn-bs.so` in the APK.
- No Android SDK in the sandbox: only syntax-check Java (`javac` with missing deps filtered),
  never expect a full local build.
