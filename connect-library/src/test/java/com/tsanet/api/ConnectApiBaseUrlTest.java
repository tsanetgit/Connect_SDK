package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The shared https guard the console and both demos call at startup. */
class ConnectApiBaseUrlTest {

    @Test
    void anHttpsBaseUrlPassesWhateverTheCase() {
        assertThatCode(() -> ConnectApiBaseUrl.requireHttps("https://connect.example", false, "x.base-url", "x.allow-insecure-http"))
            .doesNotThrowAnyException();
        assertThatCode(() -> ConnectApiBaseUrl.requireHttps("HTTPS://connect.example", false, "x.base-url", "x.allow-insecure-http"))
            .doesNotThrowAnyException();
    }

    @Test
    void aPlainHttpBaseUrlFailsNamingTheApplicationsOwnOptIn() {
        assertThatThrownBy(() -> ConnectApiBaseUrl.requireHttps("http://connect.example", false, "tsanet.demo.x.api-base-url", "tsanet.demo.allow-insecure-http"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("https")
            .hasMessageContaining("tsanet.demo.allow-insecure-http=true");
    }

    @Test
    void theOptInAdmitsAnyNonHttpsHost() {
        assertThatCode(() -> ConnectApiBaseUrl.requireHttps("http://localhost:8080", true, "x.base-url", "x")).doesNotThrowAnyException();
    }

    @Test
    void aMissingBaseUrlIsItsOwnErrorEvenWithTheOptIn() {
        assertThatThrownBy(() -> ConnectApiBaseUrl.requireHttps(" ", true, "tsanet.api.base-url", "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tsanet.api.base-url is required");
        assertThatThrownBy(() -> ConnectApiBaseUrl.requireHttps(null, true, "tsanet.api.base-url", "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("required");
    }
}
