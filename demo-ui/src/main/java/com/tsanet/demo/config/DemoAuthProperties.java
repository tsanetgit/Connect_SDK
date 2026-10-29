package com.tsanet.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional HTTP Basic gate for hosted deployments. Auth is enabled only when
 * an operator password is configured (TSANET_DEMO_AUTH_PASSWORD); with no
 * password the app stays open, which keeps local development friction-free.
 *
 * <p>A second, optional guest login (TSANET_DEMO_AUTH_GUEST_USER and
 * TSANET_DEMO_AUTH_GUEST_PASSWORD) lets the operator hand members a login that
 * is not their own and rotate it on its own. It has the same rights as the
 * operator: the gate is the demo's only perimeter (tsanetgit/Connect_SDK#148).
 * Both values are read once at startup, so rotating either login means a
 * restart (on a hosted deploy, a redeploy that picks up the new secret).
 *
 * <p>Two combinations are refused at startup rather than silently honored: a
 * guest password with no operator password, because the gate would then be off
 * while a login is believed to exist; and the same username for both logins,
 * compared case-insensitively because the in-memory user store keys users by
 * lower-cased name. Neither message contains a password.
 */
@ConfigurationProperties(prefix = "tsanet.demo.auth")
public record DemoAuthProperties(String username, String password, Guest guest) {

    private static final String DEFAULT_USERNAME = "demo";
    private static final String DEFAULT_GUEST_USERNAME = "guest";

    /** The guest login; absent from the configuration binds as a record of nulls. */
    public record Guest(String username, String password) {
    }

    public DemoAuthProperties {
        if (guest == null) {
            guest = new Guest(null, null);
        }
        boolean guestConfigured = hasText(guest.password());
        if (guestConfigured && !hasText(password)) {
            throw new IllegalStateException(
                "TSANET_DEMO_AUTH_GUEST_PASSWORD is set but TSANET_DEMO_AUTH_PASSWORD is not: the gate would be off"
                + " while a guest login is configured. Set the operator password or unset the guest one.");
        }
        if (guestConfigured
            && orDefault(username, DEFAULT_USERNAME).equalsIgnoreCase(orDefault(guest.username(), DEFAULT_GUEST_USERNAME))) {
            throw new IllegalStateException(
                "TSANET_DEMO_AUTH_USER and TSANET_DEMO_AUTH_GUEST_USER must differ (compared case-insensitively).");
        }
    }

    public boolean enabled() {
        return hasText(password);
    }

    /** True only when the gate is on and a guest password is configured. */
    public boolean guestEnabled() {
        return enabled() && hasText(guest.password());
    }

    public String effectiveUsername() {
        return orDefault(username, DEFAULT_USERNAME);
    }

    public String effectiveGuestUsername() {
        return orDefault(guest.username(), DEFAULT_GUEST_USERNAME);
    }

    private static String orDefault(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
