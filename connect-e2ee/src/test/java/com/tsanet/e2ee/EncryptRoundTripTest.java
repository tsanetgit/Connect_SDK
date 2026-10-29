package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWEObjectJSON;
import com.nimbusds.jose.JWSObject;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Records.CasePlaintext;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Records.EncryptedCase;
import com.tsanet.e2ee.Records.EncryptedNote;
import com.tsanet.e2ee.Records.NotePlaintext;
import com.tsanet.e2ee.Verification.Verified;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What Member A encrypts, Member B verifies and decrypts, and so does A on re-reading its own record. */
class EncryptRoundTripTest {
    private static final long NOW = 1_800_000_000L;
    private final Vectors v = Vectors.load();
    private E2eeProfile memberA;
    private E2eeProfile memberB;

    @BeforeEach
    void setUp() throws Exception {
        memberA = v.profile(Vectors.MEMBER_A, v.memberAKeys(), Vectors.clockAt(NOW));
        memberB = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(NOW));
    }

    private static Map<Long, String> answers() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(101L, "7.4.2");
        answers.put(104L, "");
        answers.put(105L, null);
        return answers;
    }

    /** The case as the API would return it to a reader: read names, read ids, values as sent. */
    private static CollaborationRequestStatusDto asRead(EncryptedCase sent) {
        List<CaseCustomFieldDto> fields = new ArrayList<>();
        long readId = 501;
        for (String value : sent.customFieldValues().values()) {
            fields.add(new CaseCustomFieldDto(readId++, "label", value));
        }
        return new CollaborationRequestStatusDto(1L, "OPEN", sent.summary(), "A", 1042L, "B", 2087L, "tok", null, null,
            false, sent.e2ee().manifest(), sent.e2ee().encrypted(), sent.caseNumber(), sent.description(), fields);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCaseAEncryptsVerifiesForBothMembers() throws Exception {
        EncryptedCase sent = memberA.encryptCase(
            new CasePlaintext("A-1", "Cluster down", "Both sites lost quorum", answers(), List.of()), Vectors.MEMBER_B);

        assertThat(sent.e2ee().encrypted()).isTrue();
        assertThat(sent.customFieldValues().get(105L)).isNull();
        assertThat(sent.customFieldValues().get(104L)).startsWith("{\"protected\"");
        for (Verification<DecryptedCase> result : List.of(
            memberB.verifyCase(asRead(sent), Receipt.FIRST_RECEIPT), memberA.verifyCase(asRead(sent), Receipt.STORED_READ))) {
            assertThat(result).as("%s", result).isInstanceOf(Verified.class);
            DecryptedCase decrypted = ((Verified<DecryptedCase>) result).content();
            assertThat(decrypted.summary()).isEqualTo("Cluster down");
            assertThat(decrypted.description()).isEqualTo("Both sites lost quorum");
            assertThat(decrypted.customFields()).extracting(f -> f.fieldId() + "=" + f.value())
                .containsExactly("101=7.4.2", "104=", "null=null");
        }
    }

    @Test
    void theWireShapeIsTheProfiles() throws Exception {
        EncryptedCase sent = memberA.encryptCase(
            new CasePlaintext("A-1", "s", "d", Map.of(), List.of()), Vectors.MEMBER_B);

        JWEObjectJSON jwe = JWEObjectJSON.parse(sent.summary());
        assertThat(jwe.getHeader().getAlgorithm()).isNull();
        assertThat(jwe.getHeader().getEncryptionMethod().getName()).isEqualTo("A256GCM");
        assertThat(jwe.getHeader().getCustomParams()).containsOnlyKeys("tsa_ver", "tsa_jti", "tsa_fld");
        assertThat(jwe.getRecipients()).extracting(r -> r.getUnprotectedHeader().getKeyID())
            .containsExactly(v.rsa("memberB_enc").getKeyID(), v.rsa("memberA_enc").getKeyID());
        JWSObject manifest = JWSObject.parse(sent.e2ee().manifest());
        assertThat(manifest.getHeader().getType().getType()).isEqualTo(Profile.MANIFEST_TYPE);
        assertThat(manifest.getPayload().toJSONObject())
            .containsEntry("iss", "1042").containsEntry("aud", "2087").containsEntry("ref", "A-1")
            .containsEntry("iat", NOW);
        assertThat(manifest.getSignature().decode()).hasSize(64);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNoteAEncryptsVerifiesForB() throws Exception {
        EncryptedCase sentCase = memberA.encryptCase(new CasePlaintext("A-1", "s", "d", Map.of(), List.of()), Vectors.MEMBER_B);
        CollaborationRequestStatusDto caseRecord = asRead(sentCase);

        EncryptedNote note = memberA.encryptNote(new NotePlaintext("Update", "Logs attached"), caseRecord);

        Verification<DecryptedNote> result = memberB.verifyNote(
            Vectors.noteRecord(note.summary(), note.description(), note.e2ee().manifest(), 1042L), caseRecord, Receipt.STORED_READ);
        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        assertThat(((Verified<DecryptedNote>) result).content().description()).isEqualTo("Logs attached");
    }

    @Test
    void aRequiredFormFieldWithNoAnswerIsRefusedBeforeAnythingIsEncrypted() {
        FormFieldDto required = new FormFieldDto(102L, null, "Topology", "TEXT", 1, true, null, null, null);

        assertThatThrownBy(() -> memberA.encryptCase(
            new CasePlaintext("A-1", "s", "d", Map.of(101L, "x"), List.of(required)), Vectors.MEMBER_B))
            .isInstanceOf(E2eeException.class).hasMessageContaining("customFields/102/value");
    }

    @Test
    void plaintextOverTheSpecsLimitsIsRefused() {
        assertThatThrownBy(() -> memberA.encryptCase(
            new CasePlaintext("A-1", "s".repeat(501), "d", Map.of(), List.of()), Vectors.MEMBER_B))
            .isInstanceOf(E2eeException.class).hasMessageContaining("problemSummary");
        assertThatThrownBy(() -> memberA.encryptCase(
            new CasePlaintext("A-1", "s", "d", Map.of(101L, "v".repeat(5001)), List.of()), Vectors.MEMBER_B))
            .isInstanceOf(E2eeException.class).hasMessageContaining("customFields/101/value");
    }

    @Test
    void aPartnerWithNoPublishedKeysIsRefusedWithoutAValueInTheMessage() {
        assertThatThrownBy(() -> memberA.encryptCase(
            new CasePlaintext("A-1", "secret summary", "d", Map.of(), List.of()), "9999"))
            .isInstanceOf(E2eeException.class).hasMessageContaining("9999").hasMessageNotContaining("secret summary");
    }

    @Test
    void resultsKeepContentOutOfToString() throws Exception {
        EncryptedCase sent = memberA.encryptCase(
            new CasePlaintext("A-1", "secret summary", "secret description", answers(), List.of()), Vectors.MEMBER_B);
        @SuppressWarnings("unchecked")
        DecryptedCase decrypted = ((Verified<DecryptedCase>) memberB.verifyCase(asRead(sent), Receipt.STORED_READ)).content();

        for (String text : List.of(sent.toString(), decrypted.toString(),
            new CasePlaintext("A-1", "secret summary", "d", answers(), List.of()).toString())) {
            assertThat(text).doesNotContain("secret").doesNotContain("7.4.2").doesNotContain("{\"protected\"");
        }
    }
}
