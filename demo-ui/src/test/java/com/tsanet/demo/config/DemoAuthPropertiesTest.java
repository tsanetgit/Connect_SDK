package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure truth table for the gate configuration; no Spring. */
class DemoAuthPropertiesTest {

    private static DemoAuthProperties of(String user, String password, String guestUser, String guestPassword) {
        return new DemoAuthProperties(user, password, new DemoAuthProperties.Guest(guestUser, guestPassword));
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
        var props = of("shawn", "op", null, "g");
        assertThat(props.guestEnabled()).isTrue();
        assertThat(props.effectiveGuestUsername()).isEqualTo("guest");
    }

    @Test
    void guestUsernameWithoutPasswordIsNotAGuest() {
        var props = of("shawn", "op", "oracle", " ");
        assertThat(props.guestEnabled()).isFalse();
    }

    @Test
    void absentGuestBlockBindsAsNoGuest() {
        var props = new DemoAuthProperties("shawn", "op", null);
        assertThat(props.guestEnabled()).isFalse();
        assertThat(props.guest()).isNotNull();
    }

    @Test
    void guestPasswordWithoutOperatorPasswordFailsStartup() {
        // The message names the variables, never the value (it is logged at startup).
        assertThatThrownBy(() -> of("shawn", "", "oracle", "s3cret-guest-pw"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TSANET_DEMO_AUTH_PASSWORD")
            .hasMessageNotContaining("s3cret-guest-pw");
    }

    @Test
    void sameUsernameForBothLoginsFailsStartupCaseInsensitively() {
        assertThatThrownBy(() -> of("Oracle", "op", "oracle", "g"))
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
}
