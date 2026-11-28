package io.github.sanskritcoders.githubsan;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.HookInstallResult;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Configures Git's {@code push.recurseSubmodules=on-demand} globally and optionally installs
 * an idempotent repository {@code pre-push} hook for automatic bottom-up submodule pushes.
 */
public final class EnableSubmoduleAutoPushAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean available = e.getProject() != null || SubmoduleGithubActionSupport.resolveTargetRepoRoot(e) != null;
        e.getPresentation().setEnabledAndVisible(available);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Path repoRoot = SubmoduleGithubActionSupport.resolveTargetRepoRoot(e);

        boolean hasRepo = repoRoot != null;
        String prompt = hasRepo
                ? "Configure Git to push submodules automatically?\n\n"
                        + "• Global + Repo Hook: sets global push.recurseSubmodules=on-demand and installs a "
                        + "bottom-up pre-push hook in " + repoRoot.getFileName() + ".\n"
                        + "• Global Only: sets git config --global push.recurseSubmodules on-demand."
                : "Set git config --global push.recurseSubmodules on-demand so Git automatically "
                        + "pushes referenced submodule commits when pushing superprojects?";
        String[] options = hasRepo
                ? new String[] {"Global + Repo Hook", "Global Only", "Cancel"}
                : new String[] {"Enable Global Config", "Cancel"};

        int choice = Messages.showDialog(
                project,
                prompt,
                "Enable Git Submodule Auto-Push",
                options,
                0,
                Messages.getQuestionIcon());

        boolean cancelled = choice < 0 || (hasRepo ? choice == 2 : choice == 1);
        if (cancelled) {
            return;
        }
        boolean installHook = hasRepo && choice == 0;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                SubmodulePushSupport.enableGlobalRecurseSubmodules(repoRoot);
                StringBuilder message = new StringBuilder("Configured git config --global push.recurseSubmodules on-demand.");
                NotificationType type = NotificationType.INFORMATION;
                if (installHook) {
                    HookInstallResult hookResult = SubmodulePushSupport.installPrePushHook(repoRoot);
                    message.append(" Pre-push hook ready at ").append(hookResult.hookFile()).append('.');
                    if (hookResult.customHooksPathWarning() != null) {
                        message.append(' ').append(hookResult.customHooksPathWarning());
                        type = NotificationType.WARNING;
                    }
                }
                SubmoduleGithubActionSupport.notifyLater(
                        project,
                        "Git Submodule Auto-Push Enabled",
                        message.toString(),
                        type);
            } catch (Exception ex) {
                String error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                SubmoduleGithubActionSupport.notifyLater(
                        project,
                        "Failed to Enable Submodule Auto-Push",
                        error,
                        NotificationType.ERROR);
            }
        });
    }
}
