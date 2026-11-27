package io.github.sanskritcoders.githubsan;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

/**
 * Copies the GitHub URL for the selected file or directory (including Git submodules) to the clipboard.
 */
public final class CopySubmoduleGithubUrlAction extends DumbAwareAction {
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
        SubmoduleGithubActionSupport.resolveAndExecute(e, (project, url) -> {
            CopyPasteManager.copyTextToClipboard(url);
            SubmoduleGithubActionSupport.notify(project, "GitHub URL Copied", url, NotificationType.INFORMATION);
        });
    }
}
