# jetbrains_san

JetBrains IntelliJ and IDE plugins tailored for Sanskrit scholarship and multi-submodule corpus workflows.

## Plugins

### [`github-san/`](github-san/README.md) (**Github-san tools**)

Adds **Open In → GitHub** (`Open on GitHub`) and **Copy Path/Reference → GitHub Repository URL** actions for files and directories inside Git submodules, Git worktrees, and multi-root repositories—including cases where multiple submodules point to different branches of the same GitHub repository (such as `content` and `static` branches in `vishvAsa` Hugo corpora) and IntelliJ's built-in GitHub integration drops the submodule.

- **Works for submodules & worktrees:** resolves `.git` pointers (`gitdir: ...`) and worktree `commondir` configs directly.
- **No duplicate menu clutter:** detects whether IntelliJ's built-in `Github.Open.In.Browser` already covers the selected file and stays hidden when it does.
- **Supports files, directories, & line selections:** opens `/blob/<branch-or-sha>/...` (with `#L...` fragments in editors) and `/tree/<branch-or-sha>/...` for directories and submodule roots.

See [`github-san/README.md`](github-san/README.md) for installation and build instructions.
