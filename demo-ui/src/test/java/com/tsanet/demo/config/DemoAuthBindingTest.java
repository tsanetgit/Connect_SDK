package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The record as Spring Boot binds it, rather than as a test constructs it: the
 * defaults when nothing is set, and the startup failure's full text when the
 * guest password is set alone, which is what reaches the log.
 */
class DemoAuthBindingTest {

    @Configuration
    @EnableConfigurationProperties(DemoAuthProperties.class)
    static class Bound {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bound.class);

    @Test
    void nothingSetBindsToTheDefaultsWithTheGateOff() {
        runner.run(context -> {
            var auth = context.getBean(DemoAuthProperties.class);
            assertThat(auth.enabled()).isFalse();
            assertThat(auth.guestEnabled()).isFalse();
            assertThat(auth.effectiveUsername()).isEqualTo("demo");
            assertThat(auth.effectiveGuestUsername()).isEqualTo("guest");
        });
    }

    @Test
    void blankUsernamesFallBackToTheDefaults() {
        runner.withPropertyValues(
                "tsanet.demo.auth.username=",
                "tsanet.demo.auth.password=op",
                "tsanet.demo.auth.guest.username=",
                "tsanet.demo.auth.guest.password=g")
            .run(context -> {
                var auth = context.getBean(DemoAuthProperties.class);
                assertThat(auth.guestEnabled()).isTrue();
                assertThat(auth.effectiveUsername()).isEqualTo("demo");
                assertThat(auth.effectiveGuestUsername()).isEqualTo("guest");
            });
    }

    @Test
    void guestPasswordAloneFailsStartupWithoutPrintingIt() {
        runner.withPropertyValues("tsanet.demo.auth.guest.password=s3cret-guest-pw")
            .run(context -> {
                assertThat(context).hasFailed();
                var text = new StringBuilder();
                for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                    text.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
                }
                assertThat(text).contains("TSANET_DEMO_AUTH_PASSWORD");
                assertThat(text).doesNotContain("s3cret-guest-pw");
            });
    }
}
