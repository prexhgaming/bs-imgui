# AGENTS.md

## Remote & pushing
- Remote `origin` is HTTPS: `https://github.com/prexhgaming/bs-imgui.git` (SSH may not be configured in the sandbox).
- Auth comes from the `gh` CLI: global `credential.helper = !/usr/bin/gh auth git-credential`. Plain `git push` works; no tokens needed in commands.
- Repo identity (local config): `prexhgaming <340324619+prexhgaming@users.noreply.github.com>`.

## Repo conventions
- Default branch is `main`.
- Visibility is **public** (switched from private to get free GitHub Actions minutes after the private quota ran out). Actions enabled with `allowed_actions: all`.
- Never commit secrets — the repo is publicly readable. Use Actions secrets instead (`.env*` is git-ignored).
- `.polydev/` (local agent tooling) and `bbref/` (extracted reference project) are git-ignored.

## Project layout
- Repo root **is** the Gradle source root (the app used to live in `loader/`, now flattened).
- `app/` — the loader app module (`com.zyn.bs`).
- `app/src/main/jni/` — the native payload, an **ndk-build** project (`Android.mk` + `Application.mk`).
- `Bcore/` — the **sandbox core, built from source** as a **single** Gradle library module
  (Apache-2.0). It used to be four (`Bcore` plus `android-mirror`, `black-fake`, `black-hook` as
  submodules of it); they were merged because every class already lived in its final package, so the
  merge needed no source or import change. `Bcore/src/main/java/` now holds all four package roots:
  - `com/hello/sandbox/**` — the engine; `src/main/cpp` is a **CMake** project producing `libsandbox.so`.
  - `black/android/**` — the BlackReflection mirrors. The `BR*` classes are **generated** from these
    by the `BlackReflection:compiler` annotation processor configured in `Bcore/build.gradle`; never
    commit them.
  - `android/**` — black-fake's platform stubs. These deliberately shadow the real `android.jar`
    classes, which is how the sandbox replaces them.
  - `top/niunaijun/jnihook/**` — the JNI reflection hook.
  - `src/main/aidl/` mirrors that split: both `com/**` and `android/**` live in this one module's
    AIDL source set.
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

### Why the core is source, not an AAR
It used to be a prebuilt `mundo-release.aar`. That build was **64-bit only**, and its engine was
gated on a remote licence server (`MetaActivationManager` → HTTP → native "prepared" flag), which
made it unusable offline and untestable. The source drop is Apache-2.0, so the core is now compiled
here, licence-free, for every ABI.

## Building
- Two native builds, both driven by Gradle: `externalNativeBuild.ndkBuild` for the payload and
  `externalNativeBuild.cmake` for the core engine. **One command builds everything:**
  `gradle assembleDebug` (CI builds debug only).
- `gradle assembleDebug bcoreJar` additionally emits `dist/bcore-all.jar` — the core packed into one
  reusable jar (see "Reusing the core jar").
- **CMake 3.22.1** must be installed (`cmake;3.22.1` via sdkmanager) for `:Bcore`.
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3` (`GRADLE_VERSION: 8.5`). Run `gradle wrapper` locally to generate one.
- `ndkVersion` in `app/build.gradle` must match `env.NDK_VERSION` in the workflow (currently `27.0.12077973`);
  `Bcore/build.gradle` pins the same version.
- NDK **r26+** required (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- `APP_ABI := armeabi-v7a` only for the **payload** (32-bit game target). `:Bcore` builds **all four
  ABIs** (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`); the app's `abiFilters` decides what is
  actually packaged, so the APK stays 32-bit. Change `app/build.gradle`'s `abiFilters` too if the
  payload ever goes 64-bit.
- Native outputs: `app/build/**/lib/<abi>/libzyn-bs.so` and `.../lib<abi>/libsandbox.so`.
  Module names: `zyn-bs` (shared) + prebuilt `libdobby`; the core's CMake target is `sandbox`.
- `app/src/main/jni/Dobby/libraries/*/libdobby.a` are prebuilt and **must stay committed** — `.gitignore`
  has an explicit `!` negation for them (it ignores `*.a` generally).
