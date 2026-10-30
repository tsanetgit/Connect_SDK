package com.tsanet.demo;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.demo.config.E2eeKeys;
import com.tsanet.demo.config.PartnerKeysStore;
import com.tsanet.e2ee.E2eeProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Addendum D worked example as the demo's tests use it: Member A (company 1042) creates a case
 * for Member B (company 2087) and adds a note, with the throwaway test keys the example was
 * generated with. The file is a copy of connect-e2ee's; {@code VectorsCopyTest} keeps them equal.
 * Never use these keys anywhere else.
 */
public final class E2eeTestVectors {
    public static final String MEMBER_A = "1042";
    public static final String MEMBER_B = "2087";
    public static final String RESOURCE = "/addendum-d-vectors-THROWAWAY-TEST-KEYS.json";

    private final Map<String, Object> json;

    private E2eeTestVectors(Map<String, Object> json) {
        this.json = json;
    }

    public static E2eeTestVectors load() {
        try (InputStream in = E2eeTestVectors.class.getResourceAsStream(RESOURCE)) {
            return new E2eeTestVectors(JSONObjectUtils.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | java.text.ParseException e) {
            throw new IllegalStateException("Addendum D vectors unreadable", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String... path) {
        Object node = json;
        for (String key : path) {
            node = ((Map<String, Object>) node).get(key);
        }
        return (Map<String, Object>) node;
    }

    public String string(String... path) {
        Map<String, Object> parent = map(java.util.Arrays.copyOf(path, path.length - 1));
        return (String) parent.get(path[path.length - 1]);
    }

    public RSAKey rsa(String name) {
        try {
            return RSAKey.parse(map("keys", name));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    public ECKey ec(String name) {
        try {
            return ECKey.parse(map("keys", name));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Member A's private set, in the form {@code TSANET_DEMO_E2EE_PRIVATE_JWKS} takes. */
    public String memberAPrivateJwks() {
        return new JWKSet(List.of(rsa("memberA_enc"), ec("memberA_sig"))).toString(false);
    }

    /** Member B's private set: its encryption key and a throwaway signing key (B signs nothing in the example). */
    public String memberBPrivateJwks() {
        try {
            ECKey signing = new ECKeyGenerator(Curve.P_256).keyID("memberB-sig-unused").generate();
            return new JWKSet(List.of(rsa("memberB_enc"), signing)).toString(false);
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Each member's published (public-only) JWKS as JSON text. */
    public String publicJwks(String member) {
        try {
            return JWKSet.parse(map("jwks", member)).toPublicJWKSet().toString(true);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A profile for {@code own} ("1042" or "2087") over a real {@link PartnerKeysStore} in
     * {@code dir} that holds the other member's public set as a pasted entry, the way the pilot's
     * keys reach the demo. The store is the same class the controllers use.
     */
    public E2eeProfile profileFor(String own, Path dir) {
        boolean a = MEMBER_A.equals(own);
        E2eeKeys keys = E2eeKeys.fromJson(a ? memberAPrivateJwks() : memberBPrivateJwks());
        PartnerKeysStore store = new PartnerKeysStore(dir.resolve("e2ee-partners-" + own + ".json"), null, "TEST");
        store.putJwks(a ? MEMBER_B : MEMBER_A, publicJwks(a ? "memberB" : "memberA"));
        return new E2eeProfile(own, keys.keyOperations(), store.view(own, keys.publicKeySet()), keys.replayGuard(), keys.clock());
    }

    /** The case as B reads it back: read names, read ids for custom fields, the manifest as stored. */
    public CollaborationRequestStatusDto caseRecord() {
        return caseRecord(map("example1_case_create", "encrypted_request"));
    }

    @SuppressWarnings("unchecked")
    public CollaborationRequestStatusDto caseRecord(Map<String, Object> request) {
        List<CaseCustomFieldDto> fields = new ArrayList<>();
        long readId = 9001;
        for (Object o : (List<Object>) request.get("customFields")) {
            Map<String, Object> field = (Map<String, Object>) o;
            fields.add(new CaseCustomFieldDto(readId++, (String) field.get("label"), (String) field.get("value")));
        }
        return caseRecord((String) request.get("problemSummary"), (String) request.get("problemDescription"),
            fields, (String) request.get("manifest"), (Boolean) request.get("encrypted"),
            (String) request.get("internalCaseNumber"));
    }

    public static CollaborationRequestStatusDto caseRecord(String summary, String description, List<CaseCustomFieldDto> fields,
                                                           String manifest, Boolean encrypted, String caseNumber) {
        return new CollaborationRequestStatusDto(77L, "OPEN", summary, "Member A", 1042L, "Member B", 2087L, "tok-77",
            "2026-10-23T10:00:00Z", "2026-10-23T11:00:00Z", false, manifest, encrypted, caseNumber, description, fields);
    }

    public CaseNoteDto noteRecord() {
        Map<String, Object> note = map("example2_note_create", "encrypted_request");
        return noteRecord((String) note.get("summary"), (String) note.get("description"), (String) note.get("manifest"), 1042L, true);
    }

    public static CaseNoteDto noteRecord(String summary, String description, String manifest, Long authorCompanyId, Boolean encrypted) {
        return new CaseNoteDto(5L, 77L, "tok-77", "Member A", "u", "e", "n", summary, description, "LOW", "ACTIVE",
            "note-5", "2026-10-23T11:00:00Z", null, "partner", authorCompanyId, "INBOUND", "Partner", manifest, encrypted);
    }
}
