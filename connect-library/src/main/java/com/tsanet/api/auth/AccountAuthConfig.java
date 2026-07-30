package com.tsanet.api.auth;

public sealed interface AccountAuthConfig permits ClientCredentialsAuthConfig, PasswordAuthConfig {
    AuthMode mode();
}
