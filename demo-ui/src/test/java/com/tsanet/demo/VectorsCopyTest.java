package com.tsanet.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The demo's copy of the Addendum D vectors is the module's file, byte for byte: a deliberate
 * duplication, asserted here rather than by a comment. Surefire runs with the module directory
 * as the working directory, so the sibling module is one level up; where it isn't checked out
 * (a consumer building demo-ui alone), the test is skipped rather than failed.
 */
class VectorsCopyTest {

    @Test
    void theCopyEqualsTheModulesFile() throws Exception {
        Path original = Path.of("..", "connect-e2ee", "src", "test", "resources", "addendum-d-vectors-THROWAWAY-TEST-KEYS.json");
        assumeTrue(Files.exists(original), "connect-e2ee is not beside demo-ui");
        byte[] copy = E2eeTestVectors.class.getResourceAsStream(E2eeTestVectors.RESOURCE).readAllBytes();
        assertThat(copy).isEqualTo(Files.readAllBytes(original));
    }
}
