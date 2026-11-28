package io.github.sanskritcoders.githubsan;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    private static final String APP_NAME = "local-diff-san";
    private static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8772/";
    private static final Duration SERVER_PROBE_TIMEOUT = Duration.ofMillis(700);
    private static final Duration SERVER_COMPARE_TIMEOUT = Duration.ofSeconds(10);

    public enum DiffMode {
        HEAD_SINGLE_FILE,
        HEAD_DIRECTORY,
        TWO_FILES,
        HEAD_MULTI_FILES
    }

    public record DiffPlan(DiffMode mode, String label, List<String> arguments) {}

    public record LaunchResult(boolean started, String url, String errorMessage) {}

    record ServerReuseConfig(Path registryPath, List<String> fallbackUrls) {
        static final ServerReuseConfig DISABLED = new ServerReuseConfig(null, List.of());

        static ServerReuseConfig defaults() {
            return new ServerReuseConfig(defaultServerRegistryPath(), List.of(DEFAULT_SERVER_URL));
        }
    }

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
     * Reuses a healthy running {@code local-diff-san} server if available; otherwise spawns one cleanly.
     */
    public static LaunchResult launch(Path projectBaseDir, DiffPlan plan) {
        LaunchResult reused = tryReuseRunningServer(plan, ServerReuseConfig.defaults());
        if (reused != null) {
            return reused;
        }
        return launch(resolveExecutable(projectBaseDir), plan, DEFAULT_STARTUP_TIMEOUT, ServerReuseConfig.DISABLED);
    }

    static LaunchResult launch(Path executable, DiffPlan plan, Duration startupTimeout) {
        return launch(executable, plan, startupTimeout, ServerReuseConfig.DISABLED);
    }

    static LaunchResult launch(
            Path executable, DiffPlan plan, Duration startupTimeout, ServerReuseConfig reuseConfig) {
        LaunchResult reused = tryReuseRunningServer(plan, reuseConfig);
        if (reused != null) {
            return reused;
        }
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

    static LaunchResult tryReuseRunningServer(DiffPlan plan, ServerReuseConfig config) {
        if (plan == null || config == null) {
            return null;
        }
        List<String> candidates = collectServerCandidates(config);
        if (candidates.isEmpty()) {
            return null;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(SERVER_PROBE_TIMEOUT).build();
        for (String base : candidates) {
            String cleanBase = base.endsWith("/") ? base : base + "/";
            String metaBody = getJson(client, cleanBase + "api/meta", SERVER_PROBE_TIMEOUT);
            if (metaBody == null || !APP_NAME.equals(extractJsonStringField(metaBody, "app"))) {
                continue;
            }
            if (plan.mode() == DiffMode.HEAD_MULTI_FILES
                    && !Boolean.TRUE.equals(extractJsonBooleanField(metaBody, "supports_paths"))) {
                continue;
            }
            String payload = buildComparePayloadJson(plan);
            if (payload == null) {
                continue;
            }
            HttpResult postRes = postJson(client, cleanBase + "api/compare", payload, SERVER_COMPARE_TIMEOUT);
            if (postRes == null) {
                continue;
            }
            if (postRes.statusCode() >= 200 && postRes.statusCode() < 300) {
                return new LaunchResult(true, cleanBase, null);
            }
            if (postRes.statusCode() >= 400 && postRes.statusCode() < 500 && postRes.statusCode() != 404) {
                String detail = extractJsonStringField(postRes.body(), "detail");
                String msg = (detail != null && !detail.isBlank())
                        ? detail
                        : "local-diff-san rejected comparison (HTTP " + postRes.statusCode() + ")";
                return new LaunchResult(false, cleanBase, msg);
            }
        }
        return null;
    }

    private record HttpResult(int statusCode, String body) {}

    private static Path defaultServerRegistryPath() {
        String envDir = System.getenv("DIFF_SAN_SERVER_REGISTRY_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return expandUserHome(envDir.trim(), defaultUserHome()).resolve(APP_NAME + ".json");
        }
        Path home = defaultUserHome();
        if (home == null) {
            return null;
        }
        return home.resolve(".cache").resolve("diff_san").resolve("servers").resolve(APP_NAME + ".json");
    }

    private static List<String> collectServerCandidates(ServerReuseConfig config) {
        Set<String> seenNoSlash = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (config.registryPath() != null && Files.isRegularFile(config.registryPath())) {
            try {
                String raw = Files.readString(config.registryPath(), StandardCharsets.UTF_8);
                String regUrl = extractJsonStringField(raw, "url");
                addUniqueUrl(out, seenNoSlash, regUrl);
            } catch (IOException ignored) {}
        }
        if (config.fallbackUrls() != null) {
            for (String url : config.fallbackUrls()) {
                addUniqueUrl(out, seenNoSlash, url);
            }
        }
        return out;
    }

    private static void addUniqueUrl(List<String> out, Set<String> seenNoSlash, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }
        String trimmed = rawUrl.trim();
        String norm = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (!norm.isEmpty() && seenNoSlash.add(norm)) {
            out.add(norm + "/");
        }
    }

    private static String getJson(HttpClient client, String url, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static HttpResult postJson(HttpClient client, String url, String jsonBody, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(resp.statusCode(), resp.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String buildComparePayloadJson(DiffPlan plan) {
        List<String> args = plan.arguments();
        return switch (plan.mode()) {
            case HEAD_SINGLE_FILE -> args.isEmpty()
                    ? null
                    : "{\"mode\":\"git_head\",\"left_path\":"
                            + quoteJsonString(args.get(0))
                            + ",\"paths\":"
                            + quoteJsonStringList(List.of(args.get(0)))
                            + ",\"open_browser_if_idle\":true}";
            case HEAD_DIRECTORY -> args.isEmpty()
                    ? null
                    : "{\"mode\":\"git_dir\",\"left_path\":"
                            + quoteJsonString(args.get(0))
                            + ",\"paths\":"
                            + quoteJsonStringList(List.of(args.get(0)))
                            + ",\"open_browser_if_idle\":true}";
            case TWO_FILES -> args.size() < 2
                    ? null
                    : "{\"mode\":\"file_pair\",\"left_path\":"
                            + quoteJsonString(args.get(0))
                            + ",\"right_path\":"
                            + quoteJsonString(args.get(1))
                            + ",\"open_browser_if_idle\":true}";
            case HEAD_MULTI_FILES -> {
                List<String> fileArgs = (!args.isEmpty() && "--head".equals(args.get(0)))
                        ? args.subList(1, args.size())
                        : args;
                yield fileArgs.isEmpty()
                        ? null
                        : "{\"mode\":\"git_head\",\"paths\":"
                                + quoteJsonStringList(fileArgs)
                                + ",\"open_browser_if_idle\":true}";
            }
        };
    }

    private static String quoteJsonStringList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quoteJsonString(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String quoteJsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String extractJsonStringField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return unescapeJsonString(m.group(1));
    }

    static Boolean extractJsonBooleanField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(true|false)\\b");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return Boolean.parseBoolean(m.group(1));
    }

    private static String unescapeJsonString(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(++i);
                switch (next) {
                    case '"', '\\', '/' -> sb.append(next);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < escaped.length()) {
                            sb.append((char) Integer.parseInt(escaped.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
