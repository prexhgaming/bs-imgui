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
    `src/main/cpp`, a **CMake** project producing `libblackbox.so`; it links no prebuilt `.a`.
  - `top/niunaijun/jnihook/**` — the JNI reflection hook.
  - `black/**` — the BlackReflection mirrors. The `BR*` classes are **generated** from these by the
    BlackReflection annotation processor; never commit them.
  - `android/**` — platform stubs that deliberately shadow the real `android.jar` classes.
  - `src/main/aidl/**` — the engine's AIDL.
- **No `black-reflection/` and no `compiler/` module.** This engine takes BlackReflection from JitPack
  (`com.github.CodingGay.BlackReflection:core:1.1.4` runtime + `:compiler:1.1.4` annotation processor).
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

### Which engine this is
**BCORE-KERO-FixedSDK** ("KERO" build, by dev `@L_Y_Y1`), vendored from source. It replaced the
NewBlackbox-AutoImport vendor and, before that, BlackBox Reborn — all three keep the same
`top.niunaijun.blackbox` API, so `app/` needs no changes when the engine is swapped.
What differs in this one:
- **native build is CMake** (`src/main/cpp/CMakeLists.txt`), not ndk-build, and it links **no**
  prebuilt `libdobby.a` — the old engine's `src/main/cpp/Dobby/**` is gone with it.
- **BlackReflection comes from JitPack**, so the in-tree `black-reflection/` and `compiler/` modules
  were deleted (they provided the same `top.niunaijun.blackreflection` package — keeping both would
  be duplicate classes on the classpath).
- its own `build.gradle` used a legacy `dexOptions { }` block, removed in AGP 8.x and therefore not
  carried over.
The swap is not half-reversible; the pre-first-swap state is tagged `pre-engine-swap` on `main`.

Why source and not an AAR: the original core was a prebuilt `mundo-release.aar` that shipped
**64-bit only** and was gated on a remote licence server (`MetaActivationManager` → HTTP → native
"prepared" flag), making it unusable offline. Building from source keeps it licence-free and
inspectable.

### Launch path: call the core API, load the payload in the guest
The loader must not hand-roll any of this — the reference host app does it with plain API calls:
- **No worker thread for install/launch.** `LaunchController` used to spawn a bare
  `new Thread(...)`, which has no `Looper`; the engine's calls expect one, so the launch silently
  went nowhere and the UI sat on the loading screen. There are also no artificial `postDelayed`
  delays any more. The whole sequence is `isInstalled()` → (`installPackageAsUser()` if the game is
  on the device but not cloned) → `launchApk()`, called directly.
- **Never override `isEnableLauncherActivity()`.** It does not mean "show a launcher UI": it picks
  the launch path in `BlackBoxCore.startActivity()` — `true` → `LauncherActivity.launch(...)`,
  `false` → `BActivityManager.startActivity(...)`. Forcing it false makes `launchApk()` return true
  while the game never comes up.
- **The payload is loaded in the GUEST process, not the loader.** `libzyn-bs.so` creates the game's
  lifecycle, so it is loaded from `BoxApplication.GuestPayloadLoader`
  (`AppLifecycleCallback.beforeApplicationOnCreate`, filtered to `GameConfig.PACKAGE_NAME`) via a
  plain `System.loadLibrary("zyn-bs")` — the guest process is our own APK, so its native lib dir is
  already on the linker path. No absolute-path fallback, no dlopen, no host-side load.

### Telemetry was deliberately removed — do not reintroduce it
This engine shipped a log uploader: `utils/LogSender.java` POSTed logcat as multipart to a
third-party endpoint (`logs-sender-api.vercel.app/api/<chatId>/upload`) with a hardcoded chat id in
`ClientConfiguration.getLogSenderChatId()`. All of it is gone: the class, the `sendLogs(...)` chain
in `BlackBoxCore`, the `getLogSenderChatId()` config hook, and the six call sites in
`SimpleCrashFix` that fired it on every crash type. Nothing in the core may send data off-device.

