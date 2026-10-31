package com.tsanet.demo.config;

import com.tsanet.demo.web.JwksController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(DemoAuthProperties.class)
public class SecurityConfig {

    static final String CSRF_REFUSED = "{\"error\":\"The request's cross-site protection token is missing or out of date."
        + " Reload the page and try again.\"}";
    static final String FORBIDDEN = "{\"error\":\"Forbidden\"}";

    /**
     * Writes the 403 directly. CsrfFilter runs before the Basic filter, so sendError's dispatch to
     * /error would be unauthenticated and answer 401, which prompts for the login.
     */
    static void refuse(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(denied instanceof CsrfException ? CSRF_REFUSED : FORBIDDEN);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, DemoAuthProperties auth) throws Exception {
        // A browser replays cached Basic credentials on a request another site triggers, so the gate
        // alone doesn't stop a cross-site form. fetchJson in app.js sends the cookie's token as a header.
        http.csrf(csrf -> csrf.spa());
        http.exceptionHandling(handling -> handling.accessDeniedHandler(SecurityConfig::refuse));
        if (auth.enabled()) {
            http.authorizeHttpRequests(a -> a
                    // The published public keys are read by partners' key fetchers, which hold no login.
                    .requestMatchers("/healthz", JwksController.PATH).permitAll()
                    .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        } else {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    public UserDetailsService demoUsers(DemoAuthProperties auth) {
        if (!auth.enabled()) {
            return new InMemoryUserDetailsManager();
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var users = new ArrayList<UserDetails>();
        users.add(User.builder()
            .username(auth.effectiveUsername())
            .password(encoder.encode(auth.password()))
            .roles("DEMO")
            .build());
        if (auth.guestEnabled()) {
            // Same role on purpose: the gate is the only perimeter (tsanetgit/Connect_SDK#148).
            users.add(User.builder()
                .username(auth.effectiveGuestUsername())
                .password(encoder.encode(auth.guest().password()))
                .roles("DEMO")
                .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
