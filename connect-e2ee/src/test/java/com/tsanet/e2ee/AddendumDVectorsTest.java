package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedCustomField;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Verification.Verified;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Addendum D example verifies and decrypts for both members, as a stored read and on first receipt. */
class AddendumDVectorsTest {
    private final Vectors v = Vectors.load();

    @Test
    void memberBVerifiesAndDecryptsTheCase() throws Exception {
        E2eeProfile b = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(v.iat("example1_case_create")));

        assertPlaintextCase(b.verifyCase(v.caseRecord(), Receipt.STORED_READ));
        assertPlaintextCase(b.verifyCase(v.caseRecord(), Receipt.FIRST_RECEIPT));
    }

    @Test
    void memberAReReadsItsOwnCaseWithItsOwnKey() throws Exception {
        E2eeProfile a = v.profile(Vectors.MEMBER_A, v.memberAKeys(), Vectors.clockAt(v.iat("example1_case_create")));

        assertPlaintextCase(a.verifyCase(v.caseRecord(), Receipt.STORED_READ));
    }

    @Test
    void memberBVerifiesAndDecryptsTheNote() throws Exception {
        E2eeProfile b = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(v.iat("example2_note_create")));

        Verification<DecryptedNote> result = b.verifyNote(v.noteRecord(), v.caseRecord(), Receipt.FIRST_RECEIPT);

        assertThat(result).isInstanceOf(Verified.class);
        DecryptedNote note = ((Verified<DecryptedNote>) result).content();
        assertThat(note.summary()).isEqualTo(v.string("example2_note_create", "plaintext_request", "summary"));
        assertThat(note.description()).isEqualTo(v.string("example2_note_create", "plaintext_request", "description"));
    }

    @SuppressWarnings("unchecked")
    private void assertPlaintextCase(Verification<DecryptedCase> result) {
        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        DecryptedCase decrypted = ((Verified<DecryptedCase>) result).content();
        Map<String, Object> plain = v.map("example1_case_create", "plaintext_request");
        assertThat(decrypted.summary()).isEqualTo(plain.get("problemSummary"));
        assertThat(decrypted.description()).isEqualTo(plain.get("problemDescription"));

        List<Object> plainFields = (List<Object>) plain.get("customFields");
        assertThat(decrypted.customFields()).hasSize(plainFields.size());
        for (int i = 0; i < plainFields.size(); i++) {
            Map<String, Object> expected = (Map<String, Object>) plainFields.get(i);
            DecryptedCustomField actual = decrypted.customFields().get(i);
            assertThat(actual.readId()).isEqualTo(9001L + i);
            assertThat(actual.value()).as("field %s", expected.get("fieldId")).isEqualTo(expected.get("value"));
            if (expected.get("value") != null) {
                assertThat(actual.fieldId()).isEqualTo(((Number) expected.get("fieldId")).longValue());
            }
        }
        // Field 104 is the empty-string vector and 105 the null one.
        assertThat(decrypted.customFields().get(3).value()).isEmpty();
        assertThat(decrypted.customFields().get(4).value()).isNull();
    }
}
