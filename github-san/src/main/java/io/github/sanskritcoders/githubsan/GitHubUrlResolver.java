package io.github.sanskritcoders.githubsan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves local files and directories inside Git repositories, submodules, and worktrees
 * to their corresponding GitHub web URLs.
 */
public final class GitHubUrlResolver {
    private static final Pattern REMOTE_SECTION_PATTERN = Pattern.compile("^\\[remote\\s+\"([^\"]+)\"\\s*]$");
    private static final Pattern SCP_REMOTE_PATTERN = Pattern.compile("^(?:[^@]+@)?([^:/]+):(.+)$");

    public record GitLocation(Path repoRoot, Path gitDir) {}

    public record LineRange(int oneBasedStart, int oneBasedEnd) {}

    private record RemoteInfo(String name, String githubBaseUrl) {}

    record CommandResult(int exitCode, String stdout, String stderr) {}

    private GitHubUrlResolver() {}

    /**
     * Walks up from {@code target} to find the enclosing Git working tree root and resolved {@code .git} directory,
     * supporting normal repositories, Git submodules, and Git worktrees.
     */
    public static GitLocation findGitLocation(Path target) {
        if (target == null) {
            return null;
        }
        Path normalized = target.toAbsolutePath().normalize();
        Path current = Files.isDirectory(normalized) ? normalized : normalized.getParent();
        while (current != null) {
            Path dotGit = current.resolve(".git");
            if (Files.isDirectory(dotGit)) {
                return new GitLocation(current, dotGit);
            }
            if (Files.isRegularFile(dotGit)) {
                Path resolvedGitDir = resolveGitDirFile(current, dotGit);
                if (resolvedGitDir != null && Files.isDirectory(resolvedGitDir)) {
                    return new GitLocation(current, resolvedGitDir);
                }
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Fast, subprocess-free check used during action presentation updates to verify whether
     * the enclosing repository/submodule configures at least one GitHub remote.
     */
    public static boolean hasGithubRemoteFast(GitLocation location) {
        if (location == null) {
            return false;
        }
        for (String url : readRemoteUrlsFromConfig(location.gitDir()).values()) {
            if (parseGithubBaseUrl(url) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses common Git remote URL forms into a canonical {@code https://github.com/<owner>/<repo>} URL,
     * or returns {@code null} if the URL is not a GitHub repository URL.
     */
    public static String parseGithubBaseUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String host;
        String pathPart;
        if (trimmed.contains("://")) {
            try {
                URI uri = URI.create(trimmed);
                host = uri.getHost();
                pathPart = uri.getPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else {
            Matcher scp = SCP_REMOTE_PATTERN.matcher(trimmed);
            if (!scp.matches()) {
                return null;
            }
            host = scp.group(1);
            pathPart = scp.group(2);
        }

        if (host == null || !host.equalsIgnoreCase("github.com") || pathPart == null) {
            return null;
        }

        String cleaned = trimSlashes(pathPart);
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        cleaned = trimSlashes(cleaned);

        String[] parts = cleaned.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return "https://github.com/" + parts[0] + "/" + parts[1];
    }

    /**
     * Resolves {@code target} to its GitHub web URL using {@code git} metadata in the enclosing repository/submodule.
     */
    public static String resolveGithubUrl(Path target, LineRange lineRange) throws IOException, InterruptedException {
        Path normalized = target.toAbsolutePath().normalize();
        GitLocation location = findGitLocation(normalized);
        if (location == null) {
            throw new IllegalStateException("Not inside a Git repository: " + normalized);
        }

        Path repoRoot = location.repoRoot();
        Map<String, String> remoteUrls = readRemotesViaGit(repoRoot, location.gitDir());
        RemoteInfo remote = selectGithubRemote(repoRoot, remoteUrls);
        if (remote == null) {
            throw new IllegalStateException("No GitHub remote found for repository: " + repoRoot);
        }

        String ref = resolveRemoteRef(repoRoot, remote.name());
        Path rel = repoRoot.relativize(normalized);
        String relativePath = toUnixRelativePath(rel);

        boolean isDirectory = Files.isDirectory(normalized);
        if (relativePath.isEmpty()) {
            return remote.githubBaseUrl() + "/tree/" + encodePathSegments(ref);
        }

        String kind = isDirectory ? "tree" : "blob";
        StringBuilder url = new StringBuilder()
                .append(remote.githubBaseUrl())
                .append('/')
                .append(kind)
                .append('/')
                .append(encodePathSegments(ref))
                .append('/')
                .append(encodePathSegments(relativePath));

        if (!isDirectory && lineRange != null && lineRange.oneBasedStart() >= 1) {
            url.append("#L").append(lineRange.oneBasedStart());
            if (lineRange.oneBasedEnd() > lineRange.oneBasedStart()) {
                url.append("-L").append(lineRange.oneBasedEnd());
            }
        }
        return url.toString();
    }

    private static Path resolveGitDirFile(Path repoRoot, Path dotGitFile) {
        try {
            for (String rawLine : Files.readAllLines(dotGitFile, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.toLowerCase(Locale.ROOT).startsWith("gitdir:")) {
                    String target = line.substring("gitdir:".length()).trim();
                    if (!target.isEmpty()) {
                        return repoRoot.resolve(target).normalize();
                    }
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    static Map<String, String> readRemoteUrlsFromConfig(Path gitDir) {
        Map<String, String> remotes = new LinkedHashMap<>();
        Path configPath = resolveConfigPath(gitDir);
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return remotes;
        }

        try {
            String currentRemote = null;
            for (String rawLine : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    Matcher m = REMOTE_SECTION_PATTERN.matcher(line);
                    currentRemote = m.matches() ? m.group(1) : null;
                    continue;
                }
                if (currentRemote != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        if ("url".equalsIgnoreCase(key)) {
                            String value = line.substring(eq + 1).trim();
                            remotes.putIfAbsent(currentRemote, value);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Fall through with whatever remotes were parsed.
        }
        return remotes;
    }

    private static Path resolveConfigPath(Path gitDir) {
        Path directConfig = gitDir.resolve("config");
        if (Files.isRegularFile(directConfig)) {
            return directConfig;
        }
        Path commonDirFile = gitDir.resolve("commondir");
        if (Files.isRegularFile(commonDirFile)) {
            try {
                String rel = Files.readString(commonDirFile, StandardCharsets.UTF_8).trim();
                if (!rel.isEmpty()) {
                    Path commonConfig = gitDir.resolve(rel).normalize().resolve("config");
                    if (Files.isRegularFile(commonConfig)) {
                        return commonConfig;
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, String> readRemotesViaGit(Path repoRoot, Path gitDir)
            throws IOException, InterruptedException {
        CommandResult result = runGit(repoRoot, "remote", "-v");
        Map<String, String> remotes = new LinkedHashMap<>();
        if (result.exitCode() == 0) {
            for (String line : result.stdout().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length >= 2) {
                    remotes.putIfAbsent(tokens[0], tokens[1]);
                }
            }
        }
        if (remotes.isEmpty()) {
            remotes.putAll(readRemoteUrlsFromConfig(gitDir));
        }
        return remotes;
    }

    private static RemoteInfo selectGithubRemote(Path repoRoot, Map<String, String> remoteUrls)
            throws IOException, InterruptedException {
        String currentBranch = getCurrentBranch(repoRoot);
        if (currentBranch != null) {
            CommandResult trackedRemoteRes = runGit(repoRoot, "config", "--get", "branch." + currentBranch + ".remote");
            if (trackedRemoteRes.exitCode() == 0) {
                String trackedName = trackedRemoteRes.stdout().trim();
                String trackedBase = parseGithubBaseUrl(remoteUrls.get(trackedName));
                if (trackedBase != null) {
                    return new RemoteInfo(trackedName, trackedBase);
                }
            }
        }

        String originBase = parseGithubBaseUrl(remoteUrls.get("origin"));
        if (originBase != null) {
            return new RemoteInfo("origin", originBase);
        }

        for (Map.Entry<String, String> entry : remoteUrls.entrySet()) {
            String base = parseGithubBaseUrl(entry.getValue());
            if (base != null) {
                return new RemoteInfo(entry.getKey(), base);
            }
        }
        return null;
    }

    private static String resolveRemoteRef(Path repoRoot, String remoteName)
            throws IOException, InterruptedException {
        CommandResult upstreamRes = runGit(repoRoot, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
        if (upstreamRes.exitCode() == 0) {
            String upstream = upstreamRes.stdout().trim();
            String prefix = remoteName + "/";
            if (upstream.startsWith(prefix) && upstream.length() > prefix.length()) {
                return upstream.substring(prefix.length());
            }
        }

        String branch = getCurrentBranch(repoRoot);
        if (branch != null) {
            CommandResult remoteBranchRes = runGit(
                    repoRoot, "show-ref", "--verify", "--quiet", "refs/remotes/" + remoteName + "/" + branch);
            if (remoteBranchRes.exitCode() == 0) {
                return branch;
            }
        }

        CommandResult headShaRes = runGit(repoRoot, "rev-parse", "HEAD");
        if (headShaRes.exitCode() == 0 && !headShaRes.stdout().trim().isEmpty()) {
            return headShaRes.stdout().trim();
        }
        throw new IllegalStateException("Cannot resolve branch or commit for repository: " + repoRoot);
    }

    private static String getCurrentBranch(Path repoRoot) throws IOException, InterruptedException {
        CommandResult branchRes = runGit(repoRoot, "symbolic-ref", "-q", "--short", "HEAD");
        if (branchRes.exitCode() == 0) {
            String branch = branchRes.stdout().trim();
            if (!branch.isEmpty()) {
                return branch;
            }
        }
        return null;
    }

    static CommandResult runGit(Path repoRoot, String... args) throws IOException, InterruptedException {
        return runGit(repoRoot, Duration.ofSeconds(10), null, args);
    }

    static CommandResult runGit(
            Path repoRoot, Duration timeout, Map<String, String> envOverrides, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(args.length + 3);
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoRoot.toString());
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (envOverrides != null && !envOverrides.isEmpty()) {
            pb.environment().putAll(envOverrides);
        }
        Process process = pb.start();
        process.getOutputStream().close();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread outThread = drainStream(process.getInputStream(), outBuf);
        Thread errThread = drainStream(process.getErrorStream(), errBuf);

        long timeoutMs = Math.max(1000L, timeout.toMillis());
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out running git command: " + String.join(" ", cmd));
        }
        outThread.join(1000);
        errThread.join(1000);
        return new CommandResult(
                process.exitValue(),
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    private static Thread drainStream(InputStream in, ByteArrayOutputStream out) {
        Thread thread = new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Ignore stream read interruption.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static String encodePathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String[] segments = path.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private static String toUnixRelativePath(Path rel) {
        String s = rel.toString().replace('\\', '/');
        return ".".equals(s) ? "" : s;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
