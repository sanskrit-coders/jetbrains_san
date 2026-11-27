package io.github.sanskritcoders.githubsan;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.sanskritcoders.githubsan.GitHubUrlResolver.GitLocation;
import io.github.sanskritcoders.githubsan.GitHubUrlResolver.LineRange;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Shared action context resolution and background execution helpers for Github-san tools actions.
 */
final class SubmoduleGithubActionSupport {
    private static final String NOTIFICATION_GROUP_ID = "Github-san tools";
    private static final DataKey<Object> VCS_FILE_REVISION_KEY = DataKey.create("VCS_FILE_REVISION");
    private static final DataKey<Object> VCS_LOG_COMMIT_SELECTION_KEY = DataKey.create("Vcs.Log.Commit.Selection");

    private SubmoduleGithubActionSupport() {}

    static boolean isHistoryContext(AnActionEvent e) {
        if (e.getData(VCS_FILE_REVISION_KEY) != null || e.getData(VCS_LOG_COMMIT_SELECTION_KEY) != null) {
            return true;
        }
        String place = e.getPlace();
        return place != null && (place.contains("Vcs.Log") || place.contains("VcsHistory") || place.contains("History"));
    }

    static boolean shouldShowForFileContext(AnActionEvent e) {
        if (isHistoryContext(e)) {
            return false;
        }

        VirtualFile file = resolveTargetFile(e);
        if (file == null || !file.isInLocalFileSystem()) {
            return false;
        }

        GitLocation location = GitHubUrlResolver.findGitLocation(file.toNioPath());
        if (!GitHubUrlResolver.hasGithubRemoteFast(location)) {
            return false;
        }

        Project project = e.getProject();
        BuiltInGithubCoverageChecker.Inspection inspection =
                BuiltInGithubCoverageChecker.inspect(project, file, location.repoRoot());
        if (inspection.coveredByBuiltIn()) {
            return false;
        }
        return file.isDirectory() || !inspection.ignoredInMatchingRepo();
    }

    static void resolveAndExecute(AnActionEvent e, BiConsumer<Project, String> onSuccess) {
        Project project = e.getProject();
        VirtualFile file = resolveTargetFile(e);
        if (file == null || !file.isInLocalFileSystem()) {
            return;
        }
        Path targetPath = file.toNioPath();
        LineRange lineRange = file.isDirectory() ? null : extractLineRange(e.getData(CommonDataKeys.EDITOR));

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String url = GitHubUrlResolver.resolveGithubUrl(targetPath, lineRange);
                ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(project, url));
            } catch (Exception ex) {
                String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                ApplicationManager.getApplication().invokeLater(() ->
                        notify(project, "Open on GitHub Failed", message, NotificationType.ERROR));
            }
        });
    }

    static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project);
    }

    private static VirtualFile resolveTargetFile(AnActionEvent e) {
        VirtualFile single = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (single != null) {
            return single;
        }
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        return (files != null && files.length > 0) ? files[0] : null;
    }

    private static LineRange extractLineRange(Editor editor) {
        if (editor == null) {
            return null;
        }
        Document document = editor.getDocument();
        if (document.getLineCount() < 1) {
            return null;
        }
        Caret caret = editor.getCaretModel().getCurrentCaret();
        int selectionStart = caret.getSelectionStart();
        int selectionEnd = caret.getSelectionEnd();
        int begin = document.getLineNumber(selectionStart);
        int end = document.getLineNumber(selectionEnd);
        if (end > begin && document.getLineStartOffset(end) == selectionEnd) {
            end--;
        }
        return new LineRange(begin + 1, end + 1);
    }
}
