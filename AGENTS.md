# bs-imgui — working notes

## Engine (BlackBox / "Bcore")
- The core is **vendored source**, not an AAR: `bcore/` is a single Gradle module
  (`include ':bcore'`, `implementation project(':bcore')`). Upstream
  (github.com/ALEX5402/NewBlackbox) splits it into `Bcore` + `black-reflection` + `compiler`;
  here all three live in one folder:
  - `bcore/src/main/java` = upstream `Bcore/src/main/java` **plus** `black-reflection`'s 19
    plain-Java files merged in (same `top.niunaijun.blackreflection` package).
  - `bcore/libs/blackreflection-compiler.jar` = the prebuilt annotation processor. An annotation
    processor runs on the host JVM at compile time, so it can **never** be merged into the same
    module's source set — that jar is the only piece that stays separate, and it is prebuilt
    because it needs javapoet. Rebuild it from upstream `compiler/` with `-proc:none` against
    `javapoet` + `auto-service-annotations`, then add the
    `META-INF/services/javax.annotation.processing.Processor` entry.
- **ABI split:** the core builds every ABI in `bcore/src/main/cpp/Application.mk`
  (armeabi-v7a + arm64-v8a). Narrowing is the app's job — `app/build.gradle` has
  `abiFilters 'armeabi-v7a'` because the payload and the target game are 32-bit. Do not add
  abiFilters to the library.
- Host entry points used: `top.niunaijun.blackbox.BlackBoxCore.get()` —
  `doAttachBaseContext(Context, ClientConfiguration)`, `doCreate()`,
  `isInstalled(pkg, userId)`, `installPackageAsUser(pkg, userId)`, `launchApk(pkg, userId)`,
  `isBlackProcess()`, and GMS: `isSupportGms()`, `isInstallGms(userId)`,
  `installGms(userId)` → `InstallResult`, `uninstallGms(userId)`.
- `ClientConfiguration` overrides: `getHostPackageName()` (abstract), `isHideRoot()`,
  `isEnableDaemonService()`, `isDisableFlagSecure()`, `requestInstallPackage(File, int)`.
  There is **no `isHideXposed()`** in this core — annotating one with `@Override` fails the build.
- This core has **no licence/SDK activation** — no key, no activation call. Do not add one.
- `targetSdk` is pinned to **28** on purpose: the core reflects pre-29 platform APIs.
  `lint.disable 'ExpiredTargetSdkVersion'` keeps that from failing the build.

## Startup flow (setup happens on open, not on tap)
- `LauncherActivity.runSetup()` runs one background pass at startup: find the game on the device
  → clone it into the sandbox (`installPackageAsUser`, no-op if already cloned) → install GMS
  when `isSupportGms()` and missing → ready.
- Game absent from the device → state `NEEDS_STORE`, and the button becomes
  `GET IT ON PLAY STORE` (`market://details?id=<pkg>`, web listing as fallback). Returning to the
  app re-runs the setup pass.
- The primary button is **disabled for the whole pass** and only live in a terminal state
  (`READY` / `NEEDS_STORE` / `ERROR`, where ERROR means "retry"). That is what keeps a launch tap
  from landing in a half-prepared sandbox.

## Crash handler
- `com.zyn.bs.CrashHandler` writes uncaught exceptions to `Download/bs-crash-logs/`, falling back
  to `Documents/bs-crash-logs/`, then the app-specific external dir, then internal `files/`.
  The write is synchronous + fsync'ed because the process dies right after.
- Ordering is the whole point: `BoxApplication`'s **static initializer** installs it (runs at class
  load, before `attachBaseContext`, before the engine), and `CrashHandler.attach(base)` is the
  **first statement** of `attachBaseContext()`. `CrashHandler.reassert()` runs at the end of
  `onCreate()` in case the core replaced the default handler while booting.
- It never swallows the crash: the previously installed handler is always called afterwards.
- Public Download/Documents needs `WRITE_EXTERNAL_STORAGE`; `LauncherActivity` requests it at
  startup. Without the grant the handler silently uses the app dirs — that is expected, not a bug.
- Native (SIGSEGV inside a `.so`) crashes are **not** caught by this: Java handlers cannot run
  after a native abort. Needs a separate tombstone approach if ever required.

## Java surface
- `com.zyn.bs.BoxApplication` — Application: crash handler → core attach → `doCreate()` →
  payload load in sandboxed processes. Payload name is the single constant `TARGET_LIB`
  (`zyn-bs`); change that one string to swap payloads. Staged as
  `<filesDir>/loader/lib<TARGET_LIB>.so`, with a packaged-APK fallback.
- `com.zyn.bs.LauncherActivity` — the sandbox flow + the startup setup pass.
- `com.zyn.bs.Wallpaper` — the launcher wallpaper, compiled in as **raw bytes** (a 1080x1920
  JPEG, 313 KB) split across 63 `cNN()` slice methods. No `assets/`, no decryption:
  `decode(w, h)` concatenates the slices once and returns a downsampled `Bitmap`. The slices exist
  because a single method caps at 64 KB of bytecode — never merge them into one array literal.
  Regenerate with `tmp/gen_wallpaper.py` (needs the source image at the workspace root).
- `com.zyn.bs.ui` — `Theme` (tokens, drawables, text), `Motion` (every animation helper),
  `LauncherView` (the screen), `IconOrbit`, `PulseDots`, `SweepBar`. Restyling starts in `Theme`.
- UI contract: `setListener`, `setPrimaryEnabled`, `bindGameIcon`, `setGameTitle`, `showChecking`,
  `showGmsBusy`, `showNeedsStore`, `showReady`, `showBusy`, `showRunning`, `showError`.
- Animations only touch alpha/scale/translation/rotation and are gated by `Motion.dur` /
  `Motion.loopsEnabled`, which honour the system animator-duration setting.
- There is **no overflow menu**: the primary button is the only control.

## Target
- One game only: `com.netease.newspike` (`TARGET_PACKAGE` in both classes).
  No game picker. **No OBB logic at all.**

## Build
- `app/src/main/jni` is the payload's native code only — do not read or modify it.
- Two native builds: payload (`app/src/main/jni`) and core (`bcore/src/main/cpp`), both with the
  NDK pinned in the root `build.gradle` (needs r26+ for `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- CI: `.github/workflows/android-build.yml` builds with Gradle + ndk-build (`assembleDebug`),
  fingerprints `bcore/src`, and asserts **both** `lib/armeabi-v7a/libzyn-bs.so` and
  `lib/armeabi-v7a/libblackbox.so` are in the APK.
- CI only runs on paths listed in that workflow's `on.push.paths`. It is not a catch-all: a
  commit touching nothing on that list is silently skipped (no failed run, just no run). When
  adding a top-level module or directory, add it there too — `bcore/**` was missing once and
  an AGP fix sat unbuilt.
- No Android SDK in the sandbox: only syntax-check Java (`javac` with missing deps filtered),
  never expect a full local build. `CrashHandler` is unit-tested against stubs in `tmp/`.
