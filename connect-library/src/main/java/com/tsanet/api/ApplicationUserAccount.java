package com.tsanet.api;

public record ApplicationUserAccount(String id, String username, String password, String sqlitePath) {
    public ApplicationUserAccount {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("account id is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("account username is required");
        }
        if (sqlitePath == null || sqlitePath.isBlank()) {
            throw new IllegalArgumentException("account sqlitePath is required");
        }
    }
}
