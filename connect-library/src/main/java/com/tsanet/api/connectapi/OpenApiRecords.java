package com.tsanet.api.connectapi;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.api.generated.model.CaseCustomFieldStatusDTO;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The mapping from the generated OpenAPI models to the records {@code connect-e2ee} takes. The
 * library's own gateways map these records through here, and a caller on the generated client uses
 * it too, so the two can't drift apart.
 *
 * <p>The parameters are generated types, so a rename in the Connect OpenAPI spec changes these
 * signatures. Every argument must be non-null, except {@code caseNote}'s {@code caseToken}.
 */
public final class OpenApiRecords {
    private OpenApiRecords() {
    }

    /**
     * The one place a read of a collaboration request becomes a {@link CollaborationRequestStatusDto}:
     * every gateway that receives one (lists, fetch by token, create, lifecycle calls) maps it here.
     */
    public static CollaborationRequestStatusDto collaborationRequest(CollaborationRequestStatusDTO dto) {
        return new CollaborationRequestStatusDto(
            dto.getId(),
            dto.getStatus() != null ? dto.getStatus().getValue() : null,
            dto.getSummary(),
            dto.getSubmitCompanyName(),
            dto.getSubmitCompanyId(),
            dto.getReceiveCompanyName(),
            dto.getReceiveCompanyId(),
            dto.getToken(),
            dateTime(dto.getCreatedAt()),
            dateTime(dto.getUpdatedAt()),
            dto.getTestCase(),
            dto.getManifest(),
            dto.getEncrypted(),
            dto.getSubmitterCaseNumber(),
            dto.getDescription(),
            caseCustomFields(dto.getCustomFields())
        );
    }

    /**
     * A case note as the API returned it.
     *
     * @param caseToken the token of the case the note was read under; the generated model doesn't
     *                  carry it. {@code connect-e2ee}'s {@code verifyNote} compares it with the case's
     *                  token, and a null skips that check
     */
    public static CaseNoteDto caseNote(CaseNoteDTO dto, String caseToken) {
        return new CaseNoteDto(
            dto.getId(),
            dto.getCaseId(),
            caseToken,
            dto.getCompanyName(),
            dto.getCreatorUsername(),
            dto.getCreatorEmail(),
            dto.getCreatorName(),
            dto.getSummary(),
            dto.getDescription(),
            enumValue(dto.getPriority()),
            enumValue(dto.getStatus()),
            dto.getToken(),
            dateTime(dto.getCreatedAt()),
            dateTime(dto.getUpdatedAt()),
            dto.getType() == null ? null : dto.getType().getCode(),
            dto.getCompanyId(),
            enumValue(dto.getDirection()),
            dto.getType() == null ? null : dto.getType().getDisplayName(),
            dto.getManifest(),
            dto.getEncrypted()
        );
    }

    /** One field of a collaboration request form, as {@code connect-e2ee}'s {@code CasePlaintext} takes it. */
    public static FormFieldDto formField(FieldMetadataDTO field) {
        return new FormFieldDto(
            field.getFieldId(),
            enumValue(field.getSection()),
            field.getLabel(),
            enumValue(field.getType()),
            field.getDisplayOrder(),
            Boolean.TRUE.equals(field.getRequired()),
            field.getOptions(),
            field.getValidationRules(),
            field.getValue()
        );
    }

    /** Each answer as sent, in order; null stays null so "not known" never reads as "no answers". */
    private static List<CaseCustomFieldDto> caseCustomFields(List<CaseCustomFieldStatusDTO> fields) {
        if (fields == null) {
            return null;
        }
        return fields.stream()
            .map(field -> new CaseCustomFieldDto(field.getId(), field.getFieldName(), field.getValue()))
            .toList();
    }

    // Kept here rather than shared with the internal OpenApiMapping, which isn't public;
    // internal.OpenApiMappingAgreementTest asserts the two copies agree.
    private static String dateTime(OffsetDateTime value) {
        return value != null ? value.toString() : null;
    }

    private static String enumValue(Enum<?> value) {
        return value != null ? value.name() : null;
    }
}
