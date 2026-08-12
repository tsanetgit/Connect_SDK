package com.tsanet.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
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
                    .requestMatchers("/healthz").permitAll()
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
        return new InMemoryUserDetailsManager(User.builder()
            .username(auth.username() != null && !auth.username().isBlank() ? auth.username() : "demo")
            .password(encoder.encode(auth.password()))
            .roles("DEMO")
            .build());
    }
}
