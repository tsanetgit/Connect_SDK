package com.tsanet.demo.config;

import com.tsanet.demo.web.JwksController;
import java.util.ArrayList;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(DemoAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, DemoAuthProperties auth) throws Exception {
        // The SPA posts JSON without CSRF tokens; the Basic gate is the perimeter.
        http.csrf(csrf -> csrf.disable());
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
