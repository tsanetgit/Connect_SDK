package com.tsanet.e2ee;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Addendum D worked example ("Connect E2E Encryption", profile tsanet-e2ee-1, regenerated
 * 2026-10-23): Member A (company 1042) creates a case for Member B (company 2087) and adds a note.
 * Its keys are throwaway test keys that exist only in that example; never use them anywhere else.
 */
final class Vectors {
    static final String MEMBER_A = "1042";
    static final String MEMBER_B = "2087";

    final Map<String, Object> json;

    private Vectors(Map<String, Object> json) {
        this.json = json;
    }

    static Vectors load() {
        try (InputStream in = Vectors.class.getResourceAsStream("/addendum-d-vectors-THROWAWAY-TEST-KEYS.json")) {
            return new Vectors(JSONObjectUtils.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | java.text.ParseException e) {
            throw new IllegalStateException("Addendum D vectors unreadable", e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String... path) {
        Object node = json;
        for (String key : path) {
            node = ((Map<String, Object>) node).get(key);
        }
        return (Map<String, Object>) node;
    }

    String string(String... path) {
        Map<String, Object> parent = map(java.util.Arrays.copyOf(path, path.length - 1));
        return (String) parent.get(path[path.length - 1]);
    }

    long iat(String example) {
        return ((Number) map(example, "manifest_payload").get("iat")).longValue();
    }

    RSAKey rsa(String name) {
        try {
            return RSAKey.parse(map("keys", name));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    ECKey ec(String name) {
        try {
            return ECKey.parse(map("keys", name));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Each member's published JWKS, by company id. */
    PartnerKeys partnerKeys() {
        try {
            JWKSet a = JWKSet.parse(map("jwks", "memberA"));
            JWKSet b = JWKSet.parse(map("jwks", "memberB"));
            return companyId -> switch (companyId) {
                case MEMBER_A -> a;
                case MEMBER_B -> b;
                default -> throw new IOException("no JWKS for company " + companyId);
            };
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Member B: its encryption key only; B signs nothing in the example. */
    KeyOperations memberBKeys() throws Exception {
        return new SoftwareKeyOperations(rsa("memberB_enc"), throwawaySigningKey());
    }

    KeyOperations memberAKeys() throws Exception {
        return new SoftwareKeyOperations(rsa("memberA_enc"), ec("memberA_sig"));
    }

    private static ECKey throwawaySigningKey() throws Exception {
        return new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID("unused").generate();
    }

    static Clock clockAt(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    E2eeProfile profile(String ownCompanyId, KeyOperations keys, Clock clock) {
        return new E2eeProfile(ownCompanyId, keys, partnerKeys(),
            new InMemoryReplayGuard(Duration.ofMinutes(10), Duration.ofMinutes(2), clock), clock);
    }

    /** The case as B reads it back: read names, read ids for custom fields, the manifest as stored. */
    CollaborationRequestStatusDto caseRecord() {
        return caseRecord(map("example1_case_create", "encrypted_request"));
    }

    @SuppressWarnings("unchecked")
    CollaborationRequestStatusDto caseRecord(Map<String, Object> request) {
        List<CaseCustomFieldDto> fields = new ArrayList<>();
        long readId = 9001;
        for (Object o : (List<Object>) request.get("customFields")) {
            Map<String, Object> field = (Map<String, Object>) o;
            fields.add(new CaseCustomFieldDto(readId++, (String) field.get("label"), (String) field.get("value")));
        }
        return new CollaborationRequestStatusDto(77L, "OPEN", (String) request.get("problemSummary"), "Member A", 1042L,
            "Member B", 2087L, "tok-77", null, null, false, (String) request.get("manifest"),
            (Boolean) request.get("encrypted"), (String) request.get("internalCaseNumber"),
            (String) request.get("problemDescription"), fields);
    }

    CaseNoteDto noteRecord() {
        Map<String, Object> note = map("example2_note_create", "encrypted_request");
        return noteRecord((String) note.get("summary"), (String) note.get("description"), (String) note.get("manifest"), 1042L);
    }

    static CaseNoteDto noteRecord(String summary, String description, String manifest, Long authorCompanyId) {
        return new CaseNoteDto(5L, 77L, "tok-77", "Member A", "u", "e", "n", summary, description, "LOW", "ACTIVE",
            "note-5", null, null, "partner", authorCompanyId, "INBOUND", "Partner", manifest, true);
    }
}
