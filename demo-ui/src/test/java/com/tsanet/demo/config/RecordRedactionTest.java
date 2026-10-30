package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.demo.web.SettingsController.SaveCredentialsBody;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordRedactionTest {
    @Test
    void credentialsAndRequestBodiesDoNotPrintPasswordsOrClientSecrets() {
        String password = "test-password-sentinel";
        String clientSecret = "test-client-secret-sentinel";
        CredentialsStore.Credentials credentials =
            new CredentialsStore.Credentials("password", "test-user", password, "test-client", clientSecret);
        SaveCredentialsBody body =
            new SaveCredentialsBody("password", "test-user", password, "test-client", clientSecret);

        for (Object value : List.of(credentials, body)) {
            assertThat(value.toString()).contains("<redacted>").doesNotContain(password, clientSecret);
        }
        assertThat(credentials.password()).isEqualTo(password);
        assertThat(credentials.clientSecret()).isEqualTo(clientSecret);
        assertThat(body.password()).isEqualTo(password);
        assertThat(body.clientSecret()).isEqualTo(clientSecret);
    }

    @Test
    void operatorAndGuestPasswordsAreNotPrinted() {
        String operatorPassword = "test-operator-password-sentinel";
        String guestPassword = "test-guest-password-sentinel";
        DemoAuthProperties.Guest guest = new DemoAuthProperties.Guest("guest", guestPassword);
        DemoAuthProperties auth = new DemoAuthProperties("operator", operatorPassword, guest);

        for (Object value : List.of(auth, guest)) {
            assertThat(value.toString()).contains("<redacted>").doesNotContain(operatorPassword, guestPassword);
        }
        assertThat(auth.password()).isEqualTo(operatorPassword);
        assertThat(guest.password()).isEqualTo(guestPassword);
    }
}
