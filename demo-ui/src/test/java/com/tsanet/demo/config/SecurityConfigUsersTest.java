package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The user store the gate is built from the workspaces' logins, called directly; no Spring context. */
class SecurityConfigUsersTest {

    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @TempDir
    Path dataDir;

    /** The workspaces a deploy with these gate settings builds; nothing is written under the data dir. */
    private Workspaces of(String user, String password, String guestUser, String guestPassword) {
        return new Workspaces(
            new DemoProperties(Map.of(), "beta", dataDir.toString(), null, null, null, null),
            E2eeKeys.disabled(),
            new DemoE2eeProperties(null, null),
            new DemoAuthProperties(user, password, new DemoAuthProperties.Guest(guestUser, guestPassword, null)));
    }

    @Test
    void registersOperatorAndGuestWithTheSameRole() {
        var users = new SecurityConfig().demoUsers(of("operator", "op", "visitor", "g"));

        var operator = users.loadUserByUsername("operator");
        var guest = users.loadUserByUsername("visitor");
        assertThat(ENCODER.matches("op", operator.getPassword())).isTrue();
        assertThat(ENCODER.matches("g", guest.getPassword())).isTrue();
        assertThat(ENCODER.matches("op", guest.getPassword())).isFalse();
        assertThat(operator.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_DEMO");
        assertThat(guest.getAuthorities()).extracting(Object::toString)
            .containsExactlyInAnyOrder("ROLE_DEMO", GuestUploadLimitFilter.LIMITED_UPLOADS);
    }

    @Test
    void guestIsAbsentWhenItsPasswordIsBlank() {
        var users = new SecurityConfig().demoUsers(of("operator", "op", "visitor", ""));

        assertThat(users.loadUserByUsername("operator")).isNotNull();
        assertThatThrownBy(() -> users.loadUserByUsername("visitor")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername("guest")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void gateOffRegistersNobody() {
        var users = new SecurityConfig().demoUsers(of("operator", "", null, null));

        assertThatThrownBy(() -> users.loadUserByUsername("operator")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername("demo")).isInstanceOf(UsernameNotFoundException.class);
    }
}
