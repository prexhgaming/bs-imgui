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
- `app/` — the loader app module (`com.zyn.bs`), a bcore/BlackBox sandbox loader.
- `app/src/main/jni/` — the native payload, an **ndk-build** project (`Android.mk` + `Application.mk`).
- `app/libs/Fixed-Update4.1.aar` — bcore (BlackBox), unmodified; **must stay committed**.
- `app/src/main/assets/a7f3c9.bin` — AES-256-GCM encrypted wallpaper (see "Encrypted assets").

## Building
- Gradle drives ndk-build itself via `externalNativeBuild.ndkBuild`, so **one command builds everything**:
  `gradle assembleDebug assembleRelease`
- No `gradlew` in the repo — the wrapper jar isn't committed. CI gets `gradle` from
  `gradle/actions/setup-gradle@v3` (`GRADLE_VERSION: 8.5`). Run `gradle wrapper` locally to generate one.
- `ndkVersion` in `app/build.gradle` must match `env.NDK_VERSION` in the workflow (currently `27.0.12077973`).
- NDK **r26+** required (`Application.mk` sets `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`).
- `APP_ABI := armeabi-v7a` only (32-bit game target). Adding ABIs needs `app/build.gradle` `abiFilters` updated too.
- Native output: `app/build/**/lib/<abi>/libzyn-bs.so`. Module names: `zyn-bs` (shared) + prebuilt `libdobby`.
- `app/src/main/jni/Dobby/libraries/*/libdobby.a` are prebuilt and **must stay committed** — `.gitignore`
  has an explicit `!` negation for them (it ignores `*.a` generally).
- Build outputs `app/src/main/jni/libs/` and `app/src/main/jni/obj/` are git-ignored.

## Release signing
- `signing.properties` + the `.jks` live at the **project root** (Gradle resolves `storeFile` relative to it).
- Both are git-ignored (`*.jks`, `signing.properties`) — never commit them.
- CI recreates them from base64 secrets `KEYSTORE_BASE64` and `SIGNING_PROPERTIES_BASE64`.
- When they're absent (forks/local), the release APK is built **unsigned** instead of failing.

## Encrypted assets
- Assets are AES-256-GCM encrypted; nothing readable ships in the APK.
- Regenerate with `.polydev/encrypt_assets.py <plain> <out.enc> <asset-name>`; it prints the
  `KEY_PARTS`/`KEY_MASKS` to paste into `AssetCrypto.java`.
- The asset name is bound as AAD, so **renaming the asset means re-encrypting** with a fresh key.
- Verify the shipped asset against the runtime key with `.polydev/verify_asset.py`.

## CI: .github/workflows/android-build.yml
- Decodes signing material → installs NDK → `gradle assembleDebug assembleRelease` piped through `tee` to `build.log`.
- Verifies the release APK with `apksigner verify` (fails the build if unsigned).
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
