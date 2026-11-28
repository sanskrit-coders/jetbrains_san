package io.github.sanskritcoders.githubsan;

import com.sun.net.httpserver.HttpServer;
import io.github.sanskritcoders.githubsan.DiffSanSupport.DiffMode;
import io.github.sanskritcoders.githubsan.DiffSanSupport.DiffPlan;
import io.github.sanskritcoders.githubsan.DiffSanSupport.LaunchResult;
import io.github.sanskritcoders.githubsan.DiffSanSupport.ServerReuseConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link DiffSanSupport} covering path selection, binary resolution, and launch checks.
 */
public final class DiffSanSupportTest {
    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("DiffSanSupportTest: ALL PASSED");
    }

    public static void runAll() throws Exception {
        testPlanSelectionSingleFileAndDirInGit();
        testPlanSelectionSingleFileInSubmodule();
        testPlanSelectionSingleFileOutsideGitRejected();
        testPlanSelectionTwoFiles();
        testPlanSelectionMultipleFilesHead();
        testResolveExecutablePriority();
        testLaunchSuccessAndFailure();
        testReuseRunningServerSkipsSpawn();
    }

    private static void testPlanSelectionSingleFileAndDirInGit() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-git-");
        try {
            Files.createDirectory(tempDir.resolve(".git"));
            Path subDir = Files.createDirectory(tempDir.resolve("docs"));
            Path file = Files.writeString(subDir.resolve("sample.md"), "om", StandardCharsets.UTF_8);

            DiffPlan filePlan = DiffSanSupport.planSelection(List.of(file));
            assertNotNull(filePlan, "Expected plan for file in git repo");
            assertEquals(DiffMode.HEAD_SINGLE_FILE, filePlan.mode(), "single file mode");
            assertEquals("Diff against HEAD (diff_san)", filePlan.label(), "single file label");
            assertEquals(List.of(file.toAbsolutePath().normalize().toString()), filePlan.arguments(), "single file args");

            DiffPlan dirPlan = DiffSanSupport.planSelection(List.of(subDir));
            assertNotNull(dirPlan, "Expected plan for directory in git repo");
            assertEquals(DiffMode.HEAD_DIRECTORY, dirPlan.mode(), "dir mode");
            assertEquals("Diff Directory against HEAD (diff_san)", dirPlan.label(), "dir label");
            assertEquals(List.of(subDir.toAbsolutePath().normalize().toString()), dirPlan.arguments(), "dir args");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testPlanSelectionSingleFileInSubmodule() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-submod-");
        try {
            Path superRepo = Files.createDirectory(tempDir.resolve("super"));
            Path moduleGitDir = Files.createDirectories(superRepo.resolve(".git/modules/content"));
            Path submoduleRoot = Files.createDirectory(superRepo.resolve("content"));
            Files.writeString(submoduleRoot.resolve(".git"), "gitdir: ../.git/modules/content\n", StandardCharsets.UTF_8);
            Files.writeString(moduleGitDir.resolve("HEAD"), "ref: refs/heads/content\n", StandardCharsets.UTF_8);
            Path file = Files.writeString(submoduleRoot.resolve("rAmadeshikAhnikam.md"), "text", StandardCharsets.UTF_8);

            DiffPlan plan = DiffSanSupport.planSelection(List.of(file));
            assertNotNull(plan, "Expected plan for submodule file");
            assertEquals(DiffMode.HEAD_SINGLE_FILE, plan.mode(), "submodule file mode");
            assertEquals(List.of(file.toAbsolutePath().normalize().toString()), plan.arguments(), "submodule args");

            DiffPlan rootPlan = DiffSanSupport.planSelection(List.of(submoduleRoot));
            assertNotNull(rootPlan, "Expected plan for submodule root");
            assertEquals(DiffMode.HEAD_DIRECTORY, rootPlan.mode(), "submodule root mode");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testPlanSelectionSingleFileOutsideGitRejected() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-nongit-");
        try {
            Path file = Files.writeString(tempDir.resolve("note.md"), "om", StandardCharsets.UTF_8);
            assertEquals(null, DiffSanSupport.planSelection(List.of(file)), "non-git single file must be null");
            assertEquals(null, DiffSanSupport.planSelection(List.of(tempDir)), "non-git dir must be null");
            assertEquals(null, DiffSanSupport.planSelection(List.of(tempDir.resolve("missing.md"))), "missing file must be null");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testPlanSelectionTwoFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-pair-");
        try {
            Path left = Files.writeString(tempDir.resolve("a.md"), "left", StandardCharsets.UTF_8);
            Path right = Files.writeString(tempDir.resolve("b.md"), "right", StandardCharsets.UTF_8);
            Path dir = Files.createDirectory(tempDir.resolve("sub"));

            DiffPlan pairPlan = DiffSanSupport.planSelection(List.of(left, right));
            assertNotNull(pairPlan, "Two regular non-git files should be diffable");
            assertEquals(DiffMode.TWO_FILES, pairPlan.mode(), "two-file mode");
            assertEquals("Diff Two Files (diff_san)", pairPlan.label(), "two-file label");
            assertEquals(List.of(left.toString(), right.toString()), pairPlan.arguments(), "two-file args");

            assertEquals(null, DiffSanSupport.planSelection(List.of(left, dir)), "file + dir rejected in 2-selection");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testPlanSelectionMultipleFilesHead() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-multi-");
        try {
            Files.createDirectory(tempDir.resolve(".git"));
            Path f1 = Files.writeString(tempDir.resolve("1.md"), "1", StandardCharsets.UTF_8);
            Path f2 = Files.writeString(tempDir.resolve("2.md"), "2", StandardCharsets.UTF_8);
            Path f3 = Files.writeString(tempDir.resolve("3.md"), "3", StandardCharsets.UTF_8);
            Path outside = Files.createTempDirectory("diffsan-out-");
            try {
                Path nonGit = Files.writeString(outside.resolve("x.md"), "x", StandardCharsets.UTF_8);
                DiffPlan multiPlan = DiffSanSupport.planSelection(List.of(f1, f2, f3));
                assertNotNull(multiPlan, "Three git files should use --head mode");
                assertEquals(DiffMode.HEAD_MULTI_FILES, multiPlan.mode(), "multi-head mode");
                assertEquals(List.of("--head", f1.toString(), f2.toString(), f3.toString()), multiPlan.arguments(), "multi-head args");

                assertEquals(null, DiffSanSupport.planSelection(List.of(f1, f2, nonGit)), "mixed git + non-git rejected for multi --head");
            } finally {
                deleteRecursively(outside);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testResolveExecutablePriority() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-bin-");
        try {
            Path fakeHome = Files.createDirectory(tempDir.resolve("home"));
            Path projectDir = Files.createDirectory(tempDir.resolve("proj"));
            Path gitlandBin = Files.createDirectories(fakeHome.resolve("gitland/python_venv/bin"));
            Path localBin = Files.createDirectories(fakeHome.resolve(".local/bin"));
            Path projectVenvBin = Files.createDirectories(projectDir.resolve(".venv/bin"));
            Path pathBin = Files.createDirectory(tempDir.resolve("pathbin"));
            Path overrideDir = Files.createDirectory(tempDir.resolve("override"));

            Path gitlandExe = writeExecutableScript(gitlandBin.resolve("local-diff-san"), "#!/bin/sh\nexit 0\n");
            Path localExe = writeExecutableScript(localBin.resolve("local_diff_san"), "#!/bin/sh\nexit 0\n");
            Path projectExe = writeExecutableScript(projectVenvBin.resolve("local-diff-san"), "#!/bin/sh\nexit 0\n");
            Path pathExe = writeExecutableScript(pathBin.resolve("local-diff-san"), "#!/bin/sh\nexit 0\n");
            Path overrideExe = writeExecutableScript(overrideDir.resolve("custom-diff"), "#!/bin/sh\nexit 0\n");

            assertEquals(overrideExe, DiffSanSupport.resolveExecutable(overrideExe.toString(), pathBin.toString(), fakeHome, projectDir), "env override wins");
            assertEquals(pathExe, DiffSanSupport.resolveExecutable(null, pathBin.toString(), fakeHome, projectDir), "PATH wins over fallbacks");
            assertEquals(projectExe, DiffSanSupport.resolveExecutable(null, "", fakeHome, projectDir), "project .venv wins over user home");
            assertEquals(gitlandExe, DiffSanSupport.resolveExecutable(null, "", fakeHome, null), "gitland python_venv resolved");

            Files.delete(gitlandExe);
            assertEquals(localExe, DiffSanSupport.resolveExecutable(null, "", fakeHome, null), "~/.local/bin fallback resolved");
            assertEquals(null, DiffSanSupport.resolveExecutable(null, "", tempDir.resolve("emptyHome"), null), "missing binary returns null");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testLaunchSuccessAndFailure() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-launch-");
        try {
            Files.createDirectory(tempDir.resolve(".git"));
            Path target = Files.writeString(tempDir.resolve("text.md"), "test", StandardCharsets.UTF_8);
            DiffPlan plan = DiffSanSupport.planSelection(List.of(target));
            assertNotNull(plan, "plan");

            Path okScript = writeExecutableScript(
                    tempDir.resolve("ok-diff"),
                    "#!/bin/sh\necho 'local-diff-san running at http://127.0.0.1:8799/ (mode=git_head)'\nsleep 0.2\n");
            LaunchResult okResult = DiffSanSupport.launch(okScript, plan, Duration.ofSeconds(3));
            assertTrue(okResult.started(), "Expected launch success, got: " + okResult.errorMessage());
            assertEquals("http://127.0.0.1:8799/", okResult.url(), "Expected captured server URL");

            Path failScript = writeExecutableScript(
                    tempDir.resolve("fail-diff"),
                    "#!/bin/sh\necho 'Error: Not inside a git repository' >&2\nexit 2\n");
            LaunchResult failResult = DiffSanSupport.launch(failScript, plan, Duration.ofSeconds(3));
            assertTrue(!failResult.started(), "Expected failure on exit code 2");
            assertTrue(
                    failResult.errorMessage() != null
                            && failResult.errorMessage().contains("code 2")
                            && failResult.errorMessage().contains("Not inside a git repository"),
                    "Unexpected failure message: " + failResult.errorMessage());

            LaunchResult missingResult = DiffSanSupport.launch((Path) null, plan, Duration.ofSeconds(1));
            assertTrue(!missingResult.started(), "Missing binary should fail cleanly");
            assertTrue(
                    missingResult.errorMessage() != null && missingResult.errorMessage().contains("local-diff-san"),
                    "Missing binary message should mention local-diff-san");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testReuseRunningServerSkipsSpawn() throws IOException {
        Path tempDir = Files.createTempDirectory("diffsan-reuse-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> metaJson = new AtomicReference<>(
                "{\"app\":\"local-diff-san\",\"supports_paths\":true,\"has_active_ui\":false}");
        List<String> compareBodies = new ArrayList<>();
        server.createContext("/api/meta", ex -> {
            byte[] body = metaJson.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/api/compare", ex -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            compareBodies.add(reqBody);
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        try {
            Files.createDirectory(tempDir.resolve(".git"));
            Path f1 = Files.writeString(tempDir.resolve("a.md"), "om", StandardCharsets.UTF_8);
            Path f2 = Files.writeString(tempDir.resolve("b.md"), "tat", StandardCharsets.UTF_8);
            Path f3 = Files.writeString(tempDir.resolve("c.md"), "sat", StandardCharsets.UTF_8);
            Path failScript = writeExecutableScript(
                    tempDir.resolve("fail-diff"),
                    "#!/bin/sh\necho 'Must not spawn when running server responds' >&2\nexit 9\n");

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Path regFile = tempDir.resolve("local-diff-san.json");
            Files.writeString(
                    regFile,
                    "{\"app\":\"local-diff-san\",\"pid\":12345,\"url\":\"" + baseUrl + "\"}",
                    StandardCharsets.UTF_8);

            // Registry discovery works even if executable is failScript (or null)
            DiffPlan singlePlan = DiffSanSupport.planSelection(List.of(f1));
            LaunchResult singleRes = DiffSanSupport.launch(
                    failScript,
                    singlePlan,
                    Duration.ofSeconds(2),
                    new ServerReuseConfig(regFile, List.of()));
            assertTrue(singleRes.started(), "Expected single-file reuse: " + singleRes.errorMessage());
            assertEquals(baseUrl, singleRes.url(), "Expected reused registry URL");
            assertEquals(1, compareBodies.size(), "Expected 1 POST /api/compare");
            assertTrue(compareBodies.get(0).contains("\"mode\":\"git_head\""), compareBodies.get(0));
            assertTrue(compareBodies.get(0).contains("\"open_browser_if_idle\":true"), compareBodies.get(0));

            // Multi-file HEAD plan strips --head and uses paths array
            DiffPlan multiPlan = DiffSanSupport.planSelection(List.of(f1, f2, f3));
            LaunchResult multiRes = DiffSanSupport.launch(
                    null,
                    multiPlan,
                    Duration.ofSeconds(2),
                    new ServerReuseConfig(null, List.of(baseUrl)));
            assertTrue(multiRes.started(), "Expected multi-file reuse without local binary");
            String multiBody = compareBodies.get(1);
            assertTrue(multiBody.contains("\"mode\":\"git_head\"") && !multiBody.contains("--head"), multiBody);
            assertTrue(multiBody.contains("c.md"), multiBody);

            // Two-file comparison and directory HEAD diff
            LaunchResult pairRes = DiffSanSupport.launch(
                    failScript,
                    DiffSanSupport.planSelection(List.of(f1, f2)),
                    Duration.ofSeconds(2),
                    new ServerReuseConfig(regFile, List.of()));
            assertTrue(pairRes.started(), "Expected two-file reuse");
            assertTrue(compareBodies.get(2).contains("\"mode\":\"file_pair\""), compareBodies.get(2));

            LaunchResult dirRes = DiffSanSupport.launch(
                    failScript,
                    DiffSanSupport.planSelection(List.of(tempDir)),
                    Duration.ofSeconds(2),
                    new ServerReuseConfig(regFile, List.of()));
            assertTrue(dirRes.started(), "Expected dir reuse");
            assertTrue(compareBodies.get(3).contains("\"mode\":\"git_dir\""), compareBodies.get(3));

            // Wrong app metadata ignores server and falls back to normal launch
            metaJson.set("{\"app\":\"pull_requests_san\"}");
            LaunchResult fallbackRes = DiffSanSupport.launch(
                    failScript,
                    singlePlan,
                    Duration.ofSeconds(2),
                    new ServerReuseConfig(regFile, List.of()));
            assertTrue(!fallbackRes.started(), "Expected fallback to spawn when app != local-diff-san");
            assertTrue(
                    fallbackRes.errorMessage() != null && fallbackRes.errorMessage().contains("code 9"),
                    fallbackRes.errorMessage());
        } finally {
            server.stop(0);
            deleteRecursively(tempDir);
        }
    }

    private static Path writeExecutableScript(Path path, String body) throws IOException {
        Files.writeString(path, body, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setExecutable(true);
        }
        return path;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " | expected=" + expected + " actual=" + actual);
        }
    }
}
