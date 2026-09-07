package com.tsanet.api.connectapi.internal;

/**
 * What a password login returns: the bearer and, when the API states one, its lifetime.
 * {@code expiresInSeconds} is null when the API omitted it; the session then relies on the
 * 401 path alone.
 */
public record PasswordLogin(String accessToken, Integer expiresInSeconds) {
}
