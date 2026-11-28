# bs-imgui — working notes

## Engine (BlackBox / "Bcore")
- The sandbox engine is BlackBox, consumed as a plain **local AAR**:
  `app/libs/Bcore-release.aar` (declared in `app/build.gradle` as
  `fileTree(dir: "libs", includes: ["*aar"])`). No engine Gradle module.
- Host entry points used: `top.niunaijun.blackbox.BlackBoxCore.get()` —
  `doAttachBaseContext(Context, ClientConfiguration)`, `doCreate()`,
  `isInstalled(pkg, userId)`, `installPackageAsUser(pkg, userId)`, `launchApk(pkg, userId)`,
  `isBlackProcess()`.
- `ClientConfiguration` overrides that matter: `getHostPackageName()` (abstract),
  `isHideRoot()`, `isEnableDaemonService()`, `isDisableFlagSecure()`,
  `requestInstallPackage(File, int)`.
- This engine has **no licence/SDK activation** — no key, no activation call. Do not add one.
- `targetSdk` is pinned to **28** on purpose: the engine reflects pre-29 platform APIs.
  `lint.disable 'ExpiredTargetSdkVersion'` keeps that from failing the build.

## Crash handler
- `com.zyn.bs.CrashHandler` writes uncaught exceptions to `Download/bs-crash-logs/`, falling back
  to `Documents/bs-crash-logs/`, then the app-specific external dir, then internal `files/`.
  The write is synchronous + fsync'ed because the process dies right after.
- Ordering is the whole point: `BoxApplication`'s **static initializer** installs it (runs at class
  load, before `attachBaseContext`, before the engine), and `CrashHandler.attach(base)` is the
  **first statement** of `attachBaseContext()`. `CrashHandler.reassert()` runs at the end of
  `onCreate()` in case the engine replaced the default handler while booting.
- It never swallows the crash: the previously installed handler is always called afterwards.
- Public Download/Documents needs `WRITE_EXTERNAL_STORAGE`; `LauncherActivity` requests it at
  startup. Without the grant the handler silently uses the app dirs — that is expected, not a bug.
- Native (SIGSEGV inside a `.so`) crashes are **not** caught by this: Java handlers cannot run
  after a native abort. Needs a separate tombstone approach if ever required.

## Java surface: three classes plus a `ui` package
- `com.zyn.bs.BoxApplication` — Application: crash handler → engine attach → `doCreate()` →
  payload load in sandboxed processes. Payload name is the single constant `TARGET_LIB`
  (`zyn-bs`); change that one string to swap payloads. Staged as
  `<filesDir>/loader/lib<TARGET_LIB>.so`, with a packaged-APK fallback.
- `com.zyn.bs.LauncherActivity` — the sandbox flow only (resolve / install / launch).
- `com.zyn.bs.Wallpaper` — the launcher wallpaper, compiled in as **raw bytes** (a 1080x1920
  JPEG, 313 KB) split across 63 `cNN()` slice methods. No `assets/`, no decryption:
  `decode(w, h)` concatenates the slices once and returns a downsampled `Bitmap`. The slices exist
  because a single method caps at 64 KB of bytecode — never merge them into one array literal.
  Regenerate with `tmp/gen_wallpaper.py` (needs the source image at the workspace root).
- `com.zyn.bs.ui` — `Theme` (tokens, drawables, text), `Motion` (every animation helper),
  `LauncherView` (the screen), `IconOrbit`, `PulseDots`, `SweepBar`. Restyling starts in `Theme`.
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
  fingerprints `app/libs/Bcore-release.aar` and checks `lib/armeabi-v7a/libzyn-bs.so` in the APK.
- No Android SDK in the sandbox: only syntax-check Java (`javac` with missing deps filtered),
  never expect a full local build. `CrashHandler` is unit-tested against stubs in `tmp/`.
