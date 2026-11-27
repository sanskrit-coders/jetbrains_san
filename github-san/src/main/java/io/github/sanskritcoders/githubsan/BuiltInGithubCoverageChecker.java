package io.github.sanskritcoders.githubsan;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Checks via optional reflection whether IntelliJ IDEA's built-in GitHub plugin already
 * provides an Open/Copy link action for a given {@link VirtualFile}, so the fallback
 * only activates where the built-in action is missing (for example submodules dropped by
 * {@code GHHostedRepositoriesManager} deduplication, unregistered submodules, or root directories).
 */
final class BuiltInGithubCoverageChecker {
    private static final Set<String> HIDDEN_STATUS_IDS = Set.of("UNKNOWN", "ADDED", "IGNORED");

    record Inspection(boolean coveredByBuiltIn, boolean ignoredInMatchingRepo) {}

    private BuiltInGithubCoverageChecker() {}

    static Inspection inspect(Project project, VirtualFile virtualFile, Path resolvedRepoRoot) {
        if (project == null || virtualFile == null) {
            return new Inspection(false, false);
        }

        List<ClassLoader> loaders = candidateClassLoaders();
        try {
            String statusId = getFileStatusId(loaders, project, virtualFile);
            boolean hiddenStatus = statusId != null && HIDDEN_STATUS_IDS.contains(statusId);

            Class<?> vcsUtilClass = loadClass(loaders, "com.intellij.vcsUtil.VcsUtil");
            Object filePath = vcsUtilClass.getMethod("getFilePath", VirtualFile.class).invoke(null, virtualFile);
            if (filePath == null) {
                return new Inspection(false, false);
            }

            Class<?> gitUtilClass = loadClass(loaders, "git4idea.GitUtil");
            Object gitRepoManager = gitUtilClass.getMethod("getRepositoryManager", Project.class).invoke(null, project);
            if (gitRepoManager == null) {
                return new Inspection(false, false);
            }

            Class<?> filePathClass = loadClass(loaders, "com.intellij.openapi.vcs.FilePath");
            Object gitRepo = gitRepoManager.getClass()
                    .getMethod("getRepositoryForFileQuick", filePathClass)
                    .invoke(gitRepoManager, filePath);
            if (gitRepo == null) {
                return new Inspection(false, false);
            }

            Class<?> repositoryInterface = loadClass(loaders, "com.intellij.dvcs.repo.Repository");
            VirtualFile vcsRepoRoot = (VirtualFile) repositoryInterface.getMethod("getRoot").invoke(gitRepo);
            boolean sameRepo = vcsRepoRoot != null
                    && vcsRepoRoot.isInLocalFileSystem()
                    && resolvedRepoRoot != null
                    && vcsRepoRoot.toNioPath().toAbsolutePath().normalize().equals(resolvedRepoRoot);
            boolean ignoredInSameRepo = sameRepo && "IGNORED".equals(statusId);

            if (hiddenStatus && virtualFile.isInLocalFileSystem()) {
                return new Inspection(false, ignoredInSameRepo);
            }

            Class<?> vcsFileUtilClass = loadClass(loaders, "com.intellij.vcsUtil.VcsFileUtil");
            String relPath = (String) vcsFileUtilClass
                    .getMethod("relativePath", VirtualFile.class, filePathClass)
                    .invoke(null, vcsRepoRoot, filePath);
            if (relPath == null || relPath.isBlank()) {
                return new Inspection(false, ignoredInSameRepo);
            }

            Class<?> ghManagerClass = loadClass(loaders, "org.jetbrains.plugins.github.util.GHHostedRepositoriesManager");
            Object ghManager = project.getService(ghManagerClass);
            if (ghManager == null) {
                return new Inspection(false, ignoredInSameRepo);
            }

            Object stateFlow = ghManagerClass.getMethod("getKnownRepositoriesState").invoke(ghManager);
            Class<?> stateFlowInterface = loadClass(loaders, "kotlinx.coroutines.flow.StateFlow");
            Object knownObj = stateFlowInterface.getMethod("getValue").invoke(stateFlow);
            if (!(knownObj instanceof Collection<?> knownMappings)) {
                return new Inspection(false, ignoredInSameRepo);
            }

            Class<?> mappingInterface = loadClass(loaders, "git4idea.remote.hosting.HostedGitRepositoryMapping");
            Class<?> remoteCoordsClass = loadClass(loaders, "git4idea.remote.GitRemoteUrlCoordinates");
            var getRemote = mappingInterface.getMethod("getRemote");
            var getRepository = remoteCoordsClass.getMethod("getRepository");

            for (Object mapping : knownMappings) {
                if (mapping == null) {
                    continue;
                }
                Object remote = getRemote.invoke(mapping);
                Object mappedRepo = remote == null ? null : getRepository.invoke(remote);
                if (gitRepo.equals(mappedRepo)) {
                    return new Inspection(true, ignoredInSameRepo);
                }
            }
            return new Inspection(false, ignoredInSameRepo);
        } catch (Throwable ignored) {
            return new Inspection(false, false);
        }
    }

    private static List<ClassLoader> candidateClassLoaders() {
        List<ClassLoader> loaders = new ArrayList<>(3);
        loaders.add(BuiltInGithubCoverageChecker.class.getClassLoader());
        try {
            AnAction ghAction = ActionManager.getInstance().getAction("Github.Open.In.Browser");
            if (ghAction != null && !loaders.contains(ghAction.getClass().getClassLoader())) {
                loaders.add(ghAction.getClass().getClassLoader());
            }
            AnAction gitAction = ActionManager.getInstance().getAction("Git.Fetch");
            if (gitAction != null && !loaders.contains(gitAction.getClass().getClassLoader())) {
                loaders.add(gitAction.getClass().getClassLoader());
            }
        } catch (Throwable ignored) {
            // Ignore ActionManager lookup errors during headless tests or early startup.
        }
        return loaders;
    }

    private static Class<?> loadClass(List<ClassLoader> loaders, String name) throws ClassNotFoundException {
        for (ClassLoader cl : loaders) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {
                // Try next classloader.
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static String getFileStatusId(List<ClassLoader> loaders, Project project, VirtualFile virtualFile) {
        try {
            Class<?> clmClass = loadClass(loaders, "com.intellij.openapi.vcs.changes.ChangeListManager");
            Class<?> fileStatusInterface = loadClass(loaders, "com.intellij.openapi.vcs.FileStatus");
            Object clm = clmClass.getMethod("getInstance", Project.class).invoke(null, project);
            Object status = clmClass.getMethod("getStatus", VirtualFile.class).invoke(clm, virtualFile);
            Object id = status == null ? null : fileStatusInterface.getMethod("getId").invoke(status);
            return id instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
