package io.github.sanskritcoders.githubsan;

import io.github.sanskritcoders.githubsan.GitHubUrlResolver.CommandResult;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.HookInstallResult;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.PushMode;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.PushResult;
import io.github.sanskritcoders.githubsan.SubmodulePushSupport.SubmoduleEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Integration-style unit tests for {@link SubmodulePushSupport} using temporary bare Git repositories
 * and nested submodules.
 */
public final class SubmodulePushSupportTest {
    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("SubmodulePushSupportTest: ALL PASSED");
    }

    public static void runAll() throws Exception {
        testBottomUpDiscoveryAndAutoPushWhenUnset();
        testSkipsAutoPushWhenRecurseSubmodulesConfigured();
        testSkipsDetachedAndExcludedSubmodulesAndToleratesDirtyWorktree();
        testPushSubmodulesAndRepoPushesBoth();
        testPrePushHookInstallIsIdempotentAndPushesBottomUpOnNativeGitPush();
    }

    private static void testBottomUpDiscoveryAndAutoPushWhenUnset() throws Exception {
        TestFixture f = createNestedFixture();
        try {
            List<SubmoduleEntry> discovered =
                    SubmodulePushSupport.discoverSubmodulesBottomUp(f.superWork(), f.env());
            assertEquals(
                    List.of("outer/inner", "outer", "vendor"),
                    discovered.stream().map(SubmoduleEntry::relativePath).toList(),
                    "bottom-up discovery order");

            // Make new commits in inner and outer without updating superproject gitlinks yet
            Path innerWork = f.superWork().resolve("outer/inner");
            Files.writeString(innerWork.resolve("inner.txt"), "inner v2\n", StandardCharsets.UTF_8);
            gitSuccess(innerWork, f.env(), "commit", "-am", "inner v2");

            Path outerWork = f.superWork().resolve("outer");
            gitSuccess(outerWork, f.env(), "add", "inner");
            gitSuccess(outerWork, f.env(), "commit", "-m", "outer bumps inner");

            // Leave vendor at detached HEAD (normal after submodule update)
            PushResult result = SubmodulePushSupport.pushPendingSubmodules(
                    f.superWork(),
                    Set.of(),
                    PushMode.AUTO_WHEN_NOT_CONFIGURED,
                    () -> false,
                    null,
                    f.env());

            assertTrue(!result.hasFailures(), "auto-push should have no failures: " + result.formatFailureSummary());
            assertTrue(!result.skippedBecauseConfigured(), "should not skip when global config unset");
            assertEquals(List.of("outer/inner", "outer"), result.pushedSubmodules(), "pushed bottom-up");
            assertEquals(List.of("vendor"), result.skippedDetachedSubmodules(), "vendor skipped as detached");
            assertEquals(
                    gitStdout(innerWork, f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.innerBare(), f.env(), "rev-parse", "main"),
                    "inner bare remote updated");
            assertEquals(
                    gitStdout(outerWork, f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.outerBare(), f.env(), "rev-parse", "main"),
                    "outer bare remote updated");
        } finally {
            deleteRecursively(f.root());
        }
    }

    private static void testSkipsAutoPushWhenRecurseSubmodulesConfigured() throws Exception {
        TestFixture f = createNestedFixture();
        try {
            SubmodulePushSupport.enableGlobalRecurseSubmodules(f.superWork(), f.env());
            String configured = SubmodulePushSupport.getEffectivePushRecurseSubmodules(f.superWork(), f.env());
            assertEquals("on-demand", configured, "effective push.recurseSubmodules");

            Path outerWork = f.superWork().resolve("outer");
            String beforeBare = gitStdout(f.outerBare(), f.env(), "rev-parse", "main");
            Files.writeString(outerWork.resolve("outer.txt"), "outer unpushed\n", StandardCharsets.UTF_8);
            gitSuccess(outerWork, f.env(), "commit", "-am", "outer unpushed");

            PushResult result = SubmodulePushSupport.pushPendingSubmodules(
                    f.superWork(),
                    Set.of(),
                    PushMode.AUTO_WHEN_NOT_CONFIGURED,
                    () -> false,
                    null,
                    f.env());

            assertTrue(result.skippedBecauseConfigured(), "auto mode skips when Option 3 configured");
            assertEquals(List.of(), result.pushedSubmodules(), "no submodule pushed in auto mode");
            assertEquals(beforeBare, gitStdout(f.outerBare(), f.env(), "rev-parse", "main"), "bare unchanged");
        } finally {
            deleteRecursively(f.root());
        }
    }

    private static void testSkipsDetachedAndExcludedSubmodulesAndToleratesDirtyWorktree() throws Exception {
        TestFixture f = createNestedFixture();
        try {
            Path outerWork = f.superWork().resolve("outer");
            Path innerWork = outerWork.resolve("inner");

            // Uncommitted dirty file inside outer and inner should not block pushing committed changes
            Files.writeString(outerWork.resolve("wip-dirty.txt"), "dirty wip\n", StandardCharsets.UTF_8);
            Files.writeString(outerWork.resolve("outer.txt"), "outer committed\n", StandardCharsets.UTF_8);
            gitSuccess(outerWork, f.env(), "add", "outer.txt");
            gitSuccess(outerWork, f.env(), "commit", "-m", "outer committed despite wip");

            Files.writeString(innerWork.resolve("inner.txt"), "inner excluded\n", StandardCharsets.UTF_8);
            gitSuccess(innerWork, f.env(), "commit", "-am", "inner excluded");
            String innerBareBefore = gitStdout(f.innerBare(), f.env(), "rev-parse", "main");

            PushResult result = SubmodulePushSupport.pushPendingSubmodules(
                    f.superWork(),
                    Set.of(innerWork),
                    PushMode.AUTO_WHEN_NOT_CONFIGURED,
                    () -> false,
                    null,
                    f.env());

            assertTrue(!result.hasFailures(), "dirty worktree must not fail submodule push");
            assertEquals(List.of("outer"), result.pushedSubmodules(), "only non-excluded outer pushed");
            assertEquals(innerBareBefore, gitStdout(f.innerBare(), f.env(), "rev-parse", "main"), "excluded inner untouched");
        } finally {
            deleteRecursively(f.root());
        }
    }

    private static void testPushSubmodulesAndRepoPushesBoth() throws Exception {
        TestFixture f = createNestedFixture();
        try {
            // Even when push.recurseSubmodules=on-demand is set, explicit pushSubmodulesAndRepo pushes direct submodule commits
            SubmodulePushSupport.enableGlobalRecurseSubmodules(f.superWork(), f.env());

            Path outerWork = f.superWork().resolve("outer");
            Files.writeString(outerWork.resolve("outer.txt"), "outer explicit\n", StandardCharsets.UTF_8);
            gitSuccess(outerWork, f.env(), "commit", "-am", "outer explicit");

            Files.writeString(f.superWork().resolve("README.md"), "super v2\n", StandardCharsets.UTF_8);
            gitSuccess(f.superWork(), f.env(), "commit", "-am", "super v2");

            PushResult result = SubmodulePushSupport.pushSubmodulesAndRepo(
                    f.superWork(), () -> false, null, f.env());

            assertTrue(!result.hasFailures(), "explicit push succeeds: " + result.formatFailureSummary());
            assertEquals(List.of("outer"), result.pushedSubmodules(), "explicit push pushed submodule");
            assertEquals(
                    gitStdout(outerWork, f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.outerBare(), f.env(), "rev-parse", "main"),
                    "outer bare updated");
            assertEquals(
                    gitStdout(f.superWork(), f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.superBare(), f.env(), "rev-parse", "main"),
                    "super bare updated");
        } finally {
            deleteRecursively(f.root());
        }
    }

    private static void testPrePushHookInstallIsIdempotentAndPushesBottomUpOnNativeGitPush() throws Exception {
        TestFixture f = createNestedFixture();
        try {
            HookInstallResult first = SubmodulePushSupport.installPrePushHook(f.superWork(), f.env());
            assertTrue(first.updated(), "first hook install updates file");
            String firstContent = Files.readString(first.hookFile(), StandardCharsets.UTF_8);

            HookInstallResult second = SubmodulePushSupport.installPrePushHook(f.superWork(), f.env());
            assertTrue(!second.updated(), "second hook install is idempotent");
            assertEquals(firstContent, Files.readString(second.hookFile(), StandardCharsets.UTF_8), "content unchanged");

            // Commit inside nested inner and outer submodules, plus superproject commit
            Path innerWork = f.superWork().resolve("outer/inner");
            Files.writeString(innerWork.resolve("inner.txt"), "inner hook\n", StandardCharsets.UTF_8);
            gitSuccess(innerWork, f.env(), "commit", "-am", "inner hook");

            Path outerWork = f.superWork().resolve("outer");
            gitSuccess(outerWork, f.env(), "add", "inner");
            gitSuccess(outerWork, f.env(), "commit", "-m", "outer hook");

            Files.writeString(f.superWork().resolve("README.md"), "super hook\n", StandardCharsets.UTF_8);
            gitSuccess(f.superWork(), f.env(), "commit", "-am", "super hook");

            // Run native `git push` on superproject (with global push.recurseSubmodules UNSET)
            gitSuccess(f.superWork(), f.env(), "push");

            assertEquals(
                    gitStdout(innerWork, f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.innerBare(), f.env(), "rev-parse", "main"),
                    "native git push pre-push hook updated inner bare");
            assertEquals(
                    gitStdout(outerWork, f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.outerBare(), f.env(), "rev-parse", "main"),
                    "native git push pre-push hook updated outer bare");
            assertEquals(
                    gitStdout(f.superWork(), f.env(), "rev-parse", "HEAD"),
                    gitStdout(f.superBare(), f.env(), "rev-parse", "main"),
                    "native git push updated super bare");
        } finally {
            deleteRecursively(f.root());
        }
    }

    private record TestFixture(
            Path root,
            Path superBare,
            Path outerBare,
            Path innerBare,
            Path vendorBare,
            Path superWork,
            Map<String, String> env) {}

    private static TestFixture createNestedFixture() throws Exception {
        Path root = Files.createTempDirectory("githubsan-subpush-");
        Path globalCfg = root.resolve("gitconfig-global");
        Files.writeString(globalCfg, "", StandardCharsets.UTF_8);
        Map<String, String> env = new HashMap<>(SubmodulePushSupport.envWithGlobalConfig(globalCfg));
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "Test User");
        env.put("GIT_AUTHOR_EMAIL", "test@example.com");
        env.put("GIT_COMMITTER_NAME", "Test User");
        env.put("GIT_COMMITTER_EMAIL", "test@example.com");

        Path innerBare = initBareRepo(root.resolve("inner.git"), env);
        Path outerBare = initBareRepo(root.resolve("outer.git"), env);
        Path vendorBare = initBareRepo(root.resolve("vendor.git"), env);
        Path superBare = initBareRepo(root.resolve("super.git"), env);

        seedBareRepo(innerBare, root.resolve("seed-inner"), "inner.txt", "inner v1\n", env);
        seedBareRepo(vendorBare, root.resolve("seed-vendor"), "vendor.txt", "vendor v1\n", env);

        Path seedOuter = root.resolve("seed-outer");
        gitSuccess(root, env, "clone", outerBare.toString(), seedOuter.toString());
        gitSuccess(seedOuter, env, "checkout", "-B", "main");
        Files.writeString(seedOuter.resolve("outer.txt"), "outer v1\n", StandardCharsets.UTF_8);
        gitSuccess(seedOuter, env, "add", "outer.txt");
        gitSuccess(seedOuter, env, "commit", "-m", "init outer");
        gitSuccess(seedOuter, env, "-c", "protocol.file.allow=always", "submodule", "add", innerBare.toString(), "inner");
        gitSuccess(seedOuter, env, "commit", "-m", "add inner submodule");
        gitSuccess(seedOuter, env, "push", "-u", "origin", "main");

        Path seedSuper = root.resolve("seed-super");
        gitSuccess(root, env, "clone", superBare.toString(), seedSuper.toString());
        gitSuccess(seedSuper, env, "checkout", "-B", "main");
        Files.writeString(seedSuper.resolve("README.md"), "super v1\n", StandardCharsets.UTF_8);
        gitSuccess(seedSuper, env, "add", "README.md");
        gitSuccess(seedSuper, env, "commit", "-m", "init super");
        gitSuccess(seedSuper, env, "-c", "protocol.file.allow=always", "submodule", "add", outerBare.toString(), "outer");
        gitSuccess(seedSuper, env, "-c", "protocol.file.allow=always", "submodule", "add", vendorBare.toString(), "vendor");
        gitSuccess(seedSuper, env, "commit", "-m", "add outer and vendor submodules");
        gitSuccess(seedSuper, env, "push", "-u", "origin", "main");

        Path superWork = root.resolve("work-super");
        gitSuccess(root, env, "-c", "protocol.file.allow=always", "clone", "--recurse-submodules", superBare.toString(), superWork.toString());
        // Put outer and outer/inner onto tracking branch main, leave vendor detached as standard pinned submodule
        gitSuccess(superWork.resolve("outer"), env, "checkout", "-B", "main", "--track", "origin/main");
        gitSuccess(superWork.resolve("outer/inner"), env, "checkout", "-B", "main", "--track", "origin/main");

        return new TestFixture(root, superBare, outerBare, innerBare, vendorBare, superWork, Map.copyOf(env));
    }

    private static Path initBareRepo(Path bareDir, Map<String, String> env) throws Exception {
        Files.createDirectories(bareDir);
        gitSuccess(bareDir, env, "init", "--bare", "-b", "main");
        return bareDir;
    }

    private static void seedBareRepo(
            Path bareDir, Path seedWork, String fileName, String content, Map<String, String> env)
            throws Exception {
        gitSuccess(bareDir.getParent(), env, "clone", bareDir.toString(), seedWork.toString());
        gitSuccess(seedWork, env, "checkout", "-B", "main");
        Files.writeString(seedWork.resolve(fileName), content, StandardCharsets.UTF_8);
        gitSuccess(seedWork, env, "add", fileName);
        gitSuccess(seedWork, env, "commit", "-m", "init " + fileName);
        gitSuccess(seedWork, env, "push", "-u", "origin", "main");
    }

    private static String gitStdout(Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        CommandResult res = gitSuccess(dir, env, args);
        return res.stdout().trim();
    }

    private static CommandResult gitSuccess(Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        CommandResult res = GitHubUrlResolver.runGit(dir, Duration.ofSeconds(30), env, args);
        if (res.exitCode() != 0) {
            throw new AssertionError(
                    "git " + String.join(" ", args) + " failed in " + dir + ": " + res.stderr() + " / " + res.stdout());
        }
        return res;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup in temp directory.
                }
            });
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