## Building
- **Two native builds, both driven by Gradle:** **ndk-build** for the payload
  (`app/src/main/jni/Android.mk`) and **CMake** for the engine
  (`Bcore/src/main/cpp/CMakeLists.txt`). CMake is required again — CI installs `cmake;3.22.1`
  (CMake 3.31+ rejects the engine's `cmake_minimum_required(3.4.1)`).
- **One command builds everything:** `gradle assembleRelease` (CI builds release only — see
  "Minify, shrink and keep rules" for why release and not debug).
- **No flat jar.** `:Bcore` is consumed as a normal Gradle library module; there is no `bcoreJar`
  task and nothing is prebuilt into `libs/`. See "No flat jar" below for why.
- Toolchain (keep `app`, `Bcore` and CI in sync): **AGP 8.13.2**, Gradle **8.14.5** (CI's
  `GRADLE_VERSION`), `compileSdk 36`, `buildToolsVersion 36.1.0`, **Java 21** for `app`
  (JDK 21 in CI) while `Bcore` compiles at **Java 11** (its upstream setting), `minSdk 24`,
  `targetSdk 28`.
- **Two NDKs**, both installed by CI and both from `rootProject.ext`: `ndkVersion`
  (`27.0.12077973`) for the payload, and `engineNdkVersion` (`24.0.8215888`) because the engine is
  pinned to the NDK it was written against.
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3`; run `gradle wrapper` locally if you want one.
- NDK **r26+** required for the payload (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- ABI split: the **payload** is `armeabi-v7a` only (32-bit game target); the **engine** builds
  `arm64-v8a` + `armeabi-v7a`. The app's `abiFilters` decides what is packaged, so the APK carries
  `armeabi-v7a` libs only.
- Native outputs: `libzyn-bs.so` (payload, module `zyn-bs` + prebuilt `libdobby`) and
  `libblackbox.so` (engine). CI asserts both are inside the APK.
- AIDL imports are declared with `aidlPackagedList` — the older `aidlPackageWhiteList` spelling was
  removed in AGP 8.
- The prebuilt static libs are **exceptions to `.gitignore`'s blanket `*.a`/`*.so` rules** and must
  stay committed: the payload's `app/src/main/jni/Dobby/libraries/**/*.a` and the engine's
  `Bcore/src/main/cpp/Dobby/<abi>/libdobby.a` (the second one was missed at first and CI failed with
  `Android.mk:dobby: LOCAL_SRC_FILES points to a missing file`). After touching `.gitignore`, check with
  `git check-ignore -v Bcore/src/main/cpp/Dobby/armeabi-v7a/libdobby.a`.
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
  overrides `isHideRoot` (true) and `isEnableDaemonService` (false). **Do not override
  `isEnableLauncherActivity`** — see the pitfall below.
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
- **But AGP disables R8 obfuscation and optimisation for `debuggable` builds.** The shipped debug APK
  is therefore *shrunk but not renamed*, and the build log says so:
  `WARNING: BuildType 'debug' is both debuggable and has 'isMinifyEnabled' set to true. All code
  optimizations and obfuscation are disabled for debuggable builds.` Class-renaming only happens in a
  non-debuggable build (the `release` variant). No proguard rule can re-enable it — there is no
  inverse of the `-dontobfuscate` that AGP injects.
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
- **Never set `isEnableLauncherActivity()` to false — that was the "the game never starts" bug.**
  The name is misleading: it does not mean "show a launcher UI for virtual apps". `BlackBoxCore.startActivity()`
  uses it to pick the launch path — `true` → `LauncherActivity.launch(intent, userId)` (the engine's
  splash+launch proxy, the correct one), `false` → `getBActivityManager().startActivity(...)` directly.
  With `false`, `launchApk()` still **returns true** and the game never comes up, with no error anywhere.
  Leave it at the default (`true`).
- **`closeCodeInit()` is optional and inert for us.** It registers an `AppLifecycleCallback` that loads
  `inject.so` for the hardcoded package `com.whatever` (see `closecode/Entry.java`), so it does nothing
  for `com.netease.newspike`. The reference app calls it; we deliberately don't. Everything else in the
  reference's `attachBaseContext` order we do match.
- **`targetSdk` must stay 28** — the engine's reflected platform APIs are the pre-29 ones. The
  `ExpiredTargetSdkVersion` lint check is therefore disabled in `app/build.gradle`; this is a
  sideloaded app, not a Play Store submission.
- **No project-level repositories.** `settings.gradle` uses `RepositoriesMode.PREFER_SETTINGS`, so a
  `repositories { }`/`flatDir { }` block in a module's build file is a hard failure. JitPack must stay
  in `settings.gradle`: the engine depends on `com.github.tiann:FreeReflection`.

## No flat jar (do not reintroduce one)
- The core is **only** ever consumed as `implementation project(':Bcore')`. There is no `bcoreJar`
  task, no `dist/bcore-all.jar`, and no `app/libs/*.jar` dependency.
- Why: a prebuilt jar shadows the sources. Gradle sees the jar as an input that never changes, so
  engine edits appear applied while the old engine keeps shipping — the exact "stale engine" trap.
  With a project dependency, `:Bcore`'s sources are inputs and any edit forces a rebuild.
- Related hardening, all of which exists for the same reason: CI runs `setup-gradle` with
  `cache-disabled: true`, passes `--no-build-cache`, and logs an engine fingerprint
  (`== engine: <sha256 of Bcore/src> (N files) ==`) so a stale build is visible in the log.
- If you need to skip compiling the core, use Gradle's own mechanisms (`--offline`, a local maven
  repo), not a hand-copied jar in `libs/`.

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
- Decodes signing material → installs both NDKs (**27.0.12077973** payload, **24.0.8215888** engine),
  **CMake 3.22.1** and SDK platform/build-tools **36** → `gradle assembleRelease` piped through `tee`
  to `build.log`.
- Ships the **release** APK (`dist/bskiller-<versionName>-<run_number>.apk`), signed with our key. On
  fork PRs (no secrets) the release APK is unsigned; the cert assertions are then skipped with a
  warning instead of failing the build.
- Verifies the APK with `apksigner verify` and **fails if it carries the default `CN=Android Debug`
  certificate** instead of ours.
- **Fails if either native lib is missing** — `lib/armeabi-v7a/libzyn-bs.so` or `libblackbox.so`.
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
