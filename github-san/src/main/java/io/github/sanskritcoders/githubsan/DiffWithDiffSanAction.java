package io.github.sanskritcoders.githubsan;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sanskritcoders.githubsan.DiffSanSupport.DiffMode;
import io.github.sanskritcoders.githubsan.DiffSanSupport.DiffPlan;
import io.github.sanskritcoders.githubsan.DiffSanSupport.LaunchResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Diffs a selected file/directory (or multiple files) against Git {@code HEAD},
 * or compares two selected files side by side using {@code local-diff-san}.
 */
public final class DiffWithDiffSanAction extends DumbAwareAction {
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
        DiffPlan plan = DiffSanSupport.planSelection(toLocalPaths(collectSelectedFiles(e)));
        if (plan == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setText(plan.label());
        e.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        List<VirtualFile> selectedFiles = collectSelectedFiles(e);
        DiffPlan plan = DiffSanSupport.planSelection(toLocalPaths(selectedFiles));
        if (plan == null) {
            return;
        }
        saveUnsavedDocuments(selectedFiles, plan);

        Project project = e.getProject();
        Path projectBaseDir = resolveProjectBaseDir(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            LaunchResult result = DiffSanSupport.launch(projectBaseDir, plan);
            if (!result.started()) {
                ApplicationManager.getApplication().invokeLater(() -> SubmoduleGithubActionSupport.notify(
                        project,
                        "diff_san Failed",
                        result.errorMessage(),
                        NotificationType.ERROR));
            }
        });
    }

    private static List<VirtualFile> collectSelectedFiles(AnActionEvent e) {
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null && files.length > 0) {
            List<VirtualFile> valid = new ArrayList<>(files.length);
            for (VirtualFile file : files) {
                if (file == null || !file.isInLocalFileSystem()) {
                    return List.of();
                }
                valid.add(file);
            }
            return valid;
        }
        VirtualFile single = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (single != null && single.isInLocalFileSystem()) {
            return List.of(single);
        }
        return List.of();
    }

    private static List<Path> toLocalPaths(List<VirtualFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            paths.add(file.toNioPath());
        }
        return paths;
    }

    private static void saveUnsavedDocuments(List<VirtualFile> selectedFiles, DiffPlan plan) {
        FileDocumentManager manager = FileDocumentManager.getInstance();
        if (plan.mode() == DiffMode.HEAD_DIRECTORY) {
            manager.saveAllDocuments();
            return;
        }
        for (VirtualFile file : selectedFiles) {
            Document doc = manager.getCachedDocument(file);
            if (doc != null && manager.isDocumentUnsaved(doc)) {
                manager.saveDocument(doc);
            }
        }
    }

    private static Path resolveProjectBaseDir(Project project) {
        if (project == null || project.getBasePath() == null) {
            return null;
        }
        return Path.of(project.getBasePath());
    }
}
