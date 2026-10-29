package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/**
 * The record as Spring Boot binds it, rather than as a test constructs it: the
 * defaults when nothing is set, and the startup failure when the guest password
 * is set alone: both the exception chain and the failure-analysis report Boot
 * writes to the log, which is the text an operator actually reads.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void theLoggedFailureReportNeverPrintsThePassword(CapturedOutput output) {
        // A real SpringApplication boot, so Boot's failure analyzer writes its
        // "APPLICATION FAILED TO START" report (with any Property/Value lines it
        // decides to include) to the captured log.
        // Command-line arguments outrank application.yml, whose empty
        // ${TSANET_DEMO_AUTH_GUEST_PASSWORD:} placeholder is on the test classpath.
        var app = new SpringApplication(Bound.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> app.run(
                "--spring.main.banner-mode=off",
                "--tsanet.demo.auth.guest.password=s3cret-guest-pw"))
            .isInstanceOf(Exception.class);

        assertThat(output.getAll())
            .contains("APPLICATION FAILED TO START")
            .contains("TSANET_DEMO_AUTH_PASSWORD")
            .doesNotContain("s3cret-guest-pw");
    }
}
