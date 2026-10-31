package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Verification.KeysUnavailable;
import com.tsanet.e2ee.Verification.Refused;
import com.tsanet.e2ee.Verification.Step;
import com.tsanet.e2ee.Verification.Verified;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every step of Addendum D §5 refuses at least one record, naming that step, and no refusal carries
 * a value. A tamper that has to get past the earlier steps (a moved ciphertext, a flipped bit under a
 * matching digest) re-signs the manifest with Member A's throwaway key, as a sender that went wrong would.
 */
class VerificationRefusalTest {
    private final Vectors v = Vectors.load();
    private long iat;
    private E2eeProfile memberB;
    private CollaborationRequestStatusDto original;

    @BeforeEach
    void setUp() throws Exception {
        iat = v.iat("example1_case_create");
        memberB = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(iat));
        original = v.caseRecord();
    }

    // ---- helpers ----

    private Refused<?> refusal(Verification<?> result) {
        assertThat(result).as("%s", result).isInstanceOf(Refused.class);
        Refused<?> refused = (Refused<?>) result;
        assertNoValueIn(refused.toString());
        return refused;
    }

    /** No refusal may carry a plaintext, a ciphertext, the manifest or a key. */
    private void assertNoValueIn(String text) {
        Map<String, Object> plain = v.map("example1_case_create", "plaintext_request");
        List<String> secrets = new ArrayList<>(List.of(
            (String) plain.get("problemSummary"), (String) plain.get("problemDescription"),
            original.summary().substring(0, 60), original.manifest().substring(0, 60),
            (String) v.map("keys", "memberB_enc").get("d")));
        for (String secret : secrets) {
            assertThat(text).doesNotContain(secret);
        }
    }

    private CollaborationRequestStatusDto with(String summary, String description, List<CaseCustomFieldDto> fields,
                                               String manifest, Boolean encrypted, Long submitter, Long receiver,
                                               String caseNumber) {
        return new CollaborationRequestStatusDto(original.id(), original.status(), summary, original.submitCompanyName(),
            submitter, original.receiveCompanyName(), receiver, original.token(), null, null, false, manifest, encrypted,
            caseNumber, description, fields);
    }

    private CollaborationRequestStatusDto withContent(String summary, String description, String manifest) {
        return with(summary, description, original.customFields(), manifest, true, 1042L, 2087L,
            original.submitterCaseNumber());
    }

    /** Re-signs a manifest for these values with A's key: same iss, aud, ref, iat and jti as the example. */
    private String resign(Map<String, String> valuesByName, String ver, KeyOperations signer) throws Exception {
        return resign(valuesByName, ver, signer, iat);
    }

    private String resign(Map<String, String> valuesByName, String ver, KeyOperations signer, long signedIat) throws Exception {
        Map<String, Object> payload = v.map("example1_case_create", "manifest_payload");
        List<Manifest.FieldDigest> digests = new ArrayList<>();
        valuesByName.forEach((name, value) -> digests.add(new Manifest.FieldDigest(name, Manifest.digest(value))));
        return Manifest.sign(new Manifest.Claims(ver, (String) payload.get("iss"), (String) payload.get("aud"),
            (String) payload.get("ref"), signedIat, (String) payload.get("jti"), digests), signer);
    }

    private Map<String, String> originalValues() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(Profile.PROBLEM_SUMMARY, original.summary());
        values.put(Profile.PROBLEM_DESCRIPTION, original.description());
        for (int i = 0; i < 4; i++) {
            values.put(Profile.customField(101 + i), original.customFields().get(i).value());
        }
        return values;
    }

    private static String flipOneCiphertextBit(String jwe) {
        int at = jwe.indexOf("\"ciphertext\":\"") + "\"ciphertext\":\"".length();
        char c = jwe.charAt(at);
        return jwe.substring(0, at) + (c == 'A' ? 'B' : 'A') + jwe.substring(at + 1);
    }

    // ---- malformed input: a Refused, never an exception ----

    private static String jsonB64(String json) {
        return com.nimbusds.jose.util.Base64URL.encode(json.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** A manifest with this header and payload, really signed by Member A, so it can get past step 2. */
    private String signedManifest(String headerJson, String payloadJson) throws Exception {
        String input = jsonB64(headerJson) + "." + jsonB64(payloadJson);
        byte[] signature = v.memberAKeys().sign(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + com.nimbusds.jose.util.Base64URL.encode(signature);
    }

    private static final List<String> JSON_SHAPES = List.of("null", "true", "1", "\"x\"", "[]", "[null]", "[1]", "{}", "{\"a\":null}");

    private List<String> malformedManifests() throws Exception {
        String kid = v.memberAKeys().signingKid();
        String header = "{\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"typ\":\"" + Profile.MANIFEST_TYPE + "\"}";
        List<String> manifests = new ArrayList<>(List.of("", " ", ".", "..", "...", "a.b", "a.b.c", "null", "bnVsbA.e30.AAAA"));
        for (String shape : JSON_SHAPES) {
            manifests.add(jsonB64(shape) + ".e30.AAAA");
            manifests.add(signedManifest(header, shape));
            manifests.add(signedManifest(header, "{\"fields\":" + shape + ",\"iat\":1}"));
            manifests.add(signedManifest(header, "{\"fields\":[],\"iat\":" + shape + "}"));
            manifests.add(signedManifest(header, "{\"fields\":[" + shape + "],\"iat\":1}"));
            for (String member : List.of("ver", "iss", "aud", "ref", "jti")) {
                manifests.add(signedManifest(header, "{\"fields\":[],\"iat\":1,\"" + member + "\":" + shape + "}"));
            }
            for (String member : List.of("alg", "kid", "typ", "crit", "b64", "jwk", "x5c", "cty")) {
                manifests.add(jsonB64("{\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"" + member + "\":" + shape + "}") + ".e30.AAAA");
            }
        }
        return manifests;
    }

    private List<String> malformedFieldValues() {
        List<String> values = new ArrayList<>(JSON_SHAPES);
        values.addAll(List.of("", " ", "not json", "a.b.c.d.e"));
        String valid = original.summary();
        Map<String, Object> jwe;
        try {
            jwe = com.nimbusds.jose.util.JSONObjectUtils.parse(valid);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
        for (String shape : JSON_SHAPES) {
            for (String member : List.of("protected", "recipients", "iv", "ciphertext", "tag")) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(jwe);
                copy.remove(member);
                String body = com.nimbusds.jose.util.JSONObjectUtils.toJSONString(copy);
                values.add(body.substring(0, body.length() - 1) + ",\"" + member + "\":" + shape + "}");
            }
            for (String member : List.of("encrypted_key", "header")) {
                values.add(valid.replaceFirst("\"" + member + "\":", "\"_was\":").replaceFirst("\"_was\":", "\"" + member + "\":" + shape + ",\"_was\":"));
            }
            values.add(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(jwe).replaceFirst("\"protected\":\"[^\"]*\"", "\"protected\":\"" + jsonB64(shape) + "\""));
        }
        return values;
    }

    /** The whole class at once: whatever a sender puts in the text, the caller gets one of the three outcomes. */
    @Test
    void malformedTextOnARecordIsARefusalNeverAnException() throws Exception {
        List<String> escapes = new ArrayList<>();
        CaseNoteDto note = v.noteRecord();
        for (String manifest : malformedManifests()) {
            refusedOrRecord(escapes, "case manifest " + manifest,
                () -> memberB.verifyCase(withContent(original.summary(), original.description(), manifest), Receipt.STORED_READ));
            refusedOrRecord(escapes, "first-receipt manifest " + manifest,
                () -> memberB.verifyCase(withContent(original.summary(), original.description(), manifest), Receipt.FIRST_RECEIPT));
            refusedOrRecord(escapes, "note manifest " + manifest,
                () -> memberB.verifyNote(Vectors.noteRecord(note.summary(), note.description(), manifest, 1042L), original, Receipt.STORED_READ));
        }
        for (String value : malformedFieldValues()) {
            Map<String, String> values = originalValues();
            values.put(Profile.PROBLEM_SUMMARY, value);
            String manifest = resign(values, Profile.VERSION, v.memberAKeys());
            refusedOrRecord(escapes, "signed summary " + value,
                () -> memberB.verifyCase(withContent(value, original.description(), manifest), Receipt.STORED_READ));

            List<CaseCustomFieldDto> fields = new ArrayList<>(original.customFields());
            CaseCustomFieldDto first = fields.get(0);
            fields.set(0, new CaseCustomFieldDto(first.id(), first.fieldName(), value));
            refusedOrRecord(escapes, "custom field " + value, () -> memberB.verifyCase(
                with(original.summary(), original.description(), fields, original.manifest(), true, 1042L, 2087L,
                    original.submitterCaseNumber()), Receipt.STORED_READ));
        }
        assertThat(escapes).isEmpty();
    }

    private interface Verify {
        Verification<?> run() throws Exception;
    }

    private void refusedOrRecord(List<String> escapes, String input, Verify verify) {
        try {
            Verification<?> result = verify.run();
            if (!(result instanceof Refused<?> refused)) {
                escapes.add(result.getClass().getSimpleName() + " on " + input);
            } else {
                assertNoValueIn(refused.toString());
            }
        } catch (RuntimeException | Error thrown) {
            if (thrown instanceof AssertionError) {
                throw (AssertionError) thrown;
            }
            escapes.add(thrown.getClass().getSimpleName() + " on " + input);
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    @Test
    void aManifestWhoseHeaderIsTheJsonNullIsRefusedAtSignature() {
        CaseNoteDto note = v.noteRecord();
        String manifest = "bnVsbA.e30.AAAA";

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), manifest),
            Receipt.STORED_READ)).step()).isEqualTo(Step.SIGNATURE);
        assertThat(refusal(memberB.verifyNote(Vectors.noteRecord(note.summary(), note.description(), manifest, 1042L),
            original, Receipt.STORED_READ)).step()).isEqualTo(Step.SIGNATURE);
    }

    @Test
    void aCustomFieldThatIsTheJsonNullIsRefusedAtFields() {
        List<CaseCustomFieldDto> fields = new ArrayList<>(original.customFields());
        CaseCustomFieldDto first = fields.get(0);
        fields.set(0, new CaseCustomFieldDto(first.id(), first.fieldName(), "null"));

        assertThat(refusal(memberB.verifyCase(with(original.summary(), original.description(), fields, original.manifest(),
            true, 1042L, 2087L, original.submitterCaseNumber()), Receipt.STORED_READ)).step()).isEqualTo(Step.FIELDS);
    }

    @Test
    void aSignedProtectedValueThatIsTheJsonNullIsRefusedAtFieldsOnACaseAndANote() throws Exception {
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, "null");
        Refused<?> onCase = refusal(memberB.verifyCase(
            withContent("null", original.description(), resign(values, Profile.VERSION, v.memberAKeys())), Receipt.STORED_READ));
        assertThat(onCase.step()).isEqualTo(Step.FIELDS);
        assertThat(onCase.field()).isEqualTo(Profile.PROBLEM_SUMMARY);

        CaseNoteDto note = v.noteRecord();
        Map<String, Object> payload = v.map("example2_note_create", "manifest_payload");
        List<Manifest.FieldDigest> digests = List.of(
            new Manifest.FieldDigest(Profile.PROBLEM_SUMMARY, Manifest.digest("null")),
            new Manifest.FieldDigest(Profile.PROBLEM_DESCRIPTION, Manifest.digest(note.description())));
        String signed = Manifest.sign(new Manifest.Claims(Profile.VERSION, (String) payload.get("iss"), (String) payload.get("aud"),
            (String) payload.get("ref"), iat, (String) payload.get("jti"), digests), v.memberAKeys());

        Refused<?> onNote = refusal(memberB.verifyNote(
            Vectors.noteRecord("null", note.description(), signed, 1042L), original, Receipt.STORED_READ));
        assertThat(onNote.step()).isEqualTo(Step.FIELDS);
        assertThat(onNote.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
    }

    /** A raw socket, since the JDK's own server won't send these: its client refuses them with an unchecked exception. */
    @Test
    void aMalformedHttpResponseFromTheJwksEndpointIsUnavailableKeysAndStartsTheWait() throws Exception {
        for (String length : List.of("abc", "99999999999999999999")) {
            java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
                Thread.ofVirtual().start(() -> {
                    while (!socket.isClosed()) {
                        try (java.net.Socket client = socket.accept()) {
                            requests.incrementAndGet();
                            client.getInputStream().read(new byte[4096]);
                            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + length + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                            client.getOutputStream().flush();
                        } catch (IOException closed) {
                            return;
                        }
                    }
                });
                E2eeProfile receiver = memberBWith(new PinnedJwksPartnerKeys(
                    Map.of(Vectors.MEMBER_A, java.net.URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/keys.json")),
                    java.time.Duration.ofMinutes(5)));

                assertThat(receiver.verifyCase(original, Receipt.STORED_READ)).as(length).isInstanceOf(KeysUnavailable.class);
                assertThat(receiver.verifyCase(original, Receipt.STORED_READ)).as(length).isInstanceOf(KeysUnavailable.class);
                assertThat(requests).as("the second read waited instead of fetching: " + length).hasValue(1);
            }
        }
    }

    // ---- step 1 ----

    @Test
    void aKidTheSenderDoesNotPublishIsRefusedAtSenderKey() throws Exception {
        ECKey stranger = new ECKeyGenerator(Curve.P_256).keyID("not-in-member-a-jwks").generate();
        String manifest = resign(originalValues(), Profile.VERSION, new SoftwareKeyOperations(v.rsa("memberA_enc"), stranger));

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), manifest),
            Receipt.STORED_READ)).step()).isEqualTo(Step.SENDER_KEY);
    }

    @Test
    void aRecordWithNoSendingCompanyIsRefusedAtSenderKey() {
        CollaborationRequestStatusDto noSender = with(original.summary(), original.description(), original.customFields(),
            original.manifest(), true, null, 2087L, original.submitterCaseNumber());

        assertThat(refusal(memberB.verifyCase(noSender, Receipt.STORED_READ)).step()).isEqualTo(Step.SENDER_KEY);
    }

    private E2eeProfile memberBWith(PartnerKeys keys) throws Exception {
        return new E2eeProfile(Vectors.MEMBER_B, v.memberBKeys(), keys,
            new InMemoryReplayGuard(java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(2), Vectors.clockAt(iat)),
            Vectors.clockAt(iat));
    }

    @Test
    void aPartnerServingTheJsonNullIsUnavailableKeysAndStartsTheWait() throws Exception {
        java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys.json", exchange -> {
            fetches.incrementAndGet();
            byte[] body = "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            E2eeProfile receiver = memberBWith(new PinnedJwksPartnerKeys(
                java.util.Map.of(Vectors.MEMBER_A,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/keys.json")),
                java.time.Duration.ofMinutes(5)));

            assertThat(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).isInstanceOf(KeysUnavailable.class);
            assertThat(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).isInstanceOf(KeysUnavailable.class);
            assertThat(fetches).as("the second read waited instead of fetching").hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailableKeysUseNoJtiSoTheSameFirstReceiptVerifiesOnceTheyAreBack() throws Exception {
        AtomicBoolean reachable = new AtomicBoolean(false);
        E2eeProfile receiver = memberBWith(company -> {
            if (!reachable.get()) {
                throw new IOException("key server down");
            }
            return v.partnerKeys().publicKeys(company);
        });

        assertThat(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).isInstanceOf(KeysUnavailable.class);
        reachable.set(true);
        assertThat(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).isInstanceOf(Verified.class);
    }

    @Test
    void aFailedRefetchForAnUnknownKidIsUnavailableNotRefused() throws Exception {
        JWKSet beforeRotation = new JWKSet(v.rsa("memberA_enc").toPublicJWK());
        E2eeProfile receiver = memberBWith(new PartnerKeys() {
            public JWKSet publicKeys(String company) {
                return beforeRotation;
            }
            public JWKSet refreshedPublicKeys(String company) throws IOException {
                throw new IOException("key server down");
            }
        });

        assertThat(receiver.verifyCase(original, Receipt.STORED_READ)).isInstanceOf(KeysUnavailable.class);
    }

    @Test
    void aNoteWhoseSenderKeysAreUnavailableIsUnavailable() throws Exception {
        E2eeProfile receiver = memberBWith(company -> {
            throw new IOException("key server down");
        });

        assertThat(receiver.verifyNote(v.noteRecord(), original, Receipt.STORED_READ)).isInstanceOf(KeysUnavailable.class);
    }

    @Test
    void aCaseBetweenOtherCompaniesIsRefusedEvenWhenItsSenderHasNoKeys() throws Exception {
        E2eeProfile receiver = memberBWith(company -> {
            throw new IOException("no JWKS for company " + company);
        });
        CollaborationRequestStatusDto between = with(original.summary(), original.description(), original.customFields(),
            original.manifest(), true, 4000L, 3000L, original.submitterCaseNumber());

        assertThat(refusal(receiver.verifyCase(between, Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    // ---- step 2 ----

    @Test
    void aBrokenSignatureIsRefusedAtSignature() {
        String manifest = original.manifest();
        int last = manifest.length() - 5;
        String broken = manifest.substring(0, last) + (manifest.charAt(last) == 'A' ? 'B' : 'A') + manifest.substring(last + 1);

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), broken),
            Receipt.STORED_READ)).step()).isEqualTo(Step.SIGNATURE);
    }

    @Test
    void aMissingManifestIsRefusedAtSignature() {
        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), null),
            Receipt.STORED_READ)).step()).isEqualTo(Step.SIGNATURE);
    }

    @Test
    void aValidlySignedJwsOfTheWrongTypeIsRefusedAtSignature() throws Exception {
        String[] parts = original.manifest().split("\\.");
        String header = "{\"alg\":\"ES256\",\"kid\":\"" + v.ec("memberA_sig").getKeyID() + "\",\"typ\":\"JWT\"}";
        String signingInput = Base64URL.encode(header.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
        byte[] signature = v.memberAKeys().sign(signingInput.getBytes(StandardCharsets.US_ASCII));
        String wrongType = signingInput + "." + Base64URL.encode(signature);

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), wrongType),
            Receipt.STORED_READ)).step()).isEqualTo(Step.SIGNATURE);
    }

    // ---- step 3 ----

    @Test
    void aRecordNotMarkedEncryptedIsRefusedAtClaims() {
        CollaborationRequestStatusDto plainFlag = with(original.summary(), original.description(), original.customFields(),
            original.manifest(), false, 1042L, 2087L, original.submitterCaseNumber());

        assertThat(refusal(memberB.verifyCase(plainFlag, Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    @Test
    void anUnsupportedVersionIsRefusedAtClaims() throws Exception {
        String manifest = resign(originalValues(), "tsanet-e2ee-2", v.memberAKeys());

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), manifest),
            Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    @Test
    void aManifestBoundToAnotherReceiverIsRefusedAtClaims() {
        CollaborationRequestStatusDto otherReceiver = with(original.summary(), original.description(),
            original.customFields(), original.manifest(), true, 1042L, 3000L, original.submitterCaseNumber());

        assertThat(refusal(memberB.verifyCase(otherReceiver, Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    @Test
    void aManifestBoundToAnotherCaseNumberIsRefusedAtClaims() {
        CollaborationRequestStatusDto otherCase = with(original.summary(), original.description(),
            original.customFields(), original.manifest(), true, 1042L, 2087L, "A-2026-999999");

        assertThat(refusal(memberB.verifyCase(otherCase, Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    @Test
    void aMemberThatIsNeitherPartyIsRefusedAtClaims() throws Exception {
        E2eeProfile outsider = v.profile("3000", v.memberBKeys(), Vectors.clockAt(iat));

        assertThat(refusal(outsider.verifyCase(original, Receipt.STORED_READ)).step()).isEqualTo(Step.CLAIMS);
    }

    // ---- step 4 ----

    @Test
    void aSecondFirstReceiptOfTheSameJtiIsRefusedAsAReplay() {
        assertThat(memberB.verifyCase(original, Receipt.FIRST_RECEIPT)).isInstanceOf(Verified.class);

        Refused<?> replay = refusal(memberB.verifyCase(original, Receipt.FIRST_RECEIPT));
        assertThat(replay.step()).isEqualTo(Step.FIRST_RECEIPT);
        assertThat(memberB.verifyCase(original, Receipt.STORED_READ)).isInstanceOf(Verified.class);
    }

    @Test
    void anIatOutsideTheWindowIsRefusedOnFirstReceiptOnly() throws Exception {
        E2eeProfile late = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(iat + 3600));
        E2eeProfile early = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(iat - 3600));

        assertThat(refusal(late.verifyCase(original, Receipt.FIRST_RECEIPT)).reason()).contains("older");
        assertThat(refusal(early.verifyCase(original, Receipt.FIRST_RECEIPT)).reason()).contains("later");
        assertThat(late.verifyCase(original, Receipt.STORED_READ)).isInstanceOf(Verified.class);
    }

    // ---- step 5 ----

    @Test
    void plaintextInAProtectedFieldIsRefusedAtFields() {
        Refused<?> refused = refusal(memberB.verifyCase(
            withContent("A readable summary", original.description(), original.manifest()), Receipt.STORED_READ));

        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
    }

    @Test
    void aFieldInTheManifestButNotOnTheRecordIsRefusedAtFields() {
        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(original.summary(), null, original.manifest()), Receipt.STORED_READ));

        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_DESCRIPTION);
    }

    @Test
    void aFieldOnTheRecordButNotInTheManifestIsRefusedAtFields() throws Exception {
        Map<String, String> values = originalValues();
        values.remove(Profile.PROBLEM_DESCRIPTION);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(original.summary(), original.description(), manifest), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_DESCRIPTION);
    }

    @Test
    void aFlippedCiphertextBitUnderTheOriginalManifestIsRefusedAtFields() {
        Refused<?> refused = refusal(memberB.verifyCase(withContent(flipOneCiphertextBit(original.summary()),
            original.description(), original.manifest()), Receipt.STORED_READ));

        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.reason()).contains("digest");
    }

    @Test
    void aCustomFieldValueThatIsNotAJweIsRefusedAtFields() {
        List<CaseCustomFieldDto> fields = new ArrayList<>(original.customFields());
        fields.set(0, new CaseCustomFieldDto(fields.get(0).id(), fields.get(0).fieldName(), "7.4.2"));
        CollaborationRequestStatusDto record = with(original.summary(), original.description(), fields,
            original.manifest(), true, 1042L, 2087L, original.submitterCaseNumber());

        assertThat(refusal(memberB.verifyCase(record, Receipt.STORED_READ)).step()).isEqualTo(Step.FIELDS);
    }

    // ---- step 6 ----

    @Test
    void aFlippedCiphertextBitUnderAMatchingDigestIsRefusedAtDecrypt() throws Exception {
        String tampered = flipOneCiphertextBit(original.summary());
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, tampered);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(tampered, original.description(), manifest), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.DECRYPT);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
    }

    @Test
    void aCiphertextMovedToAnotherFieldIsRefusedAtDecrypt() throws Exception {
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, original.description());
        values.put(Profile.PROBLEM_DESCRIPTION, original.summary());
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(original.description(), original.summary(), manifest), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.DECRYPT);
        assertThat(refused.reason()).contains("binding");
    }

    @Test
    void theWrongPrivateKeyIsRefusedAtDecrypt() throws Exception {
        RSAKey impostor = new RSAKeyGenerator(2048).keyID(v.rsa("memberB_enc").getKeyID()).generate();
        E2eeProfile wrongKey = v.profile(Vectors.MEMBER_B,
            new SoftwareKeyOperations(impostor, new ECKeyGenerator(Curve.P_256).keyID("x").generate()), Vectors.clockAt(iat));

        assertThat(refusal(wrongKey.verifyCase(original, Receipt.STORED_READ)).step()).isEqualTo(Step.DECRYPT);
    }

    @Test
    void aJweWithNoEntryForThisMemberIsRefusedAtDecrypt() throws Exception {
        String onlyForA = FieldCipher.encrypt("x", new FieldCipher.Binding(Profile.VERSION,
            (String) v.map("example1_case_create", "manifest_payload").get("jti"), Profile.PROBLEM_SUMMARY),
            List.of(v.rsa("memberA_enc").toPublicJWK()));
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, onlyForA);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(onlyForA, original.description(), manifest), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.DECRYPT);
        assertThat(refused.reason()).contains("recipient");
    }

    @Test
    void anOutOfRangeIatIsRefusedNotThrown() throws Exception {
        String manifest = resign(originalValues(), Profile.VERSION, v.memberAKeys(), Long.MAX_VALUE);

        assertThat(refusal(memberB.verifyCase(withContent(original.summary(), original.description(), manifest),
            Receipt.FIRST_RECEIPT)).step()).isEqualTo(Step.FIRST_RECEIPT);
    }

    @Test
    void aJweCarryingAnUnprotectedHeaderIsRefusedAtFields() throws Exception {
        // A parameter the protected header doesn't have: Nimbus itself already refuses a repeated one,
        // so only this module's own check refuses a header that wasn't authenticated.
        String withUnprotected = original.summary().replaceFirst("^\\{", "{\"unprotected\":{\"cty\":\"text/plain\"},");
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, withUnprotected);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(withUnprotected, original.description(), manifest), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
    }

    @Test
    void aJweWhoseProtectedHeaderAddsAMemberIsRefusedAtFields() throws Exception {
        String withZip = FieldCipher.encrypt("x", new FieldCipher.Binding(Profile.VERSION, "j", Profile.PROBLEM_SUMMARY),
            List.of(v.rsa("memberB_enc").toPublicJWK()));
        String header = new String(java.util.Base64.getUrlDecoder().decode(
            withZip.replaceAll("^\\{\"protected\":\"([^\"]+)\".*$", "$1")), StandardCharsets.UTF_8);
        String extra = Base64URL.encode(header.replace("{", "{\"zip\":\"DEF\",").getBytes(StandardCharsets.UTF_8)).toString();
        String tampered = withZip.replaceFirst("\"protected\":\"[^\"]+\"", "\"protected\":\"" + extra + "\"");
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, tampered);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        Refused<?> refused = refusal(memberB.verifyCase(withContent(tampered, original.description(), manifest),
            Receipt.STORED_READ));
        // Without the allowlist Nimbus would accept zip and fail later, at DECRYPT.
        assertThat(refused.step()).isEqualTo(Step.FIELDS);
    }

    @Test
    void aSignatureUnderANewlyRotatedKidIsAcceptedAfterOneRefetch() throws Exception {
        java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
        com.nimbusds.jose.jwk.JWKSet before = new com.nimbusds.jose.jwk.JWKSet(v.rsa("memberA_enc").toPublicJWK());
        PartnerKeys rotating = new PartnerKeys() {
            public com.nimbusds.jose.jwk.JWKSet publicKeys(String company) throws java.io.IOException {
                return company.equals(Vectors.MEMBER_A) ? before : v.partnerKeys().publicKeys(company);
            }
            public com.nimbusds.jose.jwk.JWKSet refreshedPublicKeys(String company) throws java.io.IOException {
                refreshes.incrementAndGet();
                return v.partnerKeys().publicKeys(company);
            }
        };
        E2eeProfile receiver = new E2eeProfile(Vectors.MEMBER_B, v.memberBKeys(), rotating,
            new InMemoryReplayGuard(java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(2), Vectors.clockAt(iat)),
            Vectors.clockAt(iat));

        assertThat(receiver.verifyCase(original, Receipt.STORED_READ)).isInstanceOf(Verified.class);
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void aFlattenedJweIsRefusedAtFields() throws Exception {
        String flattened = original.summary().replaceFirst("^\\{", "{\"header\":{\"kid\":\"x\"},");
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, flattened);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        assertThat(refusal(memberB.verifyCase(withContent(flattened, original.description(), manifest),
            Receipt.STORED_READ)).step()).isEqualTo(Step.FIELDS);
    }

    @Test
    void aJweWithNoProtectedHeaderIsRefusedNotThrown() throws Exception {
        String noProtected = "{\"recipients\":[],\"iv\":\"\",\"ciphertext\":\"\",\"tag\":\"\"}";
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, noProtected);
        String manifest = resign(values, Profile.VERSION, v.memberAKeys());

        assertThat(refusal(memberB.verifyCase(withContent(noProtected, original.description(), manifest),
            Receipt.STORED_READ)).step()).isEqualTo(Step.FIELDS);
    }

    @Test
    void aCustomFieldNamingAnInvalidFieldIsRefusedWithoutRepeatingTheName() throws Exception {
        String bogus = FieldCipher.encrypt("x", new FieldCipher.Binding(Profile.VERSION, "j", "evil\r\nname"),
            List.of(v.rsa("memberB_enc").toPublicJWK()));
        List<CaseCustomFieldDto> fields = new ArrayList<>(original.customFields());
        fields.set(0, new CaseCustomFieldDto(fields.get(0).id(), "x", bogus));
        CollaborationRequestStatusDto record = with(original.summary(), original.description(), fields,
            original.manifest(), true, 1042L, 2087L, original.submitterCaseNumber());

        Refused<?> refused = refusal(memberB.verifyCase(record, Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isNull();
        assertThat(refused.toString()).doesNotContain("evil");
    }

    @Test
    void aStoredRecordStaysReadableAfterTheReceiverRotatesItsEncryptionKey() throws Exception {
        RSAKey current = new RSAKeyGenerator(2048).keyID("member-b-enc-v2").generate();
        ECKey signing = new ECKeyGenerator(Curve.P_256).keyID("x").generate();
        E2eeProfile rotated = v.profile(Vectors.MEMBER_B,
            new SoftwareKeyOperations(current, List.of(v.rsa("memberB_enc")), signing), Vectors.clockAt(iat));
        E2eeProfile forgotten = v.profile(Vectors.MEMBER_B,
            new SoftwareKeyOperations(current, signing), Vectors.clockAt(iat));

        assertThat(rotated.verifyCase(original, Receipt.STORED_READ)).isInstanceOf(Verified.class);
        assertThat(refusal(forgotten.verifyCase(original, Receipt.STORED_READ)).step()).isEqualTo(Step.DECRYPT);
    }

    /** The summary JWE with one member changed, under a manifest re-signed for it, as B reads it. */
    @SuppressWarnings("unchecked")
    private Verification<?> summaryEditedAndResigned(java.util.function.Consumer<Map<String, Object>> edit, Receipt receipt,
                                                     E2eeProfile reader) throws Exception {
        Map<String, Object> jwe = com.nimbusds.jose.util.JSONObjectUtils.parse(original.summary());
        edit.accept(jwe);
        String edited = com.nimbusds.jose.util.JSONObjectUtils.toJSONString(jwe);
        Map<String, String> values = originalValues();
        values.put(Profile.PROBLEM_SUMMARY, edited);
        return reader.verifyCase(withContent(edited, original.description(), resign(values, Profile.VERSION, v.memberAKeys())),
            receipt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSignedJweMissingAPartIsRefusedNotThrown() throws Exception {
        for (java.util.function.Consumer<Map<String, Object>> edit : List.<java.util.function.Consumer<Map<String, Object>>>of(
            jwe -> jwe.remove("iv"),
            jwe -> jwe.remove("tag"),
            jwe -> jwe.put("iv", Base64URL.encode(new byte[16]).toString()),
            jwe -> jwe.put("tag", Base64URL.encode(new byte[12]).toString()))) {
            // Refused when the JWE is parsed, at FIELDS: without the length checks a wrong-length iv or tag
            // would still be refused, but later, at DECRYPT.
            Refused<?> refused = refusal(summaryEditedAndResigned(edit, Receipt.STORED_READ, memberB));
            assertThat(refused.step()).isEqualTo(Step.FIELDS);
            assertThat(refused.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
        }
        Refused<?> noKey = refusal(summaryEditedAndResigned(
            jwe -> ((Map<String, Object>) ((List<Object>) jwe.get("recipients")).get(0)).remove("encrypted_key"),
            Receipt.STORED_READ, memberB));
        assertThat(noKey.step()).isEqualTo(Step.DECRYPT);
        assertThat(noKey.field()).isEqualTo(Profile.PROBLEM_SUMMARY);
    }

    @Test
    void aFirstReceiptRefusedForAReceiverFaultIsRecoveredByAStoredRead() throws Exception {
        KeyOperations real = v.memberBKeys();
        boolean[] keyServiceDown = {true};
        KeyOperations flaky = new KeyOperations() {
            public String encryptionKid() { return real.encryptionKid(); }
            public String signingKid() { return real.signingKid(); }
            public byte[] unwrap(String kid, byte[] encryptedKey) throws java.security.GeneralSecurityException {
                if (keyServiceDown[0]) {
                    throw new java.security.GeneralSecurityException("key service unavailable");
                }
                return real.unwrap(kid, encryptedKey);
            }
            public byte[] sign(byte[] signingInput) throws java.security.GeneralSecurityException { return real.sign(signingInput); }
        };
        E2eeProfile receiver = v.profile(Vectors.MEMBER_B, flaky, Vectors.clockAt(iat));

        assertThat(refusal(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).step()).isEqualTo(Step.DECRYPT);
        keyServiceDown[0] = false;
        assertThat(refusal(receiver.verifyCase(original, Receipt.FIRST_RECEIPT)).step())
            .as("the redelivery is a replay").isEqualTo(Step.FIRST_RECEIPT);
        assertThat(receiver.verifyCase(original, Receipt.STORED_READ)).as("the documented recovery").isInstanceOf(Verified.class);
    }

    @Test
    void aNonCanonicalCustomFieldNameIsRefusedAtFields() throws Exception {
        String padded = FieldCipher.encrypt("x", new FieldCipher.Binding(Profile.VERSION,
            (String) v.map("example1_case_create", "manifest_payload").get("jti"), "customFields/0101/value"),
            List.of(v.rsa("memberB_enc").toPublicJWK()));
        List<CaseCustomFieldDto> fields = new ArrayList<>(original.customFields());
        fields.add(new CaseCustomFieldDto(9999L, "dup", padded));
        Map<String, String> values = originalValues();
        values.put("customFields/0101/value", padded);
        CollaborationRequestStatusDto record = with(original.summary(), original.description(), fields,
            resign(values, Profile.VERSION, v.memberAKeys()), true, 1042L, 2087L, original.submitterCaseNumber());

        assertThat(refusal(memberB.verifyCase(record, Receipt.STORED_READ)).step()).isEqualTo(Step.FIELDS);
    }

    @Test
    void aNoteCheckedAgainstAnotherCaseIsRefused() {
        CollaborationRequestStatusDto otherCase = new CollaborationRequestStatusDto(78L, "OPEN", original.summary(),
            "A", 1042L, "B", 2087L, "tok-78", null, null, false, original.manifest(), true,
            original.submitterCaseNumber(), original.description(), original.customFields());

        Refused<?> refused = refusal(memberB.verifyNote(v.noteRecord(), otherCase, Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.CLAIMS);
    }

    @Test
    void aNoteByACompanyThatIsNotAPartyIsRefused() {
        CaseNoteDto note = v.noteRecord();
        CaseNoteDto byOutsider = Vectors.noteRecord(note.summary(), note.description(), note.manifest(), 3000L);

        assertThat(memberB.verifyNote(byOutsider, original, Receipt.STORED_READ)).isInstanceOf(Refused.class);
    }

    // ---- tsanetgit/Connect-API-Code#214 ----

    @Test
    void anUndecodableCharacterReferenceIsRefusedNotThrown() {
        String hostile = "{&#34;protected&#34;:&#34;x&#x110000;&#-1;&#34;}";

        Refused<?> refused = refusal(memberB.verifyCase(
            withContent(original.summary(), hostile, original.manifest()), Receipt.STORED_READ));
        assertThat(refused.step()).isEqualTo(Step.FIELDS);
        assertThat(refused.field()).isEqualTo(Profile.PROBLEM_DESCRIPTION);
    }

    @Test
    void aDescriptionTheApiSanitizedStillVerifies() {
        String sanitized = original.description().replace("\"", "&#34;");

        assertThat(memberB.verifyCase(withContent(original.summary(), sanitized, original.manifest()),
            Receipt.STORED_READ)).isInstanceOf(Verified.class);
    }
}
