package io.github.sanskritcoders.githubsan;

import io.github.sanskritcoders.githubsan.GitHubUrlResolver.GitLocation;
import io.github.sanskritcoders.githubsan.GitHubUrlResolver.LineRange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Standalone verification suite for {@link GitHubUrlResolver}.
 */
public final class GitHubUrlResolverTest {
    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("ALL TESTS PASSED");
    }

    public static void runAll() throws Exception {
        testParseGithubBaseUrl();
        testNonGitPath();
        testTempSubmoduleDetachedHeadWorktreeAndEncoding();
        testLocalVedAhYajuhIfPresent();
    }

    static void testParseGithubBaseUrl() {
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("https://github.com/vishvAsa/vedAH_yajuH"));
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("https://github.com/vishvAsa/vedAH_yajuH.git"));
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("https://user@github.com/vishvAsa/vedAH_yajuH.git/"));
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("git@github.com:vishvAsa/vedAH_yajuH.git"));
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("ssh://git@github.com:22/vishvAsa/vedAH_yajuH.git"));
        assertEquals("https://github.com/vishvAsa/vedAH_yajuH",
                GitHubUrlResolver.parseGithubBaseUrl("git://github.com/vishvAsa/vedAH_yajuH"));
        assertEquals(null, GitHubUrlResolver.parseGithubBaseUrl("https://gitlab.com/org/repo.git"));
        assertEquals(null, GitHubUrlResolver.parseGithubBaseUrl(""));
    }

    static void testNonGitPath() throws IOException {
        Path tmp = Files.createTempDirectory("non-git-test-");
        try {
            Path plainFile = tmp.resolve("plain.txt");
            Files.writeString(plainFile, "hello");
            GitLocation loc = GitHubUrlResolver.findGitLocation(plainFile);
            assertEquals(null, loc);
            assertTrue(!GitHubUrlResolver.hasGithubRemoteFast(loc), "Non-git file must not report GitHub remote");
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testTempSubmoduleDetachedHeadWorktreeAndEncoding() throws Exception {
        Path tmp = Files.createTempDirectory("gh-resolver-test-");
        try {
            // 1. Detached HEAD repository with SSH remote & spaces/Unicode filename
            Path repo = tmp.resolve("repo");
            Files.createDirectories(repo);
            run(repo, "git", "init", "-b", "main");
            run(repo, "git", "config", "user.email", "test@example.com");
            run(repo, "git", "config", "user.name", "Test");
            run(repo, "git", "remote", "add", "origin", "git@github.com:org/my-repo.git");
            Path unicodeDir = repo.resolve("dir with space");
            Files.createDirectories(unicodeDir);
            Path unicodeFile = unicodeDir.resolve("वेद.md");
            Files.writeString(unicodeFile, "hello\n");
            run(repo, "git", "add", ".");
            run(repo, "git", "commit", "-m", "init");
            String commitSha = runOutput(repo, "git", "rev-parse", "HEAD").trim();
            run(repo, "git", "checkout", "--detach", commitSha);

            GitLocation repoLoc = GitHubUrlResolver.findGitLocation(unicodeFile);
            assertTrue(repoLoc != null && GitHubUrlResolver.hasGithubRemoteFast(repoLoc),
                    "Expected SSH GitHub remote detection");
            assertEquals(
                    "https://github.com/org/my-repo/blob/" + commitSha + "/dir%20with%20space/%E0%A4%B5%E0%A5%87%E0%A4%A6.md",
                    GitHubUrlResolver.resolveGithubUrl(unicodeFile, null));
            assertEquals(
                    "https://github.com/org/my-repo/blob/" + commitSha + "/dir%20with%20space/%E0%A4%B5%E0%A5%87%E0%A4%A6.md#L7",
                    GitHubUrlResolver.resolveGithubUrl(unicodeFile, new LineRange(7, 7)));
            assertEquals(
                    "https://github.com/org/my-repo/blob/" + commitSha + "/dir%20with%20space/%E0%A4%B5%E0%A5%87%E0%A4%A6.md#L10-L25",
                    GitHubUrlResolver.resolveGithubUrl(unicodeFile, new LineRange(10, 25)));
            assertEquals(
                    "https://github.com/org/my-repo/tree/" + commitSha + "/dir%20with%20space",
                    GitHubUrlResolver.resolveGithubUrl(unicodeDir, null));
            assertEquals(
                    "https://github.com/org/my-repo/tree/" + commitSha,
                    GitHubUrlResolver.resolveGithubUrl(repo, null));

            // 2. Git worktree (uses .git file pointing to worktrees/<name> + commondir)
            Path worktree = tmp.resolve("wt");
            run(repo, "git", "worktree", "add", "-b", "wt-branch", worktree.toString(), commitSha);
            Path wtFile = worktree.resolve("dir with space/वेद.md");
            GitLocation wtLoc = GitHubUrlResolver.findGitLocation(wtFile);
            assertTrue(wtLoc != null && GitHubUrlResolver.hasGithubRemoteFast(wtLoc),
                    "Expected worktree commondir GitHub remote detection");
            assertEquals(
                    "https://github.com/org/my-repo/blob/" + commitSha + "/dir%20with%20space/%E0%A4%B5%E0%A5%87%E0%A4%A6.md",
                    GitHubUrlResolver.resolveGithubUrl(wtFile, null));

            // 3. Synthetic git submodule (.git file -> .git/modules/content) with tracking branch
            Path upstreamBare = tmp.resolve("upstream.git");
            Files.createDirectories(upstreamBare);
            run(upstreamBare, "git", "init", "--bare", "-b", "content");
            Path subSeed = tmp.resolve("sub-seed");
            Files.createDirectories(subSeed);
            run(subSeed, "git", "init", "-b", "content");
            run(subSeed, "git", "config", "user.email", "test@example.com");
            run(subSeed, "git", "config", "user.name", "Test");
            Files.writeString(subSeed.resolve("doc.md"), "sub\n");
            run(subSeed, "git", "add", ".");
            run(subSeed, "git", "commit", "-m", "sub init");
            run(subSeed, "git", "remote", "add", "origin", upstreamBare.toString());
            run(subSeed, "git", "push", "-u", "origin", "content");

            Path superRepo = tmp.resolve("super");
            Files.createDirectories(superRepo);
            run(superRepo, "git", "init", "-b", "master");
            run(superRepo, "git", "config", "user.email", "test@example.com");
            run(superRepo, "git", "config", "user.name", "Test");
            run(superRepo, "git", "remote", "add", "origin", "https://github.com/org/same-repo.git");
            run(superRepo, "git", "-c", "protocol.file.allow=always", "submodule", "add", "-b", "content",
                    upstreamBare.toString(), "content");
            Path subDir = superRepo.resolve("content");
            run(subDir, "git", "remote", "set-url", "origin", "https://github.com/org/same-repo");
            Path subDoc = subDir.resolve("doc.md");

            GitLocation subLoc = GitHubUrlResolver.findGitLocation(subDoc);
            assertTrue(subLoc != null, "Expected GitLocation for synthetic submodule");
            assertEquals(subDir.toAbsolutePath().normalize(), subLoc.repoRoot());
            assertTrue(GitHubUrlResolver.hasGithubRemoteFast(subLoc), "Expected GitHub remote for synthetic submodule");
            assertEquals("https://github.com/org/same-repo/blob/content/doc.md",
                    GitHubUrlResolver.resolveGithubUrl(subDoc, null));

            // 4. Non-GitHub remote repository
            Path gitlabRepo = tmp.resolve("gitlab");
            Files.createDirectories(gitlabRepo);
            run(gitlabRepo, "git", "init", "-b", "main");
            run(gitlabRepo, "git", "remote", "add", "origin", "https://gitlab.com/org/repo.git");
            Path glFile = gitlabRepo.resolve("README.md");
            Files.writeString(glFile, "gl\n");
            GitLocation glLoc = GitHubUrlResolver.findGitLocation(glFile);
            assertTrue(glLoc != null && !GitHubUrlResolver.hasGithubRemoteFast(glLoc),
                    "GitLab repo must not report GitHub remote");
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testLocalVedAhYajuhIfPresent() throws Exception {
        Path contentRoot = Path.of("/home/vvasuki/gitland/vishvAsa/vedAH_yajuH/content");
        Path sampleFile = contentRoot.resolve("taittirIyam/sUtram/vaikhAnasaH/gRhyam/paddhatiH/puNyAha-vAchanam.md");
        if (!Files.exists(sampleFile)) {
            return;
        }
        GitLocation contentLoc = GitHubUrlResolver.findGitLocation(sampleFile);
        assertTrue(contentLoc != null, "Expected GitLocation for vedAH_yajuH/content");
        assertEquals(contentRoot, contentLoc.repoRoot());
        assertTrue(GitHubUrlResolver.hasGithubRemoteFast(contentLoc), "Expected GitHub remote for vedAH_yajuH/content");
        assertEquals(
                "https://github.com/vishvAsa/vedAH_yajuH/blob/content/taittirIyam/sUtram/vaikhAnasaH/gRhyam/paddhatiH/puNyAha-vAchanam.md",
                GitHubUrlResolver.resolveGithubUrl(sampleFile, null));
        assertEquals(
                "https://github.com/vishvAsa/vedAH_yajuH/tree/content",
                GitHubUrlResolver.resolveGithubUrl(contentRoot, null));
    }

    private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of(cmd)).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static String runOutput(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(List.of(cmd)).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", cmd) + "\n" + out);
        }
        return out;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            });
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