- Build outputs `app/src/main/jni/libs/`, `app/src/main/jni/obj/`, `dist/` and `*/build/` are git-ignored.
- **`targetSdk` must stay 28** — the core's reflected platform APIs are the pre-29 ones and break on
  newer targets. The `ExpiredTargetSdkVersion` lint check is therefore disabled in `app/build.gradle`
  (`lint { disable ... ; checkReleaseBuilds false; abortOnError false }`); this is a sideloaded app,
  not a Play Store submission.
- The core modules are AGP 8 clean: `dexOptions`/`aaptOptions` legacy blocks were removed and the
  `package=` attribute was stripped from every core `AndroidManifest.xml` (AGP 8 requires `namespace`
  in `build.gradle` instead).

## Sandbox core — how we drive it
- API: `SandBoxCore.get()` (`com.hello.sandbox.SandBoxCore`). Internals live under `com.hello.sandbox.**`,
  `top.niunaijun.**`, and the `black.**` / `mirror.**` reflection stubs.
- **Two lifecycle calls, in this order** (both in `BoxApplication`):
  `doAttachBaseContext(context, ClientConfiguration)` from `attachBaseContext`, then `doCreate()`
  from `onCreate`. `ClientConfiguration.getHostPackageName()` is the only abstract method; this app
  also overrides `isHideRoot` (true) and `isEnableDaemonService` (false).
- **The core is warmed up off the main thread.** `attachBaseContext` calls `SandBoxCore.get().warmUp()`
  on a background thread ("core-warmup") so the package scan, jar extraction and service start-up
  overlap the rest of process start instead of blocking the first frame.
  `BlackBoxSystem.startup()` is the single entry point and blocks concurrent callers, so a UI call
  arriving mid-warm-up waits rather than seeing a half-initialised core — don't make it non-blocking.
- **There is no licensing.** `MetaActivationManager.activateSdk()` takes no key, does no network I/O
  and just returns; `isLicenseActivated()` is unconditionally true. `LicenseVerifier` (the old HTTP
  client) was deleted, the two gates in `SandBoxCore.launchApk` / `installPackageAsUser` were removed,
  and the core calls `activateSdk()` itself at the end of `doAttachBaseContext`. **Do not reintroduce
  a key, a server URL or a remote check** — the point of building from source is that it stays local.
- API in use by `LaunchController`: `SandBoxCore.get().isInstalled(pkg, userId)`,
  `installPackageAsUser(pkg, userId)` → `InstallResult` (public `success` / `msg`), and
  `launchApk(pkg, userId)` → boolean. User slot is `0`.
- OBB: **the SDK copies expansion files itself — the app has no OBB code at all.**
  `BObbInstaller` (`core/system/pm`) runs at the end of a successful install, and
  `BPackageManagerService` calls `BObbInstaller.uninstall` on uninstall. It looks in the device's
  real `Android/obb/<pkg>/` (`Environment.getExternalStorageDirectory()`) and copies any
  `*.obb` into `BEnvironment.getExternalObbDir(pkg, userId)`
  (`<getExternalUserDir(userId)>/Android/obb/<pkg>/`) with a 1 MiB buffer -- expansion files are
  multi-GB, so never route them through `FileUtils.copyFile`'s 1 KB loop.
  `SandBoxCore.get().installObb(pkg, userId)` / `uninstallObb(...)` re-run or drop the copy on demand.
  Resolve paths through `BEnvironment`, never hardcode them.
- **There is no Xposed support, and it is not coming back.** The whole subsystem was removed: the
  module-manager service, its AIDL and the module entities, the PineXposed loader in
  `BActivityThread`, `Hook/VMClassLoaderHook` (with the `hideXposed` JNI entry point), and the SDK
  surface (`installXPModule`, `uninstallXPModule`, `isXPEnable`, `setXPEnable`, `isXposedModule`,
  `isModuleEnable`, `getInstalledXPModules`, `isHideXposed`). `pine:core` must stay — `jnihook` needs
  it — but do not reintroduce `pine:xposed`.
