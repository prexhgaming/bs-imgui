# AGENTS.md

## Remote & pushing
- Remote `origin` is HTTPS: `https://github.com/prexhgaming/bs-imgui.git` (SSH may not be configured in the sandbox).
- Auth comes from the `gh` CLI: global `credential.helper = !/usr/bin/gh auth git-credential`. Plain `git push` works; no tokens needed in commands.
- Repo identity (local config): `prexhgaming <340324619+prexhgaming@users.noreply.github.com>`.
- **Moving a clone into `/workspace` breaks the object store.** The sandbox filesystem turns hardlinks
  into symlinks, so after `git clone /tmp/... && mv .git /workspace/` the files in
  `.git/objects/pack/` are symlinks back into `/tmp` and every command dies with `fatal: bad object HEAD`.
  Copy the real `.l2s.tmp_*.0001` blobs over the `pack-*`/`*.idx` names (or clone straight into the
  workspace with `git init` + `git fetch`).

## Repo conventions
- Default branch is `main`.
- Visibility is **public** (switched from private to get free GitHub Actions minutes after the private quota ran out). Actions enabled with `allowed_actions: all`.
- Never commit secrets — the repo is publicly readable. Use Actions secrets instead (`.env*` is git-ignored).
- `.polydev/` (local agent tooling) and `bbref/` (extracted reference project) are git-ignored.

## Project layout
- Repo root **is** the Gradle source root (the app used to live in `loader/`, now flattened).
- `app/` — the loader app module (`com.zyn.bs`).
- `app/src/main/jni/` — the native payload, an **ndk-build** project (`Android.mk` + `Application.mk`).
- `Spacecore/` — the **sandbox engine** (SpaceCore), consumed as a **prebuilt AAR** inside a thin
  Gradle library module:
  - `repo/com/fvbox/flib/1.0.0/` — the vendored AAR (`flib-1.0.0.aar`, an unmodified copy of upstream's
    `app/libs/flib-release.aar`) plus a hand-written POM. `settings.gradle` registers this directory as
    a Maven repository, so AGP resolves `com.fvbox:flib:1.0.0` like any remote AAR and merges its
    manifest (~2500 proxy activities, the `:sc0`…`:scN` process pool, its providers and permissions),
    resources, jni libs and consumer ProGuard rules.
  - `src/main/AndroidManifest.xml` — deliberately empty. The engine's components come from the AAR; do
    not copy them here.
  - Engine package is `com.fvbox.lib`, public entry point `FCore`. Native libs are prebuilt inside the
    AAR: `libfcore.so` (the engine), `libshadowhook.so`, `libpine.so` and the xcrash libs, for
    arm64-v8a, armeabi-v7a, x86 and x86_64.
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

