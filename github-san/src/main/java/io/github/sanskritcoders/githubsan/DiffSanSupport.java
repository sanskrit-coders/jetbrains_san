package io.github.sanskritcoders.githubsan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code local-diff-san} invocations for diffing files/directories against Git {@code HEAD}
 * or comparing two selected files side by side with {@code diff_san}.
 */
public final class DiffSanSupport {
    private static final String RUNNING_PREFIX = "local-diff-san running at ";
    private static final Pattern RUNNING_URL_PATTERN = Pattern.compile("local-diff-san running at (https?://\\S+)");
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(4);

    public enum DiffMode {
        HEAD_SINGLE_FILE,
        HEAD_DIRECTORY,
        TWO_FILES,
        HEAD_MULTI_FILES
    }

    public record DiffPlan(DiffMode mode, String label, List<String> arguments) {}

    public record LaunchResult(boolean started, String url, String errorMessage) {}

    private DiffSanSupport() {}

    /**
     * Builds the diff invocation plan for the selected filesystem paths, or returns {@code null}
     * if the selection cannot be diffed against Git {@code HEAD} or as a two-file pair.
     */
    public static DiffPlan planSelection(List<Path> rawPaths) {
        List<Path> paths = normalizeUniqueExistingPaths(rawPaths);
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        if (paths.size() == 1) {
            return planSinglePath(paths.get(0));
        }
        if (paths.size() == 2) {
            Path left = paths.get(0);
            Path right = paths.get(1);
            if (Files.isRegularFile(left) && Files.isRegularFile(right)) {
                return new DiffPlan(
                        DiffMode.TWO_FILES,
                        "Diff Two Files (diff_san)",
                        List.of(left.toString(), right.toString()));
            }
            return null;
        }
        List<String> args = new ArrayList<>(paths.size() + 1);
        args.add("--head");
        for (Path path : paths) {
            if (!Files.isRegularFile(path) || GitHubUrlResolver.findGitLocation(path) == null) {
                return null;
            }
            args.add(path.toString());
        }
        return new DiffPlan(
                DiffMode.HEAD_MULTI_FILES,
                "Diff Files against HEAD (diff_san)",
                List.copyOf(args));
    }

    /**
     * Resolves the {@code local-diff-san} binary from {@code DIFF_SAN_BIN}, {@code PATH},
     * project virtualenvs, or standard user install locations.
     */
    public static Path resolveExecutable(Path projectBaseDir) {
        return resolveExecutable(
                System.getenv("DIFF_SAN_BIN"),
                System.getenv("PATH"),
                defaultUserHome(),
                projectBaseDir);
    }

