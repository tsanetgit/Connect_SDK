package com.tsanet.e2ee;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The signed manifest of Addendum B: a compact JWS, ES256, {@code typ} {@code tsanet-manifest+jws},
 * whose payload binds the record ({@code iss}, {@code aud}, {@code ref}, {@code jti}) and lists one
 * S256 digest per protected field, taken over the field's transmitted JWE string.
 */
final class Manifest {
    private Manifest() {
    }

    record FieldDigest(String name, String dig) {
    }

    record Claims(String ver, String iss, String aud, String ref, long iat, String jti, List<FieldDigest> fields) {
    }

    static final class ManifestException extends Exception {
        ManifestException(String reason) {
            super(reason);
        }
    }

    /** base64url(SHA-256(value as UTF-8)), the digest each manifest entry carries. */
    static String digest(String transmittedValue) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(transmittedValue.getBytes(StandardCharsets.UTF_8));
            return Base64URL.encode(hash).toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    /** Constant-time comparison of two digests. */
    static boolean sameDigest(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    /** Builds and signs a manifest through the member's own {@link KeyOperations#sign}. */
    static String sign(Claims claims, KeyOperations keys) throws GeneralSecurityException {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", JWSAlgorithm.ES256.getName());
        header.put("kid", keys.signingKid());
        header.put("typ", Profile.MANIFEST_TYPE);

        List<Object> fields = new ArrayList<>();
        for (FieldDigest field : claims.fields()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", field.name());
            entry.put("alg", Profile.DIGEST_ALG);
            entry.put("dig", field.dig());
            fields.add(entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ver", claims.ver());
        payload.put("iss", claims.iss());
        payload.put("aud", claims.aud());
        payload.put("ref", claims.ref());
        payload.put("iat", claims.iat());
        payload.put("jti", claims.jti());
        payload.put("fields", fields);

        String signingInput = b64(JSONObjectUtils.toJSONString(header)) + "." + b64(JSONObjectUtils.toJSONString(payload));
        byte[] signature = keys.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
        if (signature == null || signature.length != 64) {
            throw new GeneralSecurityException("An ES256 signature must be the raw 64-byte r || s");
        }
        return signingInput + "." + Base64URL.encode(signature);
    }

    private static String b64(String json) {
        return Base64URL.encode(json.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Parses a compact JWS without trusting it: the header names the key to look up, nothing more. */
    static JWSObject parse(String manifest) throws ManifestException {
        if (manifest == null) {
            throw new ManifestException("no manifest");
        }
        try {
            return JWSObject.parse(manifest);
        } catch (ParseException unparseable) {
            throw new ManifestException("unparseable manifest");
        }
    }

    /** Verifies the signature and header with the sending company's key, which never comes from the manifest. */
    static void verify(JWSObject jws, ECKey senderKey) throws ManifestException {
        if (!JWSAlgorithm.ES256.equals(jws.getHeader().getAlgorithm())) {
            throw new ManifestException("not ES256");
        }
        if (!new JOSEObjectType(Profile.MANIFEST_TYPE).equals(jws.getHeader().getType())) {
            throw new ManifestException("wrong typ");
        }
        if (jws.getHeader().getCriticalParams() != null && !jws.getHeader().getCriticalParams().isEmpty()) {
            throw new ManifestException("unsupported crit");
        }
        try {
            if (!jws.verify(new ECDSAVerifier(senderKey.toPublicJWK()))) {
                throw new ManifestException("bad signature");
            }
        } catch (JOSEException unusable) {
            throw new ManifestException("bad signature");
        }
    }

    /** Reads the payload claims, after {@link #verify} has passed. */
    static Claims claims(JWSObject jws) throws ManifestException {
        try {
            Map<String, Object> payload = jws.getPayload().toJSONObject();
            if (payload == null) {
                throw new ManifestException("payload is not JSON");
            }
            List<FieldDigest> fields = new ArrayList<>();
            List<Object> entries = JSONObjectUtils.getJSONArray(payload, "fields");
            if (entries == null) {
                throw new ManifestException("no fields");
            }
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)
                    || !Profile.DIGEST_ALG.equals(map.get("alg"))
                    || !(map.get("name") instanceof String name)
                    || !(map.get("dig") instanceof String dig)) {
                    throw new ManifestException("bad fields entry");
                }
                fields.add(new FieldDigest(name, dig));
            }
            if (!(payload.get("iat") instanceof Number iat)) {
                throw new ManifestException("no iat");
            }
            return new Claims(
                JSONObjectUtils.getString(payload, "ver"),
                JSONObjectUtils.getString(payload, "iss"),
                JSONObjectUtils.getString(payload, "aud"),
                JSONObjectUtils.getString(payload, "ref"),
                iat.longValue(),
                JSONObjectUtils.getString(payload, "jti"),
                List.copyOf(fields));
        } catch (ParseException | ClassCastException malformed) {
            throw new ManifestException("malformed claims");
        }
    }
}
