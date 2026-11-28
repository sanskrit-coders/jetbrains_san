# Github-san tools

JetBrains IDE plugin (`io.github.sanskritcoders.github-san`) that restores **Open on GitHub** and **Copy GitHub URL** actions for files and directories inside Git submodules, worktrees, and multi-root repositories, silently auto-pushes ahead Git submodules during pushes, and integrates [`diff_san`](https://github.com/sanskrit-coders/diff_san) (`local-diff-san`) for diffing against `HEAD` or comparing two selected files.

- **JetBrains Marketplace:** [Github-san tools](https://plugins.jetbrains.com/plugin/36520-github-san-tools)
- **GitHub Releases:** [`sanskrit-coders/jetbrains_san/releases`](https://github.com/sanskrit-coders/jetbrains_san/releases)
- **VS Code sister extension:** [`sanskrit-coders/vscode_san`](https://github.com/sanskrit-coders/vscode_san)

## Why

In multi-root projects where a parent repository and its submodules share the same GitHub `owner/repo` remote URL (for example `vedAH_yajuH` plus `vedAH_yajuH/content` and `vedAH_yajuH/static` submodules on separate branches), IntelliJ's built-in `GHHostedRepositoriesManager` deduplicates repositories solely by `GHRepositoryCoordinates`, dropping the submodules and hiding **Open In → GitHub** and **Copy Link to GitHub Repository**.

## Features

- **Silent Submodule Auto-Push (`PrePushHandler`):** Intercepts IDE Git Push / Commit & Push (`com.intellij.prePushHandler`) and silently pushes submodules with unpushed commits in bottom-up order whenever `push.recurseSubmodules` is not configured on the machine (skipping detached HEAD submodules cleanly and aborting only if a submodule push fails).
- **Push Submodules & Repository** (`Git.MainMenu`, `GitRepositoryActions`, `ProjectViewPopupMenu`): Explicitly pushes all ahead submodules bottom-up and then pushes the enclosing repository.
- **Enable Git Submodule Auto-Push** (`Git.MainMenu`, `ToolsMenu`, `GoToAction`): Configures `git config --global push.recurseSubmodules on-demand` globally and optionally installs an idempotent bottom-up `.git/hooks/pre-push` hook so CLI / external pushes push submodules automatically too.
- **Open In → GitHub** (`RevealGroup`) and **Open on GitHub (Github-san)** (`GoToAction`) for files, directories, and submodule roots.
- **Copy Path/Reference → GitHub Repository URL** (`CopyFileReference` / `Copy.Paste.Special`) with `#L...` line/selection anchors from editors.
- **Diff with `diff_san`** (`ProjectViewPopupMenu`, `EditorPopupMenu`, `EditorTabPopupMenuEx`, `CompareActions`, and `ChangesViewPopupMenu`):
  - **1 file or directory** inside a Git repository/submodule → **Diff against HEAD (diff_san)** / **Diff Directory against HEAD (diff_san)**.
  - **2 selected files** → **Diff Two Files (diff_san)**.
  - **3+ selected files** in Git repositories → **Diff Files against HEAD (diff_san)** (`--head`).
- Resolves `.git` pointer files (`gitdir: ...`), worktree `commondir` configs, tracking branch names (e.g. `origin/content` → `content`), detached HEAD commit SHAs, and UTF-8 / Devanagari path segments.
- Automatically hides Open/Copy GitHub actions when IntelliJ's built-in `Github.Open.In.Browser` already covers the selected file, preventing duplicate menu entries.

## Prerequisites (`diff_san` binaries)

For the **Diff with `diff_san`** actions (`local-diff-san`), install the Python tools from [`sanskrit-coders/diff_san`](https://github.com/sanskrit-coders/diff_san/blob/master/README.md) — specifically [`local_diff/README.md`](https://github.com/sanskrit-coders/diff_san/blob/master/local_diff/README.md):

```bash
pip install local-diff-san
# Or editable install from a local diff_san clone:
pip install -e /path/to/diff_san/pull_requests -e /path/to/diff_san/local_diff
```

The plugin automatically locates `local-diff-san` via `DIFF_SAN_BIN`, `PATH`, `<project>/.venv/bin`, `~/gitland/python_venv/bin`, and `~/.local/bin`.

## Build & Install

### Fast local build (using installed IntelliJ IDEA JBR + JARs)

```bash
bash package_with_idea.sh
```

Produces `build/distributions/github-san-1.2.0.zip` and installs the JAR into `~/.config/JetBrains/IntelliJIdea2026.3/plugins/github-san/lib/`.

To load without restarting IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk…** and select `build/distributions/github-san-1.2.0.zip`.

### Gradle build (CI / JetBrains Marketplace)

```bash
gradle test buildPlugin verifyPlugin
```
