package com.tsanet.receiver.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantConfigTest {

    private static TenantConfig valid() {
        return new TenantConfig("acme", "push-secret", "s3",
                Map.of("bucket", "acme-files", "secretKey", "storage-credential-value"),
                Map.of("apiToken", "crm-credential-value"));
    }

    @Test
    void acceptsAValidRoutingToken() {
        assertEquals("acme", valid().pathSegment());
    }

    @Test
    void rejectsRoutingTokensThatAreNotFilenameSafe() {
        // Allowlist, not blocklist: each of these is dangerous as a path or filename
        // even though none contains a forward slash. Deliberately absent: Windows
        // reserved names ("con", "nul") — all-lowercase forms PASS the allowlist; the
        // receiver deploys on Linux containers and does not defend Windows filesystems.
        for (String bad : new String[]{"..", "Acme", "a/b", "a\\b", "", "-leading",
                "a".repeat(65), "dot.dot"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TenantConfig(bad, "pw", "s3", Map.of(), Map.of()),
                    "should have rejected: '" + bad + "'");
        }
    }

    @Test
    void rejectsBlankPasswordAndBackend() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantConfig("acme", " ", "s3", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantConfig("acme", "pw", " ", Map.of(), Map.of()));
    }

    @Test
    void emptyCrmPropertiesMeansNoDelivery() {
        TenantConfig config = new TenantConfig("acme", "pw", "s3", Map.of(), Map.of());
        assertFalse(config.hasCrmDelivery());
        assertTrue(valid().hasCrmDelivery());
    }

    @Test
    void toStringRedactsEveryCredential() {
        String printed = valid().toString();
        assertFalse(printed.contains("push-secret"), "password leaked into toString");
        assertFalse(printed.contains("storage-credential-value"), "storage credential leaked into toString");
        assertFalse(printed.contains("crm-credential-value"), "crm credential leaked into toString");
        assertTrue(printed.contains("acme"), "structure (tenant, keys) should still print");
    }

    @Test
    void propertyMapsAreImmutableCopies() {
        assertThrows(UnsupportedOperationException.class,
                () -> valid().storageProperties().put("k", "v"));
    }
}
