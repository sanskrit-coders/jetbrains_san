package io.github.sanskritcoders.githubsan;

import io.github.sanskritcoders.githubsan.GitHubUrlResolver.CommandResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Discovers Git submodules bottom-up, silently pushes unpushed submodule commits before parent pushes
 * when {@code push.recurseSubmodules} is not configured, and configures Git submodule auto-push
 * ({@code push.recurseSubmodules=on-demand} and optional {@code pre-push} hook).
 */
public final class SubmodulePushSupport {
    private static final Duration GIT_QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration GIT_PUSH_TIMEOUT = Duration.ofMinutes(3);
    private static final String HOOK_BEGIN_MARKER = "# >>> github-san submodule auto-push >>>";
    private static final String HOOK_END_MARKER = "# <<< github-san submodule auto-push <<<";

    public enum PushMode {
        /** Auto-push submodules only when {@code push.recurseSubmodules} is not enabled. */
        AUTO_WHEN_NOT_CONFIGURED,
        /** Push all submodules with unpushed commits regardless of {@code push.recurseSubmodules}. */
        ALWAYS
    }

    public record SubmoduleEntry(String relativePath, Path root, String configuredBranch) {}

    public record PushResult(
            List<String> pushedSubmodules,
            List<String> skippedDetachedSubmodules,
            Map<String, String> failedSubmodules,
            boolean skippedBecauseConfigured) {
        public boolean hasFailures() {
            return !failedSubmodules.isEmpty();
        }

        public String formatFailureSummary() {
            if (failedSubmodules.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : failedSubmodules.entrySet()) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(entry.getKey()).append(": ").append(entry.getValue());
            }
            return sb.toString();
        }
    }

    public record HookInstallResult(Path hookFile, boolean updated, String customHooksPathWarning) {}

    private enum UpstreamState {
        UP_TO_DATE,
        DETACHED,
        NO_REMOTE,
        UNTRACKED_BRANCH,
        NEEDS_PUSH_EXISTING_UPSTREAM,
        NEEDS_PUSH_SET_UPSTREAM
    }

    private record PushPlan(UpstreamState state, String remoteName, String branchName) {}

    private SubmodulePushSupport() {}

    /**
     * Discovers initialized submodules under {@code repoRoot} in bottom-up (post-order) dependency order.
     */
    public static List<SubmoduleEntry> discoverSubmodulesBottomUp(Path repoRoot)
            throws IOException, InterruptedException {
        return discoverSubmodulesBottomUp(repoRoot, null);
    }

    static List<SubmoduleEntry> discoverSubmodulesBottomUp(Path repoRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return List.of();
        }
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        List<SubmoduleEntry> result = new ArrayList<>();
        Set<Path> visitedRoots = new HashSet<>();
        visitedRoots.add(toRealOrNormalized(normalizedRoot));
        collectSubmodulesRecursive(normalizedRoot, normalizedRoot, visitedRoots, result, envOverrides);
        return List.copyOf(result);
    }

    /**
     * Reads the effective {@code push.recurseSubmodules} value visible inside {@code repoRoot},
     * or returns {@code null} if unset.
     */
    public static String getEffectivePushRecurseSubmodules(Path repoRoot)
            throws IOException, InterruptedException {
        return getEffectivePushRecurseSubmodules(repoRoot, null);
    }

    static String getEffectivePushRecurseSubmodules(Path repoRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return null;
        }
        CommandResult result = GitHubUrlResolver.runGit(
                repoRoot, GIT_QUERY_TIMEOUT, envOverrides, "config", "--get", "push.recurseSubmodules");
        if (result.exitCode() != 0) {
            return null;
        }
        String trimmed = result.stdout().trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public static boolean isRecurseSubmodulesEnabled(String configValue) {
        if (configValue == null) {
            return false;
        }
        String normalized = configValue.trim().toLowerCase(Locale.ROOT);
        return "on-demand".equals(normalized) || "only".equals(normalized) || "check".equals(normalized);
    }

    /**
     * Enables {@code push.recurseSubmodules = on-demand} in global Git configuration.
     */
    public static void enableGlobalRecurseSubmodules(Path workingDir)
            throws IOException, InterruptedException {
        enableGlobalRecurseSubmodules(workingDir, null);
    }

    static void enableGlobalRecurseSubmodules(Path workingDir, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        Path effectiveDir = workingDir != null && Files.isDirectory(workingDir)
                ? workingDir
                : Path.of(System.getProperty("user.home", "."));
        CommandResult result = GitHubUrlResolver.runGit(
                effectiveDir,
                GIT_QUERY_TIMEOUT,
                envOverrides,
                "config",
                "--global",
                "push.recurseSubmodules",
                "on-demand");
        if (result.exitCode() != 0) {
            throw new IOException(extractError(result, "git config --global push.recurseSubmodules on-demand failed"));
        }
    }

    /**
     * Installs or updates the idempotent {@code pre-push} hook inside {@code repoRoot} so CLI and external pushes
     * also push ahead submodules silently in bottom-up order.
     */
    public static HookInstallResult installPrePushHook(Path repoRoot)
            throws IOException, InterruptedException {
        return installPrePushHook(repoRoot, null);
    }

    static HookInstallResult installPrePushHook(Path repoRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            throw new IOException("Repository directory does not exist: " + repoRoot);
        }
        String customHooksWarning = checkCustomHooksPath(repoRoot, envOverrides);
        Path hooksDir = resolveDefaultHooksDir(repoRoot, envOverrides);
        Files.createDirectories(hooksDir);
        Path hookFile = hooksDir.resolve("pre-push");

        String existing = Files.exists(hookFile) ? Files.readString(hookFile, StandardCharsets.UTF_8) : "";
        String updatedContent = upsertManagedHookBlock(existing, buildHookSnippet());
        boolean changed = !updatedContent.equals(existing);
        if (changed) {
            Files.writeString(hookFile, updatedContent, StandardCharsets.UTF_8);
        }
        hookFile.toFile().setExecutable(true, false);
        return new HookInstallResult(hookFile, changed, customHooksWarning);
    }

    /**
     * Inspects all initialized submodules bottom-up and pushes those with unpushed commits.
     * In {@link PushMode#AUTO_WHEN_NOT_CONFIGURED}, returns immediately if {@code push.recurseSubmodules}
     * is already enabled.
     */
    public static PushResult pushPendingSubmodules(
            Path repoRoot,
            Set<Path> excludedRoots,
            PushMode mode,
            BooleanSupplier isCanceled,
            Consumer<String> progressReporter)
            throws IOException, InterruptedException {
        return pushPendingSubmodules(repoRoot, excludedRoots, mode, isCanceled, progressReporter, null);
    }

    static PushResult pushPendingSubmodules(
            Path repoRoot,
            Set<Path> excludedRoots,
            PushMode mode,
            BooleanSupplier isCanceled,
            Consumer<String> progressReporter,
            Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return new PushResult(List.of(), List.of(), Map.of(), false);
        }
        if (mode == PushMode.AUTO_WHEN_NOT_CONFIGURED) {
            String recurseConfig = getEffectivePushRecurseSubmodules(repoRoot, envOverrides);
            if (isRecurseSubmodulesEnabled(recurseConfig)) {
                return new PushResult(List.of(), List.of(), Map.of(), true);
            }
        }

        Set<Path> normalizedExcluded = normalizeExcludedRoots(excludedRoots);
        List<SubmoduleEntry> submodules = discoverSubmodulesBottomUp(repoRoot, envOverrides);
        List<String> pushed = new ArrayList<>();
        List<String> skippedDetached = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        for (SubmoduleEntry submodule : submodules) {
            if (isCanceled != null && isCanceled.getAsBoolean()) {
                throw new InterruptedException("Submodule push canceled");
            }
            if (normalizedExcluded.contains(toRealOrNormalized(submodule.root()))) {
                continue;
            }
            PushPlan plan = computePushPlan(submodule, envOverrides);
            if (plan.state() == UpstreamState.DETACHED) {
                skippedDetached.add(submodule.relativePath());
                continue;
            }
            if (plan.state() != UpstreamState.NEEDS_PUSH_EXISTING_UPSTREAM
                    && plan.state() != UpstreamState.NEEDS_PUSH_SET_UPSTREAM) {
                continue;
            }

            if (progressReporter != null) {
                progressReporter.accept("Pushing submodule " + submodule.relativePath() + "...");
            }
            CommandResult pushResult = executeSubmodulePush(submodule.root(), plan, envOverrides);
            if (pushResult.exitCode() == 0) {
                pushed.add(submodule.relativePath());
            } else {
                failed.put(submodule.relativePath(), extractError(pushResult, "git push failed"));
                break;
            }
        }

        return new PushResult(
                List.copyOf(pushed),
                List.copyOf(skippedDetached),
                Collections.unmodifiableMap(failed),
                false);
    }

    /**
     * Pushes ahead submodules bottom-up and then pushes {@code repoRoot} itself.
     * Intended for explicit Push Submodules & Repository actions.
     */
    public static PushResult pushSubmodulesAndRepo(
            Path repoRoot,
            BooleanSupplier isCanceled,
            Consumer<String> progressReporter,
            Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        PushResult subResult = pushPendingSubmodules(
                repoRoot, Set.of(), PushMode.ALWAYS, isCanceled, progressReporter, envOverrides);
        if (subResult.hasFailures()) {
            return subResult;
        }
        if (isCanceled != null && isCanceled.getAsBoolean()) {
            throw new InterruptedException("Repository push canceled");
        }
        if (progressReporter != null) {
            progressReporter.accept("Pushing repository " + repoRoot.getFileName() + "...");
        }
        CommandResult parentPush = GitHubUrlResolver.runGit(
                repoRoot, GIT_PUSH_TIMEOUT, envOverrides, "push");
        if (parentPush.exitCode() != 0) {
            Map<String, String> failures = new LinkedHashMap<>(subResult.failedSubmodules());
            failures.put(".", extractError(parentPush, "git push failed"));
            return new PushResult(
                    subResult.pushedSubmodules(),
                    subResult.skippedDetachedSubmodules(),
                    Collections.unmodifiableMap(failures),
                    false);
        }
        return subResult;
    }

    private static void collectSubmodulesRecursive(
            Path rootRepo,
            Path currentDir,
            Set<Path> visitedRoots,
            List<SubmoduleEntry> out,
            Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        Path gitmodules = currentDir.resolve(".gitmodules");
        if (!Files.isRegularFile(gitmodules)) {
            return;
        }
        Map<String, String> pathsByName = readGitmodulesPropertyMap(currentDir, "path", envOverrides);
        if (pathsByName.isEmpty()) {
            return;
        }
        Map<String, String> branchByName = readGitmodulesPropertyMap(currentDir, "branch", envOverrides);
        for (Map.Entry<String, String> entry : pathsByName.entrySet()) {
            String name = entry.getKey();
            String rel = entry.getValue();
            if (rel == null || rel.isBlank()) {
                continue;
            }
            Path subRoot = currentDir.resolve(rel).toAbsolutePath().normalize();
            if (!Files.isDirectory(subRoot) || !Files.exists(subRoot.resolve(".git"))) {
                continue;
            }
            Path canonicalSub = toRealOrNormalized(subRoot);
            if (!visitedRoots.add(canonicalSub)) {
                continue;
            }
            collectSubmodulesRecursive(rootRepo, subRoot, visitedRoots, out, envOverrides);
            String relFromRoot = toUnixPath(rootRepo.relativize(subRoot));
            out.add(new SubmoduleEntry(relFromRoot, subRoot, branchByName.get(name)));
        }
    }

    private static Map<String, String> readGitmodulesPropertyMap(
            Path dir, String property, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult result = GitHubUrlResolver.runGit(
                dir,
                GIT_QUERY_TIMEOUT,
                envOverrides,
                "config",
                "--file",
                ".gitmodules",
                "--get-regexp",
                "^submodule\\..*\\." + property + "$");
        if (result.exitCode() != 0 && result.stdout().isBlank()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        String prefix = "submodule.";
        String suffix = "." + property;
        for (String line : result.stdout().split("\\R")) {
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx <= 0) {
                continue;
            }
            String fullKey = line.substring(0, spaceIdx);
            String value = line.substring(spaceIdx + 1).trim();
            if (fullKey.startsWith(prefix) && fullKey.endsWith(suffix)) {
                String name = fullKey.substring(prefix.length(), fullKey.length() - suffix.length());
                map.put(name, value);
            }
        }
        return map;
    }

    private static PushPlan computePushPlan(SubmoduleEntry submodule, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        Path subRoot = submodule.root();
        CommandResult branchRes = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (branchRes.exitCode() != 0) {
            return new PushPlan(UpstreamState.DETACHED, null, null);
        }
        String branch = branchRes.stdout().trim();
        if (branch.isEmpty()) {
            return new PushPlan(UpstreamState.DETACHED, null, null);
        }

        CommandResult upstreamRes = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        if (upstreamRes.exitCode() == 0 && !upstreamRes.stdout().isBlank()) {
            String upstream = upstreamRes.stdout().trim();
            int ahead = countAheadCommits(subRoot, upstream, envOverrides);
            return ahead > 0
                    ? new PushPlan(UpstreamState.NEEDS_PUSH_EXISTING_UPSTREAM, null, branch)
                    : new PushPlan(UpstreamState.UP_TO_DATE, null, branch);
        }

        String remote = resolvePreferredRemote(subRoot, envOverrides);
        if (remote == null) {
            return new PushPlan(UpstreamState.NO_REMOTE, null, branch);
        }

        String remoteRef = "refs/remotes/" + remote + "/" + branch;
        CommandResult refCheck = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "show-ref", "--verify", "--quiet", remoteRef);
        if (refCheck.exitCode() == 0) {
            int ahead = countAheadCommits(subRoot, remote + "/" + branch, envOverrides);
            return ahead > 0
                    ? new PushPlan(UpstreamState.NEEDS_PUSH_SET_UPSTREAM, remote, branch)
                    : new PushPlan(UpstreamState.UP_TO_DATE, remote, branch);
        }

        if (branch.equals(submodule.configuredBranch()) && hasValidHeadCommit(subRoot, envOverrides)) {
            return new PushPlan(UpstreamState.NEEDS_PUSH_SET_UPSTREAM, remote, branch);
        }
        return new PushPlan(UpstreamState.UNTRACKED_BRANCH, remote, branch);
    }

    private static CommandResult executeSubmodulePush(
            Path subRoot, PushPlan plan, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        if (plan.state() == UpstreamState.NEEDS_PUSH_SET_UPSTREAM) {
            return GitHubUrlResolver.runGit(
                    subRoot, GIT_PUSH_TIMEOUT, envOverrides, "push", "-u", plan.remoteName(), plan.branchName());
        }
        return GitHubUrlResolver.runGit(subRoot, GIT_PUSH_TIMEOUT, envOverrides, "push");
    }

    private static int countAheadCommits(Path subRoot, String upstreamRef, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "rev-list", "--count", upstreamRef + "..HEAD");
        if (res.exitCode() != 0) {
            return 0;
        }
        try {
            return Integer.parseInt(res.stdout().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean hasValidHeadCommit(Path subRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "rev-parse", "--verify", "--quiet", "HEAD");
        return res.exitCode() == 0;
    }

    private static String resolvePreferredRemote(Path subRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(
                subRoot, GIT_QUERY_TIMEOUT, envOverrides, "remote");
        if (res.exitCode() != 0 || res.stdout().isBlank()) {
            return null;
        }
        List<String> remotes = new ArrayList<>();
        for (String line : res.stdout().split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                remotes.add(trimmed);
            }
        }
        if (remotes.contains("origin")) {
            return "origin";
        }
        return remotes.size() == 1 ? remotes.get(0) : null;
    }

    private static String checkCustomHooksPath(Path repoRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(
                repoRoot, GIT_QUERY_TIMEOUT, envOverrides, "config", "--get", "core.hooksPath");
        if (res.exitCode() == 0 && !res.stdout().isBlank()) {
            return "Git core.hooksPath is set to '" + res.stdout().trim() + "', which may override .git/hooks/pre-push.";
        }
        return null;
    }

    private static Path resolveDefaultHooksDir(Path repoRoot, Map<String, String> envOverrides)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(
                repoRoot, GIT_QUERY_TIMEOUT, envOverrides, "rev-parse", "--git-common-dir");
        if (res.exitCode() != 0 || res.stdout().isBlank()) {
            throw new IOException(extractError(res, "Not a Git repository: " + repoRoot));
        }
        Path commonDir = Path.of(res.stdout().trim());
        if (!commonDir.isAbsolute()) {
            commonDir = repoRoot.resolve(commonDir);
        }
        return commonDir.normalize().resolve("hooks");
    }

    static String upsertManagedHookBlock(String existingContent, String managedBlock) {
        String base = existingContent == null ? "" : existingContent;
        int beginIdx = base.indexOf(HOOK_BEGIN_MARKER);
        int endIdx = beginIdx >= 0 ? base.indexOf(HOOK_END_MARKER, beginIdx) : -1;
        if (beginIdx >= 0 && endIdx >= beginIdx) {
            int afterEnd = endIdx + HOOK_END_MARKER.length();
            String prefix = base.substring(0, beginIdx);
            String suffix = base.substring(afterEnd);
            return prefix + managedBlock + suffix;
        }
        if (base.isBlank()) {
            return "#!/usr/bin/env sh\n" + managedBlock + "\n";
        }
        String separator = base.endsWith("\n") ? "\n" : "\n\n";
        return base + separator + managedBlock + "\n";
    }

    static String buildHookSnippet() {
        return String.join(
                "\n",
                HOOK_BEGIN_MARKER,
                "if [ \"${GITHUB_SAN_SUBMODULE_HOOK_ACTIVE:-0}\" != \"1\" ] && [ -f .gitmodules ]; then",
                "  GITHUB_SAN_SUBMODULE_HOOK_ACTIVE=1",
                "  export GITHUB_SAN_SUBMODULE_HOOK_ACTIVE",
                "  _github_san_push_submodules() {",
                "    _parent=\"$1\"",
                "    [ -f \"$_parent/.gitmodules\" ] || return 0",
                "    _paths=$(git -C \"$_parent\" config --file .gitmodules --get-regexp '^submodule\\..*\\.path$' 2>/dev/null | sed 's/^[^ ]* //')",
                "    [ -n \"$_paths\" ] || return 0",
                "    _old_ifs=$IFS",
                "    IFS='\n'",
                "    for _rel in $_paths; do",
                "      [ -n \"$_rel\" ] || continue",
                "      _sub=\"$_parent/$_rel\"",
                "      [ -e \"$_sub/.git\" ] || continue",
                "      ( _github_san_push_submodules \"$_sub\" ) || return 1",
                "      _branch=$(git -C \"$_sub\" symbolic-ref --quiet --short HEAD 2>/dev/null || true)",
                "      [ -n \"$_branch\" ] || continue",
                "      if _upstream=$(git -C \"$_sub\" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then",
                "        _ahead=$(git -C \"$_sub\" rev-list --count \"$_upstream..HEAD\" 2>/dev/null || echo 0)",
                "        if [ \"$_ahead\" -gt 0 ]; then",
                "          git -C \"$_sub\" push >&2 || return 1",
                "        fi",
                "      else",
                "        _remote=$(git -C \"$_sub\" remote | grep -x origin || git -C \"$_sub\" remote | head -n 1)",
                "        if [ -n \"$_remote\" ] && git -C \"$_sub\" show-ref --verify --quiet \"refs/remotes/$_remote/$_branch\" 2>/dev/null; then",
                "          _ahead=$(git -C \"$_sub\" rev-list --count \"refs/remotes/$_remote/$_branch..HEAD\" 2>/dev/null || echo 0)",
                "          if [ \"$_ahead\" -gt 0 ]; then",
                "            git -C \"$_sub\" push -u \"$_remote\" \"$_branch\" >&2 || return 1",
                "          fi",
                "        fi",
                "      fi",
                "    done",
                "    IFS=$_old_ifs",
                "  }",
                "  ( _github_san_push_submodules \".\" ) || exit 1",
                "fi",
                HOOK_END_MARKER);
    }

    private static Set<Path> normalizeExcludedRoots(Set<Path> excludedRoots) {
        if (excludedRoots == null || excludedRoots.isEmpty()) {
            return Set.of();
        }
        Set<Path> result = new HashSet<>(excludedRoots.size());
        for (Path p : excludedRoots) {
            if (p != null) {
                result.add(toRealOrNormalized(p));
            }
        }
        return result;
    }

    private static Path toRealOrNormalized(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String extractError(CommandResult result, String fallback) {
        String err = result.stderr().trim();
        if (!err.isEmpty()) {
            return err;
        }
        String out = result.stdout().trim();
        return out.isEmpty() ? fallback : out;
    }

    static Map<String, String> envWithGlobalConfig(Path globalConfigFile) {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_CONFIG_GLOBAL", globalConfigFile.toAbsolutePath().toString());
        return env;
    }
}
