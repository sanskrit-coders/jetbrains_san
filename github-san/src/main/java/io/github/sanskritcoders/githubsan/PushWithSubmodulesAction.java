package io.github.sanskritcoders.githubsan;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.PushResult;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Pushes submodules with unpushed commits in bottom-up order and then pushes the enclosing repository.
 */
public final class PushWithSubmodulesAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (SubmoduleGithubActionSupport.isHistoryContext(e)) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        Path repoRoot = SubmoduleGithubActionSupport.resolveTargetRepoRoot(e);
        e.getPresentation().setEnabledAndVisible(repoRoot != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Path repoRoot = SubmoduleGithubActionSupport.resolveTargetRepoRoot(e);
        if (repoRoot == null) {
            SubmoduleGithubActionSupport.notify(
                    project,
                    "Push Submodules & Repository",
                    "No local Git repository found for the current selection or project.",
                    NotificationType.WARNING);
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, "Pushing Submodules & Repository", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    PushResult result = SubmodulePushSupport.pushSubmodulesAndRepo(
                            repoRoot,
                            indicator::isCanceled,
                            indicator::setText2,
                            null);
                    if (result.hasFailures()) {
                        SubmoduleGithubActionSupport.notifyLater(
                                project,
                                "Push Submodules & Repository Failed",
                                result.formatFailureSummary(),
                                NotificationType.ERROR);
                        return;
                    }
                    String details = result.pushedSubmodules().isEmpty()
                            ? "Pushed repository " + repoRoot.getFileName() + "."
                            : "Pushed " + result.pushedSubmodules().size()
                                    + " submodule(s) (" + String.join(", ", result.pushedSubmodules())
                                    + ") and repository " + repoRoot.getFileName() + ".";
                    SubmoduleGithubActionSupport.notifyLater(
                            project,
                            "Push Submodules & Repository",
                            details,
                            NotificationType.INFORMATION);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    SubmoduleGithubActionSupport.notifyLater(
                            project,
                            "Push Submodules & Repository Failed",
                            message,
                            NotificationType.ERROR);
                }
            }
        });
    }
}
