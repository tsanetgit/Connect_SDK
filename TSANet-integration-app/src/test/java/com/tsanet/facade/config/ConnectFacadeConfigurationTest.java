package com.tsanet.facade.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The startup guard: no bearer over plain http unless the operator said so, in the config, on purpose. */
class ConnectFacadeConfigurationTest {

    @Test
    void anHttpsBaseUrlPasses() {
        assertThatCode(() -> ConnectFacadeConfiguration.requireHttps(
            new ConnectFacadeProperties.Api("https://connect2.tsanet.net", null))).doesNotThrowAnyException();
        assertThatCode(() -> ConnectFacadeConfiguration.requireHttps(
            new ConnectFacadeProperties.Api("HTTPS://connect2.tsanet.net", false))).doesNotThrowAnyException();
    }

    @Test
    void aPlainHttpBaseUrlFailsAtStartupWithTheFixInTheMessage() {
        assertThatThrownBy(() -> ConnectFacadeConfiguration.requireHttps(
            new ConnectFacadeProperties.Api("http://connect2.tsanet.net", null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("https")
            .hasMessageContaining("tsanet.api.allow-insecure-http");
    }

    @Test
    void theOptOutAdmitsALocalMockOnly() {
        assertThatCode(() -> ConnectFacadeConfiguration.requireHttps(
            new ConnectFacadeProperties.Api("http://localhost:8080", true))).doesNotThrowAnyException();
    }

    @Test
    void aMissingBaseUrlIsItsOwnError() {
        assertThatThrownBy(() -> ConnectFacadeConfiguration.requireHttps(new ConnectFacadeProperties.Api(" ", true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tsanet.api.base-url is required");
    }
}
