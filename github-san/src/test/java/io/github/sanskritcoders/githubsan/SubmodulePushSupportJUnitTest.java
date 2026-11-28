package io.github.sanskritcoders.githubsan;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 entrypoint executing {@link SubmodulePushSupportTest} under Gradle CI.
 */
final class SubmodulePushSupportJUnitTest {
    @Test
    void runsAllSubmodulePushSupportTests() throws Exception {
        SubmodulePushSupportTest.runAll();
    }
}
