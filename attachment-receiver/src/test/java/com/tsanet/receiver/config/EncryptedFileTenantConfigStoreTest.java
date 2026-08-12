package com.tsanet.receiver.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedFileTenantConfigStoreTest {

    @TempDir
    Path dir;

    private EncryptedFileTenantConfigStore store;

    private static String randomKeyBase64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static TenantConfig acme() {
        return new TenantConfig("acme", "push-secret-042", "s3",
                Map.of("bucket", "acme-files"), Map.of("apiToken", "crm-token-042"));
    }

    @BeforeEach
    void newStore() throws Exception {
        store = new EncryptedFileTenantConfigStore(dir,
                EncryptedFileTenantConfigStore.keyFromBase64(randomKeyBase64()));
    }

    @Test
    void roundTripsAConfig() throws Exception {
        store.save(acme());
        Optional<TenantConfig> loaded = store.byPathSegment("acme");
        assertTrue(loaded.isPresent());
        assertEquals(acme(), loaded.get());
    }

    @Test
    void unknownTenantIsEmptyNotAnError() throws Exception {
        assertTrue(store.byPathSegment("nobody").isEmpty());
    }

    @Test
    void ciphertextContainsNoCredentialBytes() throws Exception {
        store.save(acme());
        String onDisk = new String(Files.readAllBytes(dir.resolve("acme.tenant")),
                StandardCharsets.ISO_8859_1);
        assertFalse(onDisk.contains("push-secret-042"), "password visible on disk");
        assertFalse(onDisk.contains("crm-token-042"), "crm credential visible on disk");
        assertFalse(onDisk.contains("acme-files"), "storage property visible on disk");
    }

    @Test
    void bitFlipFailsTheIntegrityCheckLoudly() throws Exception {
        store.save(acme());
        Path file = dir.resolve("acme.tenant");
        byte[] body = Files.readAllBytes(file);
        body[body.length - 1] ^= 0x01;
        Files.write(file, body);
        TenantConfigException e = assertThrows(TenantConfigException.class,
                () -> store.byPathSegment("acme"));
        assertTrue(e.getMessage().contains("integrity"), "message should name the failure: " + e.getMessage());
    }

    @Test
    void swappedTenantFilesDoNotDecrypt() throws Exception {
        // The AAD binding: renaming globex's file to acme.tenant must fail, not load.
        store.save(acme());
        store.save(new TenantConfig("globex", "other-pw", "azure-files", Map.of(), Map.of()));
        Files.copy(dir.resolve("globex.tenant"), dir.resolve("acme.tenant"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        TenantConfigException e = assertThrows(TenantConfigException.class,
                () -> store.byPathSegment("acme"),
                "a swapped config file must fail its integrity check");
        // The mechanism, not just the outcome: AAD binding fails the DECRYPT ("integrity
        // check"). If AAD were silently dropped, decrypt would succeed and the later
        // stored-pathSegment cross-check would throw its distinct message instead.
        assertTrue(e.getMessage().contains("integrity"),
                "swap must fail at decryption (AAD), not at the post-decrypt cross-check: "
                        + e.getMessage());
    }

    @Test
    void readPathRejectsNonTokenSegments() {
        // Defense on the read path: a raw request value must not address arbitrary files.
        assertThrows(IllegalArgumentException.class, () -> store.byPathSegment("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.byPathSegment(null));
    }

    @Test
    void wrongKeyFailsLoudlyNotEmptily() throws Exception {
        store.save(acme());
        EncryptedFileTenantConfigStore other = new EncryptedFileTenantConfigStore(dir,
                EncryptedFileTenantConfigStore.keyFromBase64(randomKeyBase64()));
        assertThrows(TenantConfigException.class, () -> other.byPathSegment("acme"));
    }

    @Test
    void listsAllTenants() throws Exception {
        store.save(acme());
        store.save(new TenantConfig("globex", "pw2", "azure-files", Map.of(), Map.of()));
        assertEquals(2, store.all().size());
    }

    @Test
    void saveReplacesAtomicallyAndLeavesNoTempFiles() throws Exception {
        store.save(acme());
        store.save(new TenantConfig("acme", "rotated-pw", "s3",
                Map.of("bucket", "acme-files"), Map.of()));
        assertEquals("rotated-pw", store.byPathSegment("acme").orElseThrow().pushPassword());
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(f -> f.getFileName().toString().endsWith(".tenant")),
                    "no temp files may remain after save");
        }
    }

    @Test
    void truncatedKeyFailsAtStartupWithByteCount() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EncryptedFileTenantConfigStore.keyFromBase64(
                        Base64.getEncoder().encodeToString(new byte[16])));
        assertTrue(e.getMessage().contains("16"), "message should say what it got: " + e.getMessage());
    }
}
