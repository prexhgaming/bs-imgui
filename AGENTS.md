# AGENTS.md

## Remote & pushing
- Remote `origin` is HTTPS: `https://github.com/prexhgaming/bs-imgui.git` (SSH may not be configured in the sandbox).
- Auth comes from the `gh` CLI: global `credential.helper = !/usr/bin/gh auth git-credential`. Plain `git push` works; no tokens needed in commands.
- Repo identity (local config): `prexhgaming <340324619+prexhgaming@users.noreply.github.com>`.

## Repo conventions
- Default branch is `main`.
- Visibility is **public** (switched from private to get free GitHub Actions minutes after the private quota ran out). Actions enabled with `allowed_actions: all`.
- Never commit secrets — the repo is publicly readable. Use Actions secrets instead (`.env*` is git-ignored).
- `.polydev/` is git-ignored (local agent tooling, not part of the project).

## Building (cpp/ = Android NDK project)
- `cpp/` is an **ndk-build** project (`Android.mk` + `Application.mk`), not CMake/Gradle. No `jni/` folder, so build with:
  `cd cpp && ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk`
- Output: `cpp/libs/<abi>/libzyn-bs.so`. Module names: `zyn-bs` (shared) + prebuilt `libdobby`.
- `APP_ABI := armeabi-v7a` only (32-bit game target). Adding ABIs needs `app/build.gradle` abiFilters updated too.
- Needs NDK **r26+** (`APP_SUPPORT_FLEXIBLE_PAGE_SIZES`); CI pins r27c via `env.NDK_VERSION`.
- `Dobby/libraries/*/libdobby.a` are prebuilt and **must stay committed** — `.gitignore` has an explicit `!` negation for them (it ignores `*.a` generally).
- Build outputs `cpp/libs/` and `cpp/obj/` are git-ignored.

## CI: .github/workflows/android-build.yml
- Sets up the NDK, runs `ndk-build` piping through `tee` to `ndk-build.log` (with `pipefail` so failures propagate).
- Telegram report: **failure** → sends `ndk-build.log`; **success** → sends `dist/libs.zip` laid out as `<abi>/<lib>.so`.
- Logs/zip are also kept as a build artifact (`ndk-build-<run_number>`, 14 days).
- Telegram steps are `continue-on-error` and skip with a warning when secrets are absent (fork PRs).

## Actions secrets (names only — never store values here)
- `TELEGRAM_BOT_TOKEN` — Telegram bot token (@Fcxswefvcbot).
- `TELEGRAM_CHAT_ID` — target chat id.
- In workflows: reference as `${{ secrets.TELEGRAM_BOT_TOKEN }}` / `${{ secrets.TELEGRAM_CHAT_ID }}` only.
