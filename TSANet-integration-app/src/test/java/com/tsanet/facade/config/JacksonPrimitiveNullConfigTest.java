package com.tsanet.facade.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the Jackson 3 default flip that the Boot 4 upgrade worked around: main
 * application.yml sets {@code fail-on-null-for-primitives: false} so an explicit
 * JSON null on a primitive still binds to the Jackson 2 default instead of
 * rejecting the payload. Nothing else exercises that line (Connect_SDK#87).
 *
 * <p>The test-resources application.yml shadows the main one on the classpath,
 * so a plain {@code @SpringBootTest} would never load the setting under test.
 * {@code spring.config.location} lists the main file first and the test file
 * second: later locations override earlier ones, so the test overrides (mock
 * base URL, tmp storage, CLI off) still apply while the main file supplies the
 * Jackson setting. Remove that line from the main file and this test goes red.
 * The {@code file:} paths resolve against the module directory, surefire's default
 * working directory; {@code spring.config.location} replaces the default search
 * locations entirely, which is intended here.
 */
@SpringBootTest(properties = {
    "spring.config.location=file:./src/main/resources/application.yml,file:./src/test/resources/application.yml"
})
class JacksonPrimitiveNullConfigTest {

    record Probe(int count) {
    }

    @Autowired
    private ObjectMapper mapper;

    @Test
    void anExplicitNullOnAPrimitiveBindsToTheDefaultInsteadOfThrowing() {
        Probe probe = mapper.readValue("{\"count\":null}", Probe.class);
        assertThat(probe.count()).isZero();
    }
}