### Which engine this is
**SpaceCore** (https://github.com/FSpaceCore/SpaceCore), vendored as its prebuilt AAR. It replaced
BCORE-KERO-FixedSDK (BlackBox, built from source), which in turn replaced NewBlackbox-AutoImport and
BlackBox Reborn. Those three shared one API (`top.niunaijun.blackbox.BlackBoxCore`); SpaceCore does
**not** — its API is `com.fvbox.lib.FCore`, so an engine swap now means rewriting the loader-side calls
(see "Sandbox core — how we drive it"). What differs in this one:
- **No engine source.** Upstream publishes the sandbox only as `app/libs/flib-release.aar`. There is
  nothing to compile, patch or read as Java — behaviour questions are answered from the bytecode
  (`javap -p -c`, the AAR is unzipped in the sandbox) or from upstream's demo app in the same repo.
- **No CMake and no engine NDK.** `libfcore.so`, `libshadowhook.so`, `libpine.so` and the xcrash libs
  ship inside the AAR. Only the payload in `app/src/main/jni` is still built from source.
- **It shades its dependencies into `classes.jar`**: `top.niunaijun.blackreflection`,
  `org.lsposed.hiddenapibypass`, `top.canyie.pine`, `com.bytedance.shadowhook` and `xcrash`. The only
  real external deps are Kotlin (1.6.10), kotlinx-coroutines (1.5.2) and MMKV (1.2.10).
- **No `ClientConfiguration`.** There is no boot-time config object at all: init is one call, and
  per-package behaviour is expressed with `PackageRule` (see below).
- **No `installGms()` / `isInstallGms()`.** Google-services handling is internal to the engine; the old
  GMS pre-install step is gone and must not be re-added as a no-op.
- **`launchApk()` returns `void`**, not a boolean. Detect failure with
  `getLaunchIntentForPackage(pkg, userId) == null` — that is the call `launchApk` makes internally.
- **The user table is explicit.** `getUsers()` lists slots and `createUser(id)` adds one; slot 0 is not
  assumed to exist, so `LaunchController.ensureUser()` creates it when missing.
- **Guest processes are `:sc0`…`:scN` of our own APK** (BlackBox used `:p0`…`:p99`), so
  `System.loadLibrary("zyn-bs")` in a guest process still resolves against our APK's lib directory.
- **The AAR's manifest sets `android:debuggable="true"`.** `app/src/main/AndroidManifest.xml` pins
  `android:debuggable="${debuggable}"` and `app/build.gradle` sets that placeholder per build type, so
  release builds stay non-debuggable. Do not remove that pin.
- **No telemetry.** The AAR contains no HTTP endpoint at all (`strings` over `classes.jar` and every
  `.so`: only toolchain/source URLs). Nothing in the loader may send data off-device — keep it that way.

Why an AAR and not source: upstream does not publish the sandbox sources, only the AAR. Wrapping it in
a library module (instead of depending on the AAR straight from `app/`) keeps engine build details out
of `app/` and makes the manifest/consumer-rule merge happen exactly once.

### Launch path: call the core API, load the payload in the guest
The loader must not hand-roll any of this:
- **No worker thread for install/launch.** `LaunchController` runs the whole sequence on the calling
  thread; a bare `new Thread(...)` has no `Looper`, the engine's calls expect one, and the launch
  silently goes nowhere. There are also no artificial `postDelayed` delays. The sequence is
  `ensureUser()` → `applyRules()` → `isInstalled()` → `installPackageAsUser()` → `launchApk()`.
- **The payload is loaded in the GUEST process, not the loader.** `libzyn-bs.so` creates the game's
  lifecycle, so it is loaded from `BoxApplication.GuestPayloadLoader`
  (`ApplicationCallback.beforeCreateApplication`, filtered to `GameConfig.PACKAGE_NAME`) via a plain
  `System.loadLibrary("zyn-bs")` — the guest process is our own APK, so its native lib dir is already
  on the linker path. No absolute-path fallback, no dlopen, no host-side load.
- **The install runs on every launch, by package name.** `installPackageAsUser(pkg, userId)` resolves
  the system APK via `PackageManager` and installs it with `InstallOption.installBySystem()`
  (`FLAG_SYSTEM`), so the box references the existing system APK instead of copying it — cheap, no
  storage permission, no file picker. A stale sandbox entry from an earlier install (or from the
  previous engine, whose data layout is incompatible) can be present but broken; re-registering always
  leaves a valid entry. On a failed launch the entry is uninstalled and re-cloned once before giving up.

## Building
- **One native build left: ndk-build for the payload** (`app/src/main/jni/Android.mk`). The engine is a
  prebuilt AAR — there is no CMake project, no engine NDK and no `externalNativeBuild` for the engine.
- **One command builds everything:** `gradle assembleDebug` (CI builds debug — see "Minify, shrink and
  keep rules" for the R8 story) or `gradle assembleRelease`. Both build types are signed with the same
  release key, so either can update an existing install.
- Toolchain (keep `app`, `Spacecore` and CI in sync): **AGP 8.13.2**, Gradle **8.14.5** (CI's
  `GRADLE_VERSION`), `compileSdk 36`, `buildToolsVersion 36.1.0`, **Java 21** (JDK 21 in CI),
  `minSdk 24`, `targetSdk 28`. The engine AAR itself is compiled for Java 8, which Java 21 consumes.
- **One NDK**, from `rootProject.ext.ndkVersion` (`27.0.12077973`), used only by the payload.
  `engineNdkVersion` and `cmakeVersion` are gone from the root `ext` block — do not re-add them.
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3`; run `gradle wrapper` locally if you want one.
- **Nothing can be built in the agent sandbox**: it has JDK 8 only (the app needs Java 21), no Gradle,
  no Android SDK/NDK and no CMake. Compiling is CI's job — don't burn time on a local `gradle` run;
  edit sources, push, and read the workflow log.
- NDK **r26+** required for the payload (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- ABI split: the **payload** is `armeabi-v7a` only (32-bit game target). The **engine AAR** carries all
  four ABIs, but `app/build.gradle`'s `abiFilters` decides what is packaged, so the APK carries
  `armeabi-v7a` libs only.
- Native outputs inside the APK: `libzyn-bs.so` (payload, module `zyn-bs` + prebuilt `libdobby`) and
  `libfcore.so` (engine, straight from the AAR). CI asserts both are present.
- There is no AIDL build any more: the engine's AIDL is already compiled inside the AAR, so `:app` needs
  no `buildFeatures { aidl true }` and no `aidlPackagedList`.
- The payload's prebuilt static libs are **exceptions to `.gitignore`'s blanket `*.so` rules** and must
  stay committed: `app/src/main/jni/Dobby/libraries/**/*.a`. After touching `.gitignore`, check with
  `git check-ignore -v app/src/main/jni/Dobby/libraries/armeabi-v7a/libdobby.a`.
- Build outputs `app/src/main/jni/libs/`, `app/src/main/jni/obj/`, `dist/` and `*/build/` are git-ignored.

## Sandbox core — how we drive it
- API: `com.fvbox.lib.FCore`, a Kotlin singleton reached with `FCore.get()`, used from
  `BoxApplication` and `LaunchController`:
  - `init(Context, boolean)` — the boolean is the engine's **`isSamePkg`** flag. Pass `true`; that is
    what upstream's reference host app does for guests running inside the host's own APK processes.
  - `setApplicationCallback(ApplicationCallback)` — a **single** callback object (not a list).
    `beforeCreateApplication(pkg, processName, userId)` fires in the guest process right before the
    game's Application is created; that is where the payload is loaded. Note it does **not** hand over
    the guest `Application` instance (BlackBox's `AppLifecycleCallback` did).
  - `isInstalled(pkg, userId)`, `installPackageAsUser(pkg, userId)` → `InstallResult`
    (`getSuccess()` / `getMsg()` — getters, not public fields), `uninstallPackageAsUser(pkg, userId)`,
    `launchApk(pkg, userId)` → **void**, `getLaunchIntentForPackage(pkg, userId)` → `Intent` or null.
  - `getUsers()` / `createUser(id)` — the user table is explicit; slot 0 may not exist yet.
  - `addRule(PackageRule)` — per-package switches (this replaced the old engine's `ClientConfiguration`).
- **Boot contract — two calls, in this order**, both in `BoxApplication.attachBaseContext`:
  `FCore.get().init(this, true)` then `FCore.get().setApplicationCallback(...)`. Each block is wrapped
  in `catch (Throwable)` so a broken engine can't make the loader unlaunchable. There is no
  `doCreate()`/`doAttachBaseContext()`/`onBeforeMainApplicationAttach()` in this engine — do not
  reintroduce the old five-step BlackBox sequence.
- **There is deliberately no warm-up and no early startup.** The engine initialises itself on first use;
  starting it early raced the engine's own initialisation and made a tap-to-launch return immediately
  instead of starting the game.
- **`LaunchController.applyRules()` hides root** for the game (`PackageRule.Builder(pkg)
  .addUserId(0).isHideRoot(true)`), once per process, guarded by `catch (Throwable)`. That is the
  replacement for the old engine's global `isHideRoot()` override. It is never fatal: the launch
  proceeds even if `addRule` fails.
- **No OBB code, by decision.** The game ships as a single APK with no expansion file. Don't add OBB
  handling without a game that actually has one.
- **No licence/activation anything.** There is no key, no server URL and no remote check. Keep it that way.

## Minify, shrink and keep rules
- **R8 + resource shrinking are ON for the debug build type as well as release**, because debug is
  what CI ships and signs (`minifyEnabled true`, `shrinkResources true`,
  `proguard-android-optimize.txt`).
- **But AGP disables R8 obfuscation and optimisation for `debuggable` builds.** The shipped debug APK
  is therefore *shrunk but not renamed*, and the build log says so:
  `WARNING: BuildType 'debug' is both debuggable and has 'isMinifyEnabled' set to true. All code
  optimizations and obfuscation are disabled for debuggable builds.` Class-renaming only happens in a
  non-debuggable build (the `release` variant). No proguard rule can re-enable it — there is no
  inverse of the `-dontobfuscate` that AGP injects.
- **The AAR's `proguard.txt` (consumer rules) is the important half**; AGP merges it into the app
  automatically. It keeps what the engine resolves by name: `com.fvbox.lib.FCore**`, the
  `client.proxy.**` pool (whose `:sc0`…`:scN` components are named from the merged manifest),
  `common.**`, `abs.**`, `rule.common.**`, the `android.**`/`com.android.**` mirrors,
  `top.niunaijun.blackreflection.**`, `top.canyie.**`, `org.lsposed.**`, `com.tencent.mmkv.**`, the
  Parcelable creators, and `native <methods>`. It also enables `-repackageclasses`.
- `app/proguard-rules.pro` adds explicit keeps for `com.fvbox.lib.**`, `com.fvbox.mirror.**`,
  `top.niunaijun.blackreflection.**` and `org.lsposed.**` as belt-and-braces, so a future AAR swap
  cannot silently let R8 rename the engine's entry points.
- It deliberately contains **no keeps for `com.zyn.bs.**` — shrinking and renaming our own code is safe
  because the native payload makes **zero** `FindClass`/`GetMethodID` name-based lookups (verified).

## Pitfalls that have already cost a build
- **`FCore.init(ctx, ...)`'s boolean is `isSamePkg`, and it must be `true`.** It is not a debug flag.
  Upstream's reference host app passes `true`; passing `false` changes how the engine resolves the
  host package for guests that run in this APK's own processes.
- **`launchApk()` returning `void` is not success.** The old code checked a boolean; with this engine a
  missing launch intent is silent. Always check `getLaunchIntentForPackage(pkg, userId) != null` first,
  or the UI sits on "ENTERING SANDBOX…" forever.
- **The engine's `InstallResult` exposes getters, not fields.** `result.success` / `result.msg` from the
  BlackBox code do not compile; use `getSuccess()` / `getMsg()`.
- **Do not re-add a GMS pre-install.** There is no `installGms`/`isInstallGms` in `FCore`; the old
  `ensureGms()` step is gone on purpose.
- **`targetSdk` must stay 28** — the engine's reflected platform APIs are the pre-29 ones. The
  `ExpiredTargetSdkVersion` lint check is therefore disabled in `app/build.gradle`; this is a
  sideloaded app, not a Play Store submission.
- **No project-level repositories.** `settings.gradle` uses `RepositoriesMode.PREFER_SETTINGS`, so a
  `repositories { }`/`flatDir { }` block in a module's build file is a hard failure. The engine's local
  Maven repo (`maven { url = uri("${rootDir}/Spacecore/repo") }`) must therefore live in
  `settings.gradle` — that is what makes `com.fvbox:flib:1.0.0` resolve.
- **The loader screen must not overflow.** `LauncherView` is a vertical stack with the centre block on
  `layout_weight = 1` (scrollable) and the title on a single ellipsized line. Keep it that way; do
  not re-introduce the old fixed `190dp` top margin + bottom-anchored action block, which overlapped
  on short screens.
- **Gson is a hard runtime dependency of the engine, not a transitive nicety.** The AAR shades every
  other library but leaves `com.google.gson.Gson` unresolved: its obfuscated rule store (`u1`) does
  `new Gson()` in a static initialiser to (de)serialise `PackageRule` JSON. R8 reports it as
  `Missing class com.google.gson.Gson (referenced from ...)` — a build *warning*, but the app then dies
  with `NoClassDefFoundError` the first time rules are touched. `app/build.gradle` pins
  `com.google.code.gson:gson`. Upstream's demo only got it via `retrofit-converter-gson`, which is why
  it is easy to miss.
- **A bare `null` in an error toast means `Throwable.getMessage()` was null**, i.e. the engine *threw*
  (often a `NullPointerException` with no message) rather than rejecting the install. Use
  `CrashLog.describe(t)` — it always includes the exception class — and read
  `files/logs/loader.log` instead of guessing. `android.util.Log` alone is logcat-only and is gone
  once the cable is unplugged, which is why nothing was available when this first came up.
- **`android:extractNativeLibs` from the AAR is NOT merged by AGP.** The engine's manifest asks for
  extraction, but AGP drops that attribute from libraries and drives it from the DSL instead, logging
  `extractNativeLibs is set to true in a dependency's AndroidManifest.xml`. `app/build.gradle` sets
  `packaging { jniLibs { useLegacyPackaging = true } }` to match upstream's working build, so
  `libfcore.so`/`libshadowhook.so`/`libpine.so` land in `/data/app/<pkg>/lib/arm/` and any
  dlopen-by-absolute-path inside the engine keeps working.
- **Dobby is now linked exactly once** (payload only). The old engine statically linked its own
  `libdobby.a`; SpaceCore uses shadowhook/pine instead, so the former "two Dobbies fight over hook
  slots" hazard is gone. Do not reintroduce a second static Dobby.

## Logs
- **`files/logs/loader.log`** (internal storage: `/data/data/com.zyn.bs/files/logs/loader.log`), written
  by `CrashLog`; rotated to `loader.log.1` at 512 KB. It mirrors the engine-facing logcat lines and
  records uncaught exceptions via a default handler.
- Pull it from a debug build (what CI ships) without root:
  `adb exec-out run-as com.zyn.bs cat files/logs/loader.log > loader.log`.
- Live logcat, filtered to the tags this app uses:
  `adb logcat -v time LaunchController:V BoxApplication:V NativeBridge:V CrashLog:V AndroidRuntime:E *:S`.
- Tags: `BoxApplication` (boot, engine version/process type), `LaunchController` (install/launch
  sequence), `NativeBridge` (payload), `CrashLog` (logger itself).

## Engine packaging (do not reintroduce a flat jar)
- The engine is **only** ever consumed as `implementation project(':Spacecore')`, which resolves the
  vendored `com.fvbox:flib:1.0.0` AAR from the in-repo Maven repository. There is no `bcoreJar` task,
  no `dist/*.jar`, and no `app/libs/*.aar` dependency.
- Why a Maven repo and not `files('libs/flib-release.aar')`: a raw local AAR is not fully supported in a
  *library* module (AGP cannot package/merge it when the module is itself consumed), while a
  Maven-resolved AAR gets the full treatment — manifest merge, resources, jni libs and consumer rules.
- Why not depend on the AAR directly from `app/`: the wrapper module keeps `app/` free of engine
  packaging details and documents the engine version in exactly one place.
- `Spacecore/repo/.../flib-1.0.0.aar` is a **6.2 MB binary that must stay committed**. It is the engine;
  never "clean" it, never regenerate it locally, never replace it with a different upstream build
  without re-checking the `FCore` API against `app/src/main/java/com/zyn/bs/`.
- Related hardening: CI runs `setup-gradle` with `cache-disabled: true`, passes `--no-build-cache`, and
  logs an engine fingerprint (`== engine: <sha256 of the AAR> (com.fvbox:flib:1.0.0) ==`) so a stale or
  locally edited engine is visible in the log.

## Signing
- `signing.properties` + the `.jks` live at the **project root** (Gradle resolves `storeFile` relative to it).
- Both are git-ignored (`*.jks`, `signing.properties`) — never commit them.
- CI recreates them from base64 secrets `KEYSTORE_BASE64` and `SIGNING_PROPERTIES_BASE64`.
- **The `debug` build type uses the release signing config too**, so `assembleDebug` produces an APK
  signed with our certificate rather than the default `CN=Android Debug` key — it installs as an
  update over an existing install instead of conflicting.
- When the signing files are absent (forks/local), debug falls back to the default debug key and
  release is left unsigned, instead of failing the build.

## Encrypted assets
- Assets are AES-256-GCM encrypted; nothing readable ships in the APK.
- Regenerate with `.polydev/encrypt_assets.py <plain> <out.enc> <asset-name>`; it prints the
  `KEY_PARTS`/`KEY_MASKS` to paste into `AssetCrypto.java`.
- The asset name is bound as AAD, so **renaming the asset means re-encrypting** with a fresh key.
- Verify the shipped asset against the runtime key with `.polydev/verify_asset.py`.

## CI: .github/workflows/android-build.yml
- Decodes signing material → installs the **payload NDK only** (`27.0.12077973`) plus SDK
  platform/build-tools **36** → `gradle assembleDebug` piped through `tee` to `build.log`.
  There is no engine NDK and no CMake install any more.
- Ships the **debug** APK (`dist/bskiller-<versionName>-<run_number>.apk`), signed with our key.
- **Does not check the APK signature** (on purpose): both build types use the release keystore, so the
  check added nothing but noise. Did before, via `apksigner verify` — don't re-add it.
- **Fails if either native lib is missing** — `lib/armeabi-v7a/libzyn-bs.so` or
  `lib/armeabi-v7a/libfcore.so` (the engine lib, which comes from the AAR).
- Telegram report: **failure** → sends `build.log`; **success** → sends the signed APK
  (`dist/bskiller-<versionName>-<run_number>.apk`).
- Logs and APKs are also kept as a build artifact (`loader-<run_number>`, 14 days).
- Telegram steps are `continue-on-error` and skip with a warning when secrets are absent (fork PRs).

## Actions secrets (names only — never store values here)
- `TELEGRAM_BOT_TOKEN` — Telegram bot token (@Fcxswefvcbot).
- `TELEGRAM_CHAT_ID` — target chat id.
- `KEYSTORE_BASE64` — base64 of the release `.jks`.
- `SIGNING_PROPERTIES_BASE64` — base64 of `signing.properties`.
- In workflows: reference as `${{ secrets.NAME }}` only.
