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
- `Bcore/` — the **sandbox engine**, built from source as a single Gradle library module:
  - `top/niunaijun/blackbox/**` — the engine (public entry point `BlackBoxCore`). Native side is
    `src/main/cpp`, an **ndk-build** project producing `libblackbox.so`; it links its own prebuilt
    `libdobby.a`.
  - `top/niunaijun/jnihook/**` — the JNI reflection hook.
  - `black/**` — the BlackReflection mirrors. The `BR*` classes are **generated** from these by the
    `:compiler` annotation processor; never commit them.
  - `android/**` — platform stubs that deliberately shadow the real `android.jar` classes.
  - `src/main/aidl/**` — the engine's AIDL.
- `black-reflection/` — BlackReflection runtime library (`:black-reflection`, plain `java-library`).
- `compiler/` — the BlackReflection annotation processor (`:compiler`, compile-time only, never packaged).
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

### Which engine this is
BlackBox **Reborn** (github.com/fdavids77/BlackBoxReborn), vendored from source and Apache-2.0.
Its README still advertises GPL-3.0, but its `LICENSE` file is Apache-2.0 — the licence file
governs, and it matches this repo. It replaced the previous core entirely (different lineage,
different packages, different native build); the swap is not half-reversible, so the pre-swap state
is tagged `pre-engine-swap` on `main` if a full revert is ever needed.

Why source and not an AAR: the original core was a prebuilt `mundo-release.aar` that shipped
**64-bit only** and was gated on a remote licence server (`MetaActivationManager` → HTTP → native
"prepared" flag), making it unusable offline. Building from source keeps it licence-free and
inspectable.

### Telemetry was deliberately removed — do not reintroduce it
This engine shipped a log uploader: `utils/LogSender.java` POSTed logcat as multipart to a
third-party endpoint (`logs-sender-api.vercel.app/api/<chatId>/upload`) with a hardcoded chat id in
`ClientConfiguration.getLogSenderChatId()`. All of it is gone: the class, the `sendLogs(...)` chain
in `BlackBoxCore`, the `getLogSenderChatId()` config hook, and the six call sites in
`SimpleCrashFix` that fired it on every crash type. Nothing in the core may send data off-device.

## Building
- **Two native builds, both ndk-build, both driven by Gradle:** `externalNativeBuild.ndkBuild` for the
  payload and for the engine. **There is no CMake anywhere** — do not install or wire CMake back in.
