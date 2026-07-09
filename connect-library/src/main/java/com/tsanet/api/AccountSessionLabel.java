package com.tsanet.api;

import java.util.Locale;

public final class AccountSessionLabel {
    private AccountSessionLabel() {
    }

    public static String fromUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }

        String normalized = username.trim().toLowerCase(Locale.ROOT)
            .replace('@', '-')
            .replaceAll("[^a-z0-9._-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-+|-+$", "");

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("username yields empty session label");
        }
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized;
    }
}
