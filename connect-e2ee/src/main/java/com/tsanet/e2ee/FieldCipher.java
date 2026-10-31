package com.tsanet.e2ee;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWECryptoParts;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObjectJSON;
import com.nimbusds.jose.crypto.impl.ContentCryptoProvider;
import com.nimbusds.jose.jca.JWEJCAContext;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * One protected field as a JWE in general JSON serialization (RFC 7516 §7.2.1), per Addendum C:
 * one AES-256-GCM encryption, the content key wrapped with RSA-OAEP-256 once per recipient, and a
 * protected header of {@code enc}, {@code tsa_ver}, {@code tsa_jti} and {@code tsa_fld}.
 *
 * <p>The JSON is assembled here rather than by Nimbus's multi-recipient encrypter, so every member
 * the profile fixes ({@code alg} and {@code kid} per recipient, nothing else unprotected) is exactly
 * as specified. Nimbus does the AES-GCM; on decrypt the AAD is the protected header exactly as
 * received, never a re-serialization, so a JWE from another library verifies.
 */
final class FieldCipher {
    private static final String KEY_WRAP_ALG = "RSA-OAEP-256";
    private static final int CONTENT_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JWEJCAContext JCA = new JWEJCAContext();
    /** The profile's protected header, and nothing else: no {@code zip}, {@code crit} or anything a sender adds. */
    private static final java.util.Set<String> PROTECTED_MEMBERS = java.util.Set.of("enc", "tsa_ver", "tsa_jti", "tsa_fld");

    private FieldCipher() {
    }

    /** The binding a field's protected header carries. */
    record Binding(String version, String jti, String field) {
    }

    /** A parsed JWE: its protected header, its recipients and its parts, nothing decrypted. */
    record Parsed(JWEObjectJSON jwe, Binding binding) {
    }

    /** Thrown for any JWE that can't be parsed or opened; the message names no value. */
    static final class FieldCipherException extends Exception {
        FieldCipherException(String reason) {
            super(reason);
        }
    }

    static String encrypt(String plaintext, Binding binding, List<RSAKey> recipients) throws GeneralSecurityException {
        Map<String, Object> protectedHeader = new LinkedHashMap<>();
        protectedHeader.put("enc", EncryptionMethod.A256GCM.getName());
        protectedHeader.put("tsa_ver", binding.version());
        protectedHeader.put("tsa_jti", binding.jti());
        protectedHeader.put("tsa_fld", binding.field());
        Base64URL protectedB64 = Base64URL.encode(JSONObjectUtils.toJSONString(protectedHeader).getBytes(StandardCharsets.UTF_8));

        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(CONTENT_KEY_BYTES * 8, RANDOM);
        SecretKey contentKey = generator.generateKey();

        JWECryptoParts parts;
        try {
            JWEHeader header = JWEHeader.parse(protectedB64);
            byte[] aad = protectedB64.toString().getBytes(StandardCharsets.US_ASCII);
            parts = ContentCryptoProvider.encrypt(header, plaintext.getBytes(StandardCharsets.UTF_8), aad, contentKey, null, JCA);
        } catch (ParseException | JOSEException failed) {
            throw new GeneralSecurityException("Field encryption failed");
        }

        List<Object> recipientEntries = new ArrayList<>();
        for (RSAKey recipient : recipients) {
            Map<String, Object> unprotected = new LinkedHashMap<>();
            unprotected.put("alg", KEY_WRAP_ALG);
            unprotected.put("kid", recipient.getKeyID());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("header", unprotected);
            entry.put("encrypted_key", Base64URL.encode(wrap(contentKey, recipient)).toString());
            recipientEntries.add(entry);
        }

        Map<String, Object> jwe = new LinkedHashMap<>();
        jwe.put("protected", protectedB64.toString());
        jwe.put("recipients", recipientEntries);
        jwe.put("iv", parts.getInitializationVector().toString());
        jwe.put("ciphertext", parts.getCipherText().toString());
        jwe.put("tag", parts.getAuthenticationTag().toString());
        return JSONObjectUtils.toJSONString(jwe);
    }