- **One command builds everything:** `gradle assembleDebug` (CI builds debug only).
- `gradle assembleDebug bcoreJar` additionally emits `dist/bcore-all.jar` (see "Reusing the core jar").
- Toolchain (keep `app`, `Bcore` and CI in sync): **AGP 8.13.2**, Gradle **8.14.5** (CI's
  `GRADLE_VERSION`), `compileSdk 36`, `buildToolsVersion 36.1.0`, **Java 17**, `minSdk 24`,
  `targetSdk 28`. `compileSdk` must stay 36: the engine's AAR metadata pins `minCompileSdk 36`, so a
  lower value is a hard failure, not a warning.
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3`; run `gradle wrapper` locally if you want one.
- `ndkVersion` must match CI's `NDK_VERSION` (`27.0.12077973`). `Bcore/build.gradle` and the app pin it.
- NDK **r26+** required (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- ABI split: the **payload** is `armeabi-v7a` only (32-bit game target); the **engine** builds
  `arm64-v8a` + `armeabi-v7a`. The app's `abiFilters` decides what is packaged, so the APK carries
  `armeabi-v7a` libs only.
- Native outputs: `libzyn-bs.so` (payload, module `zyn-bs` + prebuilt `libdobby`) and
  `libblackbox.so` (engine). CI asserts both are inside the APK.
- AIDL imports are declared with `aidlPackagedList` — the older `aidlPackageWhiteList` spelling was
  removed in AGP 8.
- `app/src/main/jni/Dobby/libraries/*/libdobby.a` are prebuilt and **must stay committed**
  (`.gitignore` has an explicit `!` negation for them, since it ignores `*.a` generally).
- Build outputs `app/src/main/jni/libs/`, `app/src/main/jni/obj/`, `dist/` and `*/build/` are git-ignored.

## Sandbox core — how we drive it
- API: `BlackBoxCore.get()` (`top.niunaijun.blackbox.BlackBoxCore`), used from `LaunchController`:
  `isInstalled(pkg, userId)`, `installPackageAsUser(pkg, userId)` → `InstallResult`
  (public `success` / `msg`), `launchApk(pkg, userId)` → boolean. User slot is `0`.
- **Boot contract — four calls, in this order**, all in `BoxApplication`:
  `onBeforeMainApplicationAttach(this, base)` → `doAttachBaseContext(base, ClientConfiguration)` →
  `onAfterMainApplicationAttach(this, base)` from `attachBaseContext`, then `doCreate()` from
  `onCreate`. Each block is wrapped in `catch (Throwable)` so a broken engine can't make the loader
  unlaunchable. `ClientConfiguration` needs `getHostPackageName()` (abstract); this app also
  overrides `isHideRoot` (true), `isEnableDaemonService` (false) and `isEnableLauncherActivity` (false).
- **There is deliberately no warm-up and no early startup.** An earlier version started the engine's
  package scan on a background thread to overlap process start-up; that raced the engine's own
  initialisation and made a tap-to-launch return immediately instead of starting the game. The engine
  initialises itself on first use — never start it early.
- **No licence/activation anything.** There is no key, no server URL and no remote check in this
  engine. Keep it that way.
- **No OBB code, by decision.** The game ships as a single APK with no expansion file, so nothing
  copies OBBs (the engine exposes `BEnvironment.getExternalObbDir` but its install does not use it).
  Don't add OBB handling without a game that actually has an expansion file.
- No Xposed subsystem (no `*Xposed*` class at all); a few files mention it only while filtering
  stack traces or hiding detection.
- `FileUtils.copyFile` uses a 64 KiB buffer and a `transferTo` fast path — APK copies on the install
  path go through it, so keep the bulk path bulk.

## Minify, shrink and keep rules
- **R8 + resource shrinking are ON for the debug build type as well as release**, because debug is
  what CI ships and signs (`minifyEnabled true`, `shrinkResources true`,
  `proguard-android-optimize.txt`).
- `Bcore/consumer-rules.pro` is the important half: AGP merges it into the app automatically. It keeps
  everything the engine resolves **by name at runtime** — `top.niunaijun.blackbox.**` (including the
  `proxy.**` pool, whose `:p0`…`:p99` components are named from the merged manifest),
  `top.niunaijun.jnihook.**`, `top.niunaijun.blackreflection.**`, `mirror.**` and `black.**`, the
  `android.**`/`com.android.**` stubs, and `native <methods>` — plus the BlackReflection annotation
  rules, which only exist there and never in the app's own file.
- `app/proguard-rules.pro` deliberately contains **no keeps for `com.zyn.bs.**` — shrinking and
  renaming our own code is safe because the native payload makes **zero** `FindClass`/`GetMethodID`
  name-based lookups (verified). It only carries `-dontwarn` entries for optional deps the engine
  references but never calls.

## Pitfalls that have already cost a build
- **`.gitignore` must keep the venv rule as `/env/`, not `env/`.** A bare `env/` also matches the
  engine's Java package `Bcore/src/main/java/top/niunaijun/blackbox/core/env/`, so `BEnvironment.java`,
  `AppSystemEnv.java` and `VirtualRuntime.java` silently stop being committed and CI dies with a
  cascade of `package ... does not exist` — even though the files sit right there locally. After
  touching `.gitignore`, verify with
  `git check-ignore -v Bcore/src/main/java/top/niunaijun/blackbox/core/env/BEnvironment.java`.
- **One engine tree, always.** `Bcore/src/main/java/` holds exactly `android/`, `black/` and `top/`. A
  leftover `com/hello/sandbox/` (the previous lineage) or a second `top/niunaijun/blackbox/` means two
  cores got merged. Check with `ls Bcore/src/main/java`.
- **Dobby is linked twice.** The engine statically links its own `libdobby.a` into `libblackbox.so`,
  and the payload statically links Dobby into `libzyn-bs.so`. Two copies initialising in one process
  fight over the same hook slots, which is the prime suspect for random crashes while hooking. If that
  shows up, make exactly one of them own Dobby.
- **`targetSdk` must stay 28** — the engine's reflected platform APIs are the pre-29 ones. The
  `ExpiredTargetSdkVersion` lint check is therefore disabled in `app/build.gradle`; this is a
  sideloaded app, not a Play Store submission.
- **No project-level repositories.** `settings.gradle` uses `RepositoriesMode.PREFER_SETTINGS`, so a
  `repositories { }`/`flatDir { }` block in a module's build file is a hard failure. JitPack must stay
  in `settings.gradle`: the engine depends on `com.github.tiann:FreeReflection`.

## Reusing the core jar
- `gradle bcoreJar` writes `dist/bcore-all.jar`: `:Bcore`'s classes plus `:black-reflection` and the
  small runtime deps (`FreeReflection`, `toml4j`). AndroidX/framework classes are never bundled, and
  `:compiler` is excluded (annotation processor, compile-time only).
- It contains **Java only** — `libblackbox.so` still comes from the ndk-build step. The jar saves
  recompiling the engine's few hundred Java files, not the native build.
- To consume it: put the jar in `app/libs/` and replace `implementation project(':Bcore')` with
  `implementation files('libs/bcore-all.jar')`.
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
- Decodes signing material → installs NDK **27.0.12077973** and SDK platform/build-tools **36** (no
  CMake) → `gradle assembleDebug bcoreJar` piped through `tee` to `build.log`.
- Verifies the APK with `apksigner verify` and **fails if it carries the default `CN=Android Debug`
  certificate** instead of ours.
- **Fails if either native lib is missing** — `lib/armeabi-v7a/libzyn-bs.so` or `libblackbox.so`.
- Verifies `bcore-all.jar` actually contains the engine (`>= 100` `top/niunaijun/blackbox/` entries),
  so a silently-empty jar cannot ship.
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