- Core keep rules ship as `Bcore/consumer-rules.pro` (AGP merges them into the app automatically).
  `app/proguard-rules.pro` deliberately contains no core packages.

## Pitfalls that have already cost a build
- **`.gitignore` must keep the venv rule as `/env/`, not `env/`.** A bare `env/` also matches the
  core's Java package `Bcore/src/main/java/com/hello/sandbox/core/env/`, so `BEnvironment.java`,
  `AppSystemEnv.java` and `VirtualRuntime.java` silently stop being committed and CI dies with a
  cascade of `package com.hello.sandbox.core.env does not exist` — even though the files are sitting
  right there locally. After touching `.gitignore`, verify with
  `git check-ignore -v Bcore/src/main/java/com/hello/sandbox/core/env/BEnvironment.java`.
- **One core tree, always.** Since the module merge `Bcore/src/main/java/` legitimately holds four
  package roots: `com/`, `black/`, `android/` and `top/`. A *second* `com/hello/sandbox` or a
  leftover `top/niunaijun/blackbox/` tree means two cores got merged. Check with
  `find Bcore -path '*niunaijun/blackbox*'`. When swapping in a source drop, diff against the
  *immediate* parent commit (`<commit>^`), not an older one, or a cleanup will delete files that
  legitimately landed in between.
- **No project-level repositories.** `settings.gradle` uses `RepositoriesMode.PREFER_SETTINGS`, so a
  `repositories { }`/`flatDir { }` block in a module's build file is a hard failure. Note that a
  `repositories { }` written *inside* `android { }` is not an AGP property — Groovy falls through to
  the project's `repositories` and triggers exactly this error.
- **`fake_dlfcn.cpp` supports x86_64** via the shared 64-bit (`Elf64_*`) branch, together with
  aarch64. All four ABIs build; keep `abiFilters` in `Bcore/build.gradle` complete or the core stops
  being reusable by a 64-bit host. The APK itself stays `armeabi-v7a`-only via the app's `abiFilters`.


## Reusing the core jar
- `gradle bcoreJar` writes `dist/bcore-all.jar`: the `:Bcore` module's classes plus its small runtime
  deps (FreeReflection, BlackReflection, Pine core). AndroidX/framework classes are never bundled.
- It contains **Java only** — `libsandbox.so` still comes from the CMake build. The jar saves
  recompiling ~450 Java files, not the native step.
- To consume it: put the jar in `app/libs/` and replace `implementation project(':Bcore')` with
  `implementation files('libs/bcore-all.jar')` (plus the four Maven deps if you use the thin variant).
- CI uploads it to Telegram next to the APK as `dist/bcore-all-<run_number>.jar`.

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
- Decodes signing material → installs NDK **+ CMake 3.22.1** → `gradle assembleDebug bcoreJar`
  piped through `tee` to `build.log`.
- Verifies the APK with `apksigner verify` and **fails if it carries the default `CN=Android Debug`
  certificate** instead of ours.
- Verifies `bcore-all.jar` actually contains the core (`>= 100` `com/hello/sandbox/` entries), so a
  silently-empty jar cannot ship.
- Telegram report: **failure** → sends `build.log`; **success** → sends the signed APK
  (`dist/bskiller-<versionName>-<run_number>.apk`) **and** `dist/bcore-all-<run_number>.jar`.
- Logs, APKs and the jar are also kept as a build artifact (`loader-<run_number>`, 14 days).
- Telegram steps are `continue-on-error` and skip with a warning when secrets are absent (fork PRs).

## Actions secrets (names only — never store values here)
- `TELEGRAM_BOT_TOKEN` — Telegram bot token (@Fcxswefvcbot).
- `TELEGRAM_CHAT_ID` — target chat id.
- `KEYSTORE_BASE64` — base64 of the release `.jks`.
- `SIGNING_PROPERTIES_BASE64` — base64 of `signing.properties`.
- In workflows: reference as `${{ secrets.NAME }}` only.
