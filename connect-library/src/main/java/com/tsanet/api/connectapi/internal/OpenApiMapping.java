package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.generated.model.CaseCustomFieldStatusDTO;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

final class OpenApiMapping {
    private OpenApiMapping() {
    }

    static String dateTime(OffsetDateTime value) {
        return value != null ? value.toString() : null;
    }

    static String enumValue(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    static String joinEnumList(List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * The one place a read of a collaboration request becomes a {@link CollaborationRequestStatusDto}:
     * every gateway that receives one (lists, fetch by token, create, lifecycle calls) maps it here.
     */
    static CollaborationRequestStatusDto collaborationRequest(CollaborationRequestStatusDTO dto) {
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

    /** Each answer as sent, in order; null stays null so "not known" never reads as "no answers". */
    static List<CaseCustomFieldDto> caseCustomFields(List<CaseCustomFieldStatusDTO> fields) {
        if (fields == null) {
            return null;
        }
        return fields.stream()
            .map(field -> new CaseCustomFieldDto(field.getId(), field.getFieldName(), field.getValue()))
            .toList();
    }
}