    static Path resolveExecutable(String envOverride, String pathEnv, Path userHome, Path projectBaseDir) {
        if (envOverride != null && !envOverride.isBlank()) {
            Path override = expandUserHome(envOverride.trim(), userHome);
            if (Files.isDirectory(override)) {
                Path inside = findInDirectory(override);
                if (inside != null) {
                    return inside;
                }
            } else if (isExecutableFile(override)) {
                return override;
            }
        }
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String entry : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    Path found = findInDirectory(expandUserHome(entry.trim(), userHome));
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        for (Path binDir : candidateBinDirectories(userHome, projectBaseDir)) {
            Path found = findInDirectory(binDir);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Builds the full command list for executing {@code executable} with {@code plan}.
     */
    public static List<String> buildCommand(Path executable, DiffPlan plan) {
        List<String> command = new ArrayList<>(1 + plan.arguments().size());
        command.add(executable.toString());
        command.addAll(plan.arguments());
        return List.copyOf(command);
    }

    /**
     * Spawns {@code local-diff-san} asynchronously and verifies initial startup cleanly without blocking indefinitely.
     */
    public static LaunchResult launch(Path projectBaseDir, DiffPlan plan) {
        return launch(resolveExecutable(projectBaseDir), plan, DEFAULT_STARTUP_TIMEOUT);
    }

    static LaunchResult launch(Path executable, DiffPlan plan, Duration startupTimeout) {
        if (executable == null) {
            return new LaunchResult(
                    false,
                    null,
                    "Could not find 'local-diff-san'. Install diff_san (e.g. ~/gitland/python_venv/bin/local-diff-san) or set DIFF_SAN_BIN.");
        }
        Path logFile = null;
        try {
            logFile = Files.createTempFile("diff-san-", ".log");
            logFile.toFile().deleteOnExit();

            ProcessBuilder pb = new ProcessBuilder(buildCommand(executable, plan));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            ensureBinDirOnPath(pb, executable.getParent());

            Process process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {}
            long timeoutNanos = Math.max(200_000_000L, startupTimeout.toNanos());
            long deadline = System.nanoTime() + timeoutNanos;
            while (true) {
                String output = readLogSafely(logFile);
                String runningUrl = extractRunningUrl(output);
                if (runningUrl != null) {
                    return new LaunchResult(true, runningUrl, null);
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    if (exitCode == 0) {
                        return new LaunchResult(true, null, null);
                    }
                    return new LaunchResult(false, null, formatFailure(exitCode, output));
                }
                if (System.nanoTime() >= deadline) {
                    return new LaunchResult(true, null, null);
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new LaunchResult(true, null, null);
                }
            }
        } catch (IOException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return new LaunchResult(false, null, "Failed to start local-diff-san: " + msg);
        }
    }

    static String extractRunningUrl(String output) {
        if (output == null || !output.contains(RUNNING_PREFIX)) {
            return null;
        }
        Matcher matcher = RUNNING_URL_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static DiffPlan planSinglePath(Path target) {
        if (GitHubUrlResolver.findGitLocation(target) == null) {
            return null;
        }
        if (Files.isDirectory(target)) {
            return new DiffPlan(
                    DiffMode.HEAD_DIRECTORY,
                    "Diff Directory against HEAD (diff_san)",
                    List.of(target.toString()));
        }
        if (Files.isRegularFile(target)) {
            return new DiffPlan(
                    DiffMode.HEAD_SINGLE_FILE,
                    "Diff against HEAD (diff_san)",
                    List.of(target.toString()));
        }
        return null;
    }

    private static List<Path> normalizeUniqueExistingPaths(List<Path> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return null;
        }
        Set<Path> unique = new LinkedHashSet<>();
        for (Path raw : rawPaths) {
            if (raw == null) {
                return null;
            }
            Path normalized = raw.toAbsolutePath().normalize();
            if (!Files.exists(normalized)) {
                return null;
            }
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private static List<Path> candidateBinDirectories(Path userHome, Path projectBaseDir) {
        List<Path> dirs = new ArrayList<>();
        if (projectBaseDir != null) {
            dirs.add(projectBaseDir.resolve(".venv").resolve("bin"));
            dirs.add(projectBaseDir.resolve("venv").resolve("bin"));
        }
        if (userHome != null) {
            dirs.add(userHome.resolve("gitland").resolve("python_venv").resolve("bin"));
            dirs.add(userHome.resolve(".local").resolve("bin"));
            dirs.add(userHome.resolve(".venv").resolve("bin"));
            dirs.add(userHome.resolve("venv").resolve("bin"));
            dirs.add(userHome.resolve("gitland").resolve("sanskrit-coders").resolve("diff_san").resolve(".venv").resolve("bin"));
        }
        return dirs;
    }

    private static Path findInDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        for (String name : candidateBinaryNames()) {
            Path candidate = directory.resolve(name);
            if (isExecutableFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static List<String> candidateBinaryNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("local-diff-san.exe", "local-diff-san.cmd", "local_diff_san.exe", "local-diff-san", "local_diff_san");
        }
        return List.of("local-diff-san", "local_diff_san");
    }

    private static boolean isExecutableFile(Path candidate) {
        return candidate != null && Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }

    private static Path expandUserHome(String value, Path userHome) {
        if (userHome != null && (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\"))) {
            String remainder = value.length() <= 2 ? "" : value.substring(2);
            return remainder.isEmpty() ? userHome : userHome.resolve(remainder);
        }
        return Path.of(value);
    }

    private static Path defaultUserHome() {
        String home = System.getProperty("user.home");
        return (home == null || home.isBlank()) ? null : Path.of(home);
    }

    private static void ensureBinDirOnPath(ProcessBuilder pb, Path binDir) {
        if (binDir == null) {
            return;
        }
        String dirStr = binDir.toAbsolutePath().normalize().toString();
        String current = pb.environment().get("PATH");
        if (current == null || current.isBlank()) {
            pb.environment().put("PATH", dirStr);
        } else if (!current.contains(dirStr)) {
            pb.environment().put("PATH", dirStr + File.pathSeparator + current);
        }
    }

    private static String readLogSafely(Path logFile) {
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String formatFailure(int exitCode, String output) {
        String trimmed = output == null ? "" : output.trim();
        if (trimmed.isEmpty()) {
            return "local-diff-san exited with code " + exitCode;
        }
        return "local-diff-san exited with code " + exitCode + ": " + trimmed;
    }
}