    private static byte[] wrap(SecretKey contentKey, RSAKey recipient) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, recipient.toRSAPublicKey(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return cipher.doFinal(contentKey.getEncoded());
        } catch (JOSEException badKey) {
            throw new GeneralSecurityException("Recipient key unusable");
        }
    }

    /**
     * Parses a transmitted value as a profile JWE without decrypting it. Refuses compact and
     * flattened forms and a top-level {@code aad}, which the profile doesn't use. The binding read
     * here is for finding the field only; {@link #open} checks it again after authentication.
     */
    static Parsed parse(String transmitted) throws FieldCipherException {
        Map<String, Object> json;
        try {
            json = JSONObjectUtils.parse(transmitted);
        } catch (ParseException notJson) {
            throw new FieldCipherException("not a JWE in general JSON serialization");
        }
        // JSONObjectUtils.parse answers null for the JSON null, without throwing.
        if (json == null) {
            throw new FieldCipherException("not a JWE in general JSON serialization");
        }
        // The binding must come from the authenticated protected header only, so a top-level
        // unprotected header, a flattened per-recipient header or extra AAD is refused.
        if (!json.containsKey("recipients") || json.containsKey("encrypted_key") || json.containsKey("aad")
            || json.containsKey("unprotected") || json.containsKey("header")) {
            throw new FieldCipherException("not a JWE in the profile's general JSON serialization");
        }
        JWEObjectJSON jwe;
        try {
            jwe = JWEObjectJSON.parse(json);
        } catch (ParseException | RuntimeException unparseable) {
            throw new FieldCipherException("unparseable JWE");
        }
        JWEHeader header = jwe.getHeader();
        if (header == null || header.getParsedBase64URL() == null) {
            throw new FieldCipherException("no protected header");
        }
        if (!PROTECTED_MEMBERS.containsAll(header.getIncludedParams())) {
            throw new FieldCipherException("protected header has members the profile doesn't define");
        }
        // A256GCM: a 96-bit IV and a 128-bit tag (RFC 7518 section 5.3). Checked here, so a JWE missing
        // either is refused rather than failing later on a null.
        if (jwe.getIV() == null || jwe.getIV().decode().length != 12) {
            throw new FieldCipherException("missing or wrong-length iv");
        }
        if (jwe.getAuthTag() == null || jwe.getAuthTag().decode().length != 16) {
            throw new FieldCipherException("missing or wrong-length tag");
        }
        return new Parsed(jwe, new Binding(
            stringParam(header, "tsa_ver"), stringParam(header, "tsa_jti"), stringParam(header, "tsa_fld")));
    }

    private static String stringParam(JWEHeader header, String name) {
        Object value = header.getCustomParam(name);
        return value instanceof String s ? s : null;
    }

    /** Unwraps this member's content key and decrypts; the GCM tag authenticates the protected header. */
    static String open(Parsed parsed, KeyOperations keys) throws FieldCipherException {
        JWEObjectJSON jwe = parsed.jwe();
        if (!EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
            throw new FieldCipherException("not A256GCM");
        }
        // The first entry for a key this member holds: the current one, or an earlier version for a
        // record encrypted before a rotation.
        JWEObjectJSON.Recipient mine = null;
        for (JWEObjectJSON.Recipient recipient : jwe.getRecipients()) {
            if (recipient.getUnprotectedHeader() != null && recipient.getUnprotectedHeader().getKeyID() != null
                && keys.holdsEncryptionKey(recipient.getUnprotectedHeader().getKeyID())) {
                mine = recipient;
                break;
            }
        }
        if (mine == null) {
            throw new FieldCipherException("no recipient entry for this member's encryption key");
        }
        if (mine.getEncryptedKey() == null) {
            throw new FieldCipherException("recipient entry has no encrypted_key");
        }
        Object alg = mine.getUnprotectedHeader().getParam("alg");
        if (!KEY_WRAP_ALG.equals(alg instanceof String s ? s : String.valueOf(alg))) {
            throw new FieldCipherException("recipient key wrap is not RSA-OAEP-256");
        }
        byte[] contentKey;
        try {
            contentKey = keys.unwrap(mine.getUnprotectedHeader().getKeyID(), mine.getEncryptedKey().decode());
        } catch (GeneralSecurityException unwrapFailed) {
            throw new FieldCipherException("content key unwrap failed");
        }
        if (contentKey == null || contentKey.length != CONTENT_KEY_BYTES) {
            throw new FieldCipherException("content key is not 256 bits");
        }
        try {
            byte[] plaintext = ContentCryptoProvider.decrypt(jwe.getHeader(), jwe.getAAD(), null, jwe.getIV(),
                jwe.getCipherText(), jwe.getAuthTag(), new SecretKeySpec(contentKey, "AES"), JCA);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (JOSEException tagFailed) {
            throw new FieldCipherException("decryption failed");
        }
    }
}
