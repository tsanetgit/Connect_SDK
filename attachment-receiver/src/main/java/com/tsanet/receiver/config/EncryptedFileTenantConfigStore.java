package com.tsanet.receiver.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * File-per-tenant store, AES-256-GCM at rest. {@code <pathSegment>.tenant} under one
 * directory; the file body is a random 12-byte IV followed by the ciphertext. The
 * tenant's path segment is bound into the ciphertext as GCM additional authenticated
 * data, so two tenants' files cannot be swapped on disk and still decrypt. Writes go to
 * a temp file first and land by atomic move: a crash mid-save can never leave a torn
 * file that reads as tampering later.
 */
public final class EncryptedFileTenantConfigStore implements TenantConfigStore {

    private static final String FILE_SUFFIX = ".tenant";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final Path directory;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptedFileTenantConfigStore(Path directory, SecretKey key) throws TenantConfigException {
        this.directory = directory;
        this.key = key;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new TenantConfigException("cannot create config directory " + directory, e);
        }
    }

    /**
     * The deployment-reality key path: a base64 value from an environment variable or
     * secret mount. Fails at startup, loudly, on anything but exactly 32 decoded bytes —
     * a truncated key discovered at first tenant read is a debugging trap.
     */
    public static SecretKey keyFromBase64(String base64) {
        byte[] decoded = Base64.getDecoder().decode(base64);
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "AES-256 key must decode to exactly " + KEY_BYTES + " bytes, got " + decoded.length);
        }
        return new SecretKeySpec(decoded, "AES");
    }

    @Override
    public Optional<TenantConfig> byPathSegment(String pathSegment) throws TenantConfigException {
        if (pathSegment == null || !TenantConfig.PATH_SEGMENT.matcher(pathSegment).matches()) {
            throw new IllegalArgumentException(
                    "not a valid routing token: '" + pathSegment + "'");
        }
        Path file = directory.resolve(pathSegment + FILE_SUFFIX);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file, pathSegment));
    }

    @Override
    public List<TenantConfig> all() throws TenantConfigException {
        List<TenantConfig> configs = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(FILE_SUFFIX)).toList()) {
                String name = file.getFileName().toString();
                String segment = name.substring(0, name.length() - FILE_SUFFIX.length());
                configs.add(read(file, segment));
            }
        } catch (IOException e) {
            throw new TenantConfigException("cannot list config directory " + directory, e);
        }
        return List.copyOf(configs);
    }

    @Override
    public void save(TenantConfig config) throws TenantConfigException {
        byte[] plaintext = serialize(config);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(config.pathSegment().getBytes(StandardCharsets.UTF_8));
            ciphertext = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new TenantConfigException("encrypt failed for tenant " + config.pathSegment(), e);
        }
        Path target = directory.resolve(config.pathSegment() + FILE_SUFFIX);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, config.pathSegment(), ".tmp");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(iv);
            body.write(ciphertext);
            Files.write(temp, body.toByteArray());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException e) {
            throw new TenantConfigException("cannot write config for tenant " + config.pathSegment(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a ciphertext-only temp file.
                }
            }
        }
    }

    private TenantConfig read(Path file, String pathSegment) throws TenantConfigException {
        byte[] body;
        try {
            body = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TenantConfigException("cannot read config for tenant " + pathSegment, e);
        }
        if (body.length <= IV_BYTES) {
            throw new TenantConfigException("config for tenant " + pathSegment + " is truncated");
        }
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, body, 0, IV_BYTES));
            cipher.updateAAD(pathSegment.getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(body, IV_BYTES, body.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new TenantConfigException("config for tenant " + pathSegment
                    + " failed its integrity check (tampering, a swapped file, or the wrong key)", e);
        }
        TenantConfig config = deserialize(plaintext, pathSegment);
        if (!config.pathSegment().equals(pathSegment)) {
            throw new TenantConfigException("config file for tenant " + pathSegment
                    + " contains configuration for tenant " + config.pathSegment());
        }
        return config;
    }

    private static byte[] serialize(TenantConfig config) throws TenantConfigException {
        Properties props = new Properties();
        props.setProperty("pathSegment", config.pathSegment());
        props.setProperty("pushPassword", config.pushPassword());
        props.setProperty("storageBackend", config.storageBackend());
        config.storageProperties().forEach((k, v) -> props.setProperty("storage." + k, v));
        config.crmProperties().forEach((k, v) -> props.setProperty("crm." + k, v));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            props.store(out, null);
        } catch (IOException e) {
            throw new TenantConfigException("serialize failed for tenant " + config.pathSegment(), e);
        }
        return out.toByteArray();
    }

    private static TenantConfig deserialize(byte[] plaintext, String pathSegment)
            throws TenantConfigException {
        Properties props = new Properties();
        try {
            props.load(new ByteArrayInputStream(plaintext));
        } catch (IOException e) {
            throw new TenantConfigException("parse failed for tenant " + pathSegment, e);
        }
        Map<String, String> storage = new HashMap<>();
        Map<String, String> crm = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("storage.")) {
                storage.put(name.substring("storage.".length()), props.getProperty(name));
            } else if (name.startsWith("crm.")) {
                crm.put(name.substring("crm.".length()), props.getProperty(name));
            }
        }
        try {
            return new TenantConfig(
                    props.getProperty("pathSegment"),
                    props.getProperty("pushPassword"),
                    props.getProperty("storageBackend"),
                    storage, crm);
        } catch (RuntimeException e) {
            throw new TenantConfigException(
                    "config for tenant " + pathSegment + " decrypted but is not valid: " + e.getMessage(), e);
        }
    }
}
