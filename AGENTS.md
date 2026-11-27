# AGENTS.md

## Remote & pushing
- Remote `origin` is HTTPS: `https://github.com/prexhgaming/bs-imgui.git` (SSH may not be configured in the sandbox).
- Auth comes from the `gh` CLI: global `credential.helper = !/usr/bin/gh auth git-credential`. Plain `git push` works; no tokens needed in commands.
- Repo identity (local config): `prexhgaming <340324619+prexhgaming@users.noreply.github.com>`.

## Repo conventions
- Default branch is `main`.
- `.polydev/` is git-ignored (local agent tooling, not part of the project).
