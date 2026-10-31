package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The user store the gate is built from, called directly; no Spring context. */
class SecurityConfigUsersTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private static DemoAuthProperties of(String user, String password, String guestUser, String guestPassword) {
        return new DemoAuthProperties(user, password, new DemoAuthProperties.Guest(guestUser, guestPassword, null));
    }

    @Test
    void registersOperatorAndGuestWithTheSameRole() {
        var users = new SecurityConfig().demoUsers(of("shawn", "op", "oracle", "g"));

        var operator = users.loadUserByUsername("shawn");
        var guest = users.loadUserByUsername("oracle");
        assertThat(ENCODER.matches("op", operator.getPassword())).isTrue();
        assertThat(ENCODER.matches("g", guest.getPassword())).isTrue();
        assertThat(ENCODER.matches("op", guest.getPassword())).isFalse();
        assertThat(operator.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_DEMO");
        assertThat(guest.getAuthorities()).extracting(Object::toString)
            .containsExactlyInAnyOrder("ROLE_DEMO", GuestUploadLimitFilter.LIMITED_UPLOADS);
    }

    @Test
    void guestIsAbsentWhenItsPasswordIsBlank() {
        var users = new SecurityConfig().demoUsers(of("shawn", "op", "oracle", ""));

        assertThat(users.loadUserByUsername("shawn")).isNotNull();
        assertThatThrownBy(() -> users.loadUserByUsername("oracle")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername("guest")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void gateOffRegistersNobody() {
        var users = new SecurityConfig().demoUsers(of("shawn", "", null, null));

        assertThatThrownBy(() -> users.loadUserByUsername("shawn")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername("demo")).isInstanceOf(UsernameNotFoundException.class);
    }
}
