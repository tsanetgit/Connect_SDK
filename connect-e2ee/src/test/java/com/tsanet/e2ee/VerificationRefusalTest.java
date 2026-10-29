package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Verification.Refused;
import com.tsanet.e2ee.Verification.Step;
import com.tsanet.e2ee.Verification.Verified;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        Map<String, Object> payload = v.map("example1_case_create", "manifest_payload");
        List<Manifest.FieldDigest> digests = new ArrayList<>();
        valuesByName.forEach((name, value) -> digests.add(new Manifest.FieldDigest(name, Manifest.digest(value))));
        return Manifest.sign(new Manifest.Claims(ver, (String) payload.get("iss"), (String) payload.get("aud"),
            (String) payload.get("ref"), iat, (String) payload.get("jti"), digests), signer);
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

    // ---- tsanetgit/Connect-API-Code#214 ----

    @Test
    void aDescriptionTheApiSanitizedStillVerifies() {
        String sanitized = original.description().replace("\"", "&#34;");

        assertThat(memberB.verifyCase(withContent(original.summary(), sanitized, original.manifest()),
            Receipt.STORED_READ)).isInstanceOf(Verified.class);
    }
}
