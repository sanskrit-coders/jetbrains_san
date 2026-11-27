# Github-san tools

JetBrains IDE plugin (`io.github.sanskritcoders.github-san`) that restores **Open on GitHub** and **Copy GitHub URL** actions for files and directories inside Git submodules, worktrees, and multi-root repositories, and integrates [`diff_san`](https://github.com/sanskrit-coders/diff_san) (`local-diff-san`) for diffing against `HEAD` or comparing two selected files.

- **JetBrains Marketplace:** [Github-san tools](https://plugins.jetbrains.com/plugin/36520-github-san-tools)
- **GitHub Releases:** [`sanskrit-coders/jetbrains_san/releases`](https://github.com/sanskrit-coders/jetbrains_san/releases)
- **VS Code sister extension:** [`sanskrit-coders/vscode_san`](https://github.com/sanskrit-coders/vscode_san)

## Why

In multi-root projects where a parent repository and its submodules share the same GitHub `owner/repo` remote URL (for example `vedAH_yajuH` plus `vedAH_yajuH/content` and `vedAH_yajuH/static` submodules on separate branches), IntelliJ's built-in `GHHostedRepositoriesManager` deduplicates repositories solely by `GHRepositoryCoordinates`, dropping the submodules and hiding **Open In → GitHub** and **Copy Link to GitHub Repository**.

## Features

- **Open In → GitHub** (`RevealGroup`) and **Open on GitHub (Github-san)** (`GoToAction`) for files, directories, and submodule roots.
- **Copy Path/Reference → GitHub Repository URL** (`CopyFileReference` / `Copy.Paste.Special`) with `#L...` line/selection anchors from editors.
- **Diff with `diff_san`** (`ProjectViewPopupMenu`, `EditorPopupMenu`, `EditorTabPopupMenuEx`, `CompareActions`, and `ChangesViewPopupMenu`):
  - **1 file or directory** inside a Git repository/submodule → **Diff against HEAD (diff_san)** / **Diff Directory against HEAD (diff_san)**.
  - **2 selected files** → **Diff Two Files (diff_san)**.
  - **3+ selected files** in Git repositories → **Diff Files against HEAD (diff_san)** (`--head`).
- Resolves `.git` pointer files (`gitdir: ...`), worktree `commondir` configs, tracking branch names (e.g. `origin/content` → `content`), detached HEAD commit SHAs, and UTF-8 / Devanagari path segments.
- Automatically hides Open/Copy GitHub actions when IntelliJ's built-in `Github.Open.In.Browser` already covers the selected file, preventing duplicate menu entries.

## Build & Install

### Fast local build (using installed IntelliJ IDEA JBR + JARs)

```bash
bash package_with_idea.sh
```

Produces `build/distributions/github-san-1.1.0.zip` and installs the JAR into `~/.config/JetBrains/IntelliJIdea2026.3/plugins/github-san/lib/`.

To load without restarting IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk…** and select `build/distributions/github-san-1.1.0.zip`.

### Gradle build (CI / JetBrains Marketplace)

```bash
gradle test buildPlugin verifyPlugin
```
