package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.api.generated.model.CaseCustomFieldStatusDTO;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseStatus;
import com.tsanet.api.generated.model.CollaborationRequestDirection;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import com.tsanet.api.generated.model.FieldMetadataSection;
import com.tsanet.api.generated.model.FieldMetadataType;
import com.tsanet.api.generated.model.NotePriority;
import com.tsanet.api.generated.model.NoteStatus;
import com.tsanet.api.generated.model.NoteTypeSelectionDTO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every generated field gets a distinct value, so a field mapped to the wrong component (two
 * strings swapped) fails as surely as one left unmapped, and no component may be null, so a record
 * that gains a component the mapping doesn't fill fails here (tsanetgit/Connect_SDK#146).
 */
class OpenApiRecordsTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-01T09:00Z");
    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-10-02T10:30Z");

    @Test
    void aCollaborationRequestMapsEveryFieldToItsOwnComponent() {
        CaseCustomFieldStatusDTO answer = new CaseCustomFieldStatusDTO();
        answer.setId(9001L);
        answer.setFieldName("Product");
        answer.setValue("answer-value");
        CollaborationRequestStatusDTO dto = new CollaborationRequestStatusDTO();
        dto.setId(77L);
        dto.setStatus(CaseStatus.INFORMATION);
        dto.setSummary("summary-text");
        dto.setSubmitCompanyName("submitter-name");
        dto.setSubmitCompanyId(1042L);
        dto.setReceiveCompanyName("receiver-name");
        dto.setReceiveCompanyId(2087L);
        dto.setToken("case-token");
        dto.setCreatedAt(CREATED);
        dto.setUpdatedAt(UPDATED);
        dto.setTestCase(true);
        dto.setManifest("manifest-jws");
        dto.setEncrypted(true);
        dto.setSubmitterCaseNumber("A-2026-004417");
        dto.setDescription("description-text");
        dto.setCustomFields(List.of(answer));

        CollaborationRequestStatusDto record = OpenApiRecords.collaborationRequest(dto);

        assertThat(record).hasNoNullFieldsOrProperties();
        assertThat(record).isEqualTo(new CollaborationRequestStatusDto(77L, "INFORMATION", "summary-text",
            "submitter-name", 1042L, "receiver-name", 2087L, "case-token", CREATED.toString(), UPDATED.toString(),
            true, "manifest-jws", true, "A-2026-004417", "description-text",
            List.of(new CaseCustomFieldDto(9001L, "Product", "answer-value"))));
    }

    @Test
    void nullCustomFieldsStayNullNotEmpty() {
        CollaborationRequestStatusDTO dto = new CollaborationRequestStatusDTO();
        dto.setCustomFields(null);

        assertThat(OpenApiRecords.collaborationRequest(dto).customFields()).isNull();
    }

    @Test
    void aCaseNoteMapsEveryFieldToItsOwnComponentAndTakesTheCaseTokenFromTheCaller() {
        NoteTypeSelectionDTO type = new NoteTypeSelectionDTO();
        type.setCode("partner");
        type.setDisplayName("Partner note");
        CaseNoteDTO dto = new CaseNoteDTO();
        dto.setId(5L);
        dto.setCaseId(77L);
        dto.setCompanyName("author-company");
        dto.setCreatorUsername("creator-username");
        dto.setCreatorEmail("creator@example.invalid");
        dto.setCreatorName("creator-name");
        dto.setSummary("note-summary");
        dto.setDescription("note-description");
        dto.setPriority(NotePriority.HIGH);
        dto.setStatus(NoteStatus.READ);
        dto.setToken("note-token");
        dto.setCreatedAt(CREATED);
        dto.setUpdatedAt(UPDATED);
        dto.setType(type);
        dto.setCompanyId(1042L);
        dto.setDirection(CollaborationRequestDirection.INBOUND);
        dto.setManifest("note-manifest");
        dto.setEncrypted(true);

        CaseNoteDto record = OpenApiRecords.caseNote(dto, "case-token");

        assertThat(record).hasNoNullFieldsOrProperties();
        assertThat(record).isEqualTo(new CaseNoteDto(5L, 77L, "case-token", "author-company", "creator-username",
            "creator@example.invalid", "creator-name", "note-summary", "note-description", "HIGH", "READ",
            "note-token", CREATED.toString(), UPDATED.toString(), "partner", 1042L, "INBOUND", "Partner note",
            "note-manifest", true));
    }

    @Test
    void aFormFieldMapsEveryFieldToItsOwnComponent() {
        FieldMetadataDTO dto = new FieldMetadataDTO();
        dto.setFieldId(101L);
        dto.setSection(FieldMetadataSection.PROBLEM_SECTION);
        dto.setLabel("field-label");
        dto.setType(FieldMetadataType.EMAIL);
        dto.setDisplayOrder(3);
        dto.setRequired(true);
        dto.setOptions("field-options");
        dto.setValidationRules("field-rules");
        dto.setValue("field-value");

        FormFieldDto record = OpenApiRecords.formField(dto);

        assertThat(record).hasNoNullFieldsOrProperties();
        assertThat(record).isEqualTo(new FormFieldDto(101L, "PROBLEM_SECTION", "field-label", "EMAIL", 3, true,
            "field-options", "field-rules", "field-value"));
    }
}
