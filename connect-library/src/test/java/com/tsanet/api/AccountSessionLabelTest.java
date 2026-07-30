package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountSessionLabelTest {
    @Test
    void itNormalizesEmailUsernames() {
        assertThat(AccountSessionLabel.fromUsername("api@appko.com")).isEqualTo("api-appko.com");
    }

    @Test
    void itTrimsAndLowercases() {
        assertThat(AccountSessionLabel.fromUsername("  Beta.User@Example.COM  ")).isEqualTo("beta.user-example.com");
    }

    @Test
    void itRejectsBlankUsernames() {
        assertThatThrownBy(() -> AccountSessionLabel.fromUsername("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("username");
    }
}
