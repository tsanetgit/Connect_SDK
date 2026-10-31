package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.OpenApiRecords;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.generated.model.CaseCustomFieldStatusDTO;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseStatus;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Verification.Verified;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A connector on the generated client (tsanetgit/Connect_SDK#146): the Addendum D example arrives as
 * the generated models, becomes records through {@link OpenApiRecords}, and verifies, so the mapping
 * keeps every value a manifest digest covers.
 */
class GeneratedModelVerificationTest {
    private final Vectors v = Vectors.load();

    @SuppressWarnings("unchecked")
    private CollaborationRequestStatusDTO caseAsRead() {
        Map<String, Object> request = v.map("example1_case_create", "encrypted_request");
        CollaborationRequestStatusDTO dto = new CollaborationRequestStatusDTO();
        dto.setId(77L);
        dto.setStatus(CaseStatus.OPEN);
        dto.setSummary((String) request.get("problemSummary"));
        dto.setDescription((String) request.get("problemDescription"));
        dto.setSubmitCompanyId(1042L);
        dto.setReceiveCompanyId(2087L);
        dto.setToken("tok-77");
        dto.setManifest((String) request.get("manifest"));
        dto.setEncrypted((Boolean) request.get("encrypted"));
        dto.setSubmitterCaseNumber((String) request.get("internalCaseNumber"));
        long readId = 9001;
        for (Object o : (List<Object>) request.get("customFields")) {
            Map<String, Object> field = (Map<String, Object>) o;
            CaseCustomFieldStatusDTO answer = new CaseCustomFieldStatusDTO();
            answer.setId(readId++);
            answer.setFieldName((String) field.get("label"));
            answer.setValue((String) field.get("value"));
            dto.addCustomFieldsItem(answer);
        }
        return dto;
    }

    @Test
    void aCaseReadThroughTheGeneratedClientVerifiesOnFirstReceipt() throws Exception {
        E2eeProfile b = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(v.iat("example1_case_create")));

        Verification<DecryptedCase> result = b.verifyCase(OpenApiRecords.collaborationRequest(caseAsRead()),
            Receipt.FIRST_RECEIPT);

        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        assertThat(((Verified<DecryptedCase>) result).content().description())
            .isEqualTo(v.string("example1_case_create", "plaintext_request", "problemDescription"));
    }

    @Test
    void aNoteReadThroughTheGeneratedClientVerifiesOnFirstReceipt() throws Exception {
        Map<String, Object> request = v.map("example2_note_create", "encrypted_request");
        CaseNoteDTO note = new CaseNoteDTO();
        note.setId(5L);
        note.setCaseId(77L);
        note.setCompanyId(1042L);
        note.setSummary((String) request.get("summary"));
        note.setDescription((String) request.get("description"));
        note.setManifest((String) request.get("manifest"));
        note.setEncrypted((Boolean) request.get("encrypted"));
        CollaborationRequestStatusDto caseRecord = OpenApiRecords.collaborationRequest(caseAsRead());
        E2eeProfile b = v.profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.clockAt(v.iat("example2_note_create")));

        Verification<DecryptedNote> result = b.verifyNote(OpenApiRecords.caseNote(note, caseRecord.token()), caseRecord,
            Receipt.FIRST_RECEIPT);

        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        assertThat(((Verified<DecryptedNote>) result).content().description())
            .isEqualTo(v.string("example2_note_create", "plaintext_request", "description"));
    }
}
