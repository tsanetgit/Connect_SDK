package com.tsanet.application.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The scripted demo refuses a plain-http base URL at startup unless the operator opted in, same rule as the console. */
class TsaNetApiConfigurationBeanTest {

    private static TsaNetApplicationProperties propertiesFor(String baseUrl, Boolean allowInsecureHttp) {
        return new TsaNetApplicationProperties(
            new TsaNetApplicationProperties.Api(baseUrl, allowInsecureHttp),
            null,
            new TsaNetApplicationProperties.Storage(System.getProperty("java.io.tmpdir") + "/tsanet-bean-test"),
            null
        );
    }

    @Test
    void aPlainHttpBaseUrlFailsAtStartupWithTheFixInTheMessage() {
        assertThatThrownBy(() -> new TsaNetApiConfigurationBean().tsaNetApiSessionFactory(propertiesFor("http://connect.example", null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("https")
            .hasMessageContaining("tsanet.api.allow-insecure-http=true");
    }

    @Test
    void anHttpsBaseUrlAndAnOptedInHttpMockBothStart() {
        assertThatCode(() -> new TsaNetApiConfigurationBean().tsaNetApiSessionFactory(propertiesFor("https://connect.example", null)))
            .doesNotThrowAnyException();
        assertThatCode(() -> new TsaNetApiConfigurationBean().tsaNetApiSessionFactory(propertiesFor("http://localhost:8080", true)))
            .doesNotThrowAnyException();
    }
}
