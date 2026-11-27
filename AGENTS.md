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
- `app/` — the loader app module (`com.zyn.bs`), a Mundo (BlackBox fork) sandbox loader.
- `app/src/main/jni/` — the native payload, an **ndk-build** project (`Android.mk` + `Application.mk`).
- `app/libs/mundo-release.aar` — the **Mundo** core (a BlackBox fork); **must stay committed**.
  It is self-contained: it ships its own manifest components (merged in by AGP), its own
  consumer ProGuard rules (`proguard.txt`), and per-ABI `libzyluqeh.so` — including `armeabi-v7a`.
  Its `jni/riscv64/` entry has been **stripped**: AGP 8.1 rejects that folder name
  (`jni extracted from path .../jni/riscv64/libzyluqeh.so is not an ABI`) and `mergeDebugNativeLibs`
  fails. Strip it again if the AAR is ever replaced.
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

## Building
- Gradle drives ndk-build itself via `externalNativeBuild.ndkBuild`, so **one command builds everything**:
  `gradle assembleDebug` (CI builds debug only).
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3` (`GRADLE_VERSION: 8.5`). Run `gradle wrapper` locally to generate one.
- `ndkVersion` in `app/build.gradle` must match `env.NDK_VERSION` in the workflow (currently `27.0.12077973`).
- NDK **r26+** required (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- `APP_ABI := armeabi-v7a` only (32-bit game target). Adding ABIs needs `app/build.gradle` `abiFilters` updated too.
- Native output: `app/build/**/lib/<abi>/libzyn-bs.so`. Module names: `zyn-bs` (shared) + prebuilt `libdobby`.
- `app/src/main/jni/Dobby/libraries/*/libdobby.a` are prebuilt and **must stay committed** — `.gitignore`
  has an explicit `!` negation for them (it ignores `*.a` generally).
- Build outputs `app/src/main/jni/libs/` and `app/src/main/jni/obj/` are git-ignored.
- **`targetSdk` must stay 28** — the core's reflected platform APIs are the pre-29 ones and break on
  newer targets. The `ExpiredTargetSdkVersion` lint check is therefore disabled in `app/build.gradle`
  (`lint { disable ... ; checkReleaseBuilds false; abortOnError false }`); this is a sideloaded app,
  not a Play Store submission.

## Sandbox core (Mundo) — how we drive it
- Entry points are public statics in `net_62v.external.*`; engine internals live in `top.bienvenido.**`.
- The core **bootstraps itself**: the AAR declares `MundoSupervisorProvider` and a pool of `:stubN`
  processes, and those ContentProviders are created before `Application.onCreate`. So there is no
  `doAttachBaseContext`/`doCreate` equivalent to call.
- **Activation IS required — do not try to skip it.** Every core operation is gated on an internal
  "prepared" flag that Java cannot see: it is a JNI round trip
  (`MNative.ioctl(43012, null)` → `Boolean.parseBoolean(result)`). While it is `false` the core pops
  a toast ("SDK aún no está bien preparado…", hardcoded in Spanish in `top.bienvenido.date_24323.B2`)
  and refuses the launch. The native lib sets that flag: `libzyluqeh.so` exports
  `Mundo_Activate_SDK(JNIEnv*, const char*)`, reached via
  `MetaActivationManager.activateSdk(String)`. `CoreActivation` holds the key, `BoxApplication`
  activates at boot, and `LaunchController` retries before each launch (activation needs a network
  round trip, so a device that was offline at boot would otherwise fail forever).
- The key in `CoreActivation.LICENSE` was recovered from the private reference source, where it is
  built byte-by-byte inside a `new Object() { public String toString() { … } }` trick (20 bytes from
  `(byte)(intConstant >>> shift)`). It is a static account key, identical at both call sites.
- API in use: `MetaActivityManager.isInnerPackageInstalled(pkg)` / `isAppRunning(pkg, 0)` /
  `launchApp(pkg)` / `killAppByPkg(pkg)`, `MetaApplicationInstaller.cloneApp(pkg)` /
  `installAppByPath(path)`, `MetaStorageManager.obtainAppExternalStorageDir(pkg)`.
- `MetaApplicationInstaller.convertResultToString(int)` is a `"Not Support."` stub in this build, so
  treat "no exception thrown" as success — that is what the reference app does too.
- Inspect the AAR's API with `.polydev/inspect_aar.py <aar> <class-regex>` (reads `classes.jar` in
  memory; extracting it fails on the core's absurdly long obfuscated class names).

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
- Decodes signing material → installs NDK → `gradle assembleDebug` piped through `tee` to `build.log`.
- Verifies the APK with `apksigner verify` and **fails if it carries the default `CN=Android Debug`
  certificate** instead of ours.
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
