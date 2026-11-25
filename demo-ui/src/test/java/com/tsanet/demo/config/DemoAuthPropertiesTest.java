package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure truth table for the gate configuration; no Spring. */
class DemoAuthPropertiesTest {

    private static DemoAuthProperties of(String user, String password, String guestUser, String guestPassword) {
        return new DemoAuthProperties(user, password, new DemoAuthProperties.Guest(guestUser, guestPassword, null));
    }

    @Test
    void gateOffWhenNoOperatorPassword() {
        var props = of("demo", "", null, null);
        assertThat(props.enabled()).isFalse();
        assertThat(props.guestEnabled()).isFalse();
    }

    @Test
    void operatorOnlyLeavesGuestOff() {
        var props = of(null, "op", null, "");
        assertThat(props.enabled()).isTrue();
        assertThat(props.guestEnabled()).isFalse();
        assertThat(props.effectiveUsername()).isEqualTo("demo");
    }

    @Test
    void guestOnWhenBothPasswordsSet() {
        var props = of("operator", "op", null, "g");
        assertThat(props.guestEnabled()).isTrue();
        assertThat(props.effectiveGuestUsername()).isEqualTo("guest");
    }

    @Test
    void guestUsernameWithoutPasswordIsNotAGuest() {
        var props = of("operator", "op", "visitor", " ");
        assertThat(props.guestEnabled()).isFalse();
    }

    @Test
    void absentGuestBlockBindsAsNoGuest() {
        var props = new DemoAuthProperties("operator", "op", null);
        assertThat(props.guestEnabled()).isFalse();
        assertThat(props.guest()).isNotNull();
    }

    @Test
    void guestPasswordWithoutOperatorPasswordFailsStartup() {
        // The message names the variables, never the value (it is logged at startup).
        assertThatThrownBy(() -> of("operator", "", "visitor", "s3cret-guest-pw"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TSANET_DEMO_AUTH_PASSWORD")
            .hasMessageNotContaining("s3cret-guest-pw");
    }

    @Test
    void sameUsernameForBothLoginsFailsStartupCaseInsensitively() {
        assertThatThrownBy(() -> of("Visitor", "op", "visitor", "g"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must differ");
        // The defaults collide too when the operator is explicitly named "guest".
        assertThatThrownBy(() -> of("guest", "op", null, "g"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateUsernamesAreFineWhileTheGuestIsOff() {
        var props = of("guest", "op", "guest", "");
        assertThat(props.enabled()).isTrue();
        assertThat(props.guestEnabled()).isFalse();
    }

    @Test
    void toStringNeverPrintsEitherPassword() {
        var props = of("operator", "s3cret-operator-pw", "visitor", "s3cret-guest-pw");
        assertThat(props.toString())
            .startsWith("DemoAuthProperties[username=operator, password set")
            .contains("visitor")
            .doesNotContain("s3cret-operator-pw", "s3cret-guest-pw");
        assertThat(props.guest().toString()).contains("visitor").doesNotContain("s3cret-guest-pw");
        assertThat(of("operator", "", null, null).toString()).startsWith("DemoAuthProperties[username=operator, no password");
    }

    @org.junit.jupiter.api.Test
    void aGuestUploadLimitDefaultsToOneGigabyteAndMustBePositive() {
        var guest = new DemoAuthProperties.Guest("g", "gp", null);
        org.assertj.core.api.Assertions.assertThat(new DemoAuthProperties("u", "p", guest).guestMaxUploadBytes())
            .isEqualTo(1024L * 1024 * 1024);
        org.assertj.core.api.Assertions.assertThat(
                new DemoAuthProperties("u", "p", new DemoAuthProperties.Guest("g", "gp", 5L)).guestMaxUploadBytes())
            .isEqualTo(5L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DemoAuthProperties("u", "p", new DemoAuthProperties.Guest("g", "gp", 0L)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("TSANET_DEMO_AUTH_GUEST_MAX_UPLOAD_BYTES");
    }
}
