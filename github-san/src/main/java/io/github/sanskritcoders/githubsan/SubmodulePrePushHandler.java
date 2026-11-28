package io.github.sanskritcoders.githubsan;

import com.intellij.dvcs.push.PrePushHandler;
import com.intellij.dvcs.push.PushInfo;
import com.intellij.dvcs.repo.Repository;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.PushMode;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.PushResult;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts IDE Git pushes and silently pushes submodules with unpushed commits bottom-up
 * when {@code push.recurseSubmodules} is not configured on the current machine.
 */
public final class SubmodulePrePushHandler implements PrePushHandler {
    @Override
    public @NotNull String getPresentableName() {
        return "Github-san submodule auto-push";
    }

    @Override
    public @NotNull Result handle(
            @NotNull Project project,
            @NotNull List<PushInfo> pushInfos,
            @NotNull ProgressIndicator indicator) {
        Set<Path> queuedRoots = collectQueuedRoots(pushInfos);
        if (queuedRoots.isEmpty()) {
            return Result.OK;
        }

        for (Path repoRoot : queuedRoots) {
            if (indicator != null && indicator.isCanceled()) {
                return Result.ABORT;
            }
            try {
                PushResult result = SubmodulePushSupport.pushPendingSubmodules(
                        repoRoot,
                        queuedRoots,
                        PushMode.AUTO_WHEN_NOT_CONFIGURED,
                        () -> indicator != null && indicator.isCanceled(),
                        text -> {
                            if (indicator != null) {
                                indicator.setText2(text);
                            }
                        },
                        null);
                if (result.hasFailures()) {
                    SubmoduleGithubActionSupport.notifyLater(
                            project,
                            "Submodule Auto-Push Failed",
                            result.formatFailureSummary(),
                            NotificationType.ERROR);
                    return Result.ABORT;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Result.ABORT;
            } catch (Exception ex) {
                String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                SubmoduleGithubActionSupport.notifyLater(
                        project,
                        "Submodule Auto-Push Failed",
                        message,
                        NotificationType.ERROR);
                return Result.ABORT;
            }
        }
        return Result.OK;
    }

    private static Set<Path> collectQueuedRoots(List<PushInfo> pushInfos) {
        Set<Path> roots = new LinkedHashSet<>();
        for (PushInfo info : pushInfos) {
            Repository repo = info.getRepository();
            if (repo == null) {
                continue;
            }
            VirtualFile root = repo.getRoot();
            if (root != null && root.isInLocalFileSystem()) {
                roots.add(root.toNioPath().toAbsolutePath().normalize());
            }
        }
        return roots;
    }
}
