package io.github.sanskritcoders.githubsan;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 entrypoint executing {@link DiffSanSupportTest} under Gradle CI.
 */
final class DiffSanSupportJUnitTest {
    @Test
    void runsAllDiffSanTests() throws Exception {
        DiffSanSupportTest.runAll();
    }
}
