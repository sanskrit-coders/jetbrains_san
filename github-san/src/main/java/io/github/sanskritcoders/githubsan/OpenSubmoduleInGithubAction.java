package io.github.sanskritcoders.githubsan;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the selected file or directory (including Git submodules) on GitHub in the browser.
 */
public final class OpenSubmoduleInGithubAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(SubmoduleGithubActionSupport.shouldShowForFileContext(e));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        SubmoduleGithubActionSupport.resolveAndExecute(e, (project, url) -> BrowserUtil.browse(url, project));
    }
}
