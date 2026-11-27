package io.github.sanskritcoders.githubsan;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 entrypoint executing {@link GitHubUrlResolverTest} under Gradle CI.
 */
final class GitHubUrlResolverJUnitTest {
    @Test
    void runsAllResolverTests() throws Exception {
        GitHubUrlResolverTest.runAll();
    }
}
