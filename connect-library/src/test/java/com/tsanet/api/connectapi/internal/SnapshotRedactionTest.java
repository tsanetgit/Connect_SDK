package com.tsanet.api.connectapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.connectapi.dto.UserContextDto;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SnapshotRedactionTest {
    @Test
    void redactsBearerWithoutChangingSessionAccessors() {
        Instant expiresAt = Instant.parse("2026-01-01T00:00:00Z");
        var context = new UserContextDto(1L, "Sample", 2L, "sample-user", "user@example.invalid", "Sample", "User");
        var snapshot = new ConnectApiSessionStore.Snapshot(
            "sample-bearer", "sample-user", "sample-account", AuthMode.CONNECT1_PASSWORD, expiresAt, context);

        assertTrue(snapshot.toString().contains("bearerToken=<redacted>"));
        assertFalse(snapshot.toString().contains("sample-bearer"));
        assertEquals("sample-bearer", snapshot.bearerToken());
        assertEquals("sample-user", snapshot.username());
        assertEquals("sample-account", snapshot.accountId());
        assertEquals(AuthMode.CONNECT1_PASSWORD, snapshot.authMode());
        assertEquals(expiresAt, snapshot.expiresAt());
        assertEquals(context, snapshot.userContext());
        assertTrue(ConnectApiSessionStore.Snapshot.EMPTY.toString().contains("bearerToken=<redacted>"));
    }
}
