package com.tsanet.api.connectapi.internal;

import static com.tsanet.api.connectapi.internal.OpenApiMapping.enumValue;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import java.util.Collections;
import java.util.List;

public final class FormTemplateMapper {
    private FormTemplateMapper() {
    }

    public static CollaborationRequestFormTemplateDto toTemplate(
        CollaborationRequestDTO form,
        Long receiverCompanyId,
        Long departmentId
    ) {
        if (form == null || form.getDocumentId() == null) {
            throw new IllegalStateException("Form template returned no documentId");
        }
        List<FormFieldDto> fields = form.getCustomFields() == null
            ? List.of()
            : form.getCustomFields().stream().map(FormTemplateMapper::toFieldDto).toList();
        return new CollaborationRequestFormTemplateDto(
            form.getDocumentId(),
            receiverCompanyId,
            departmentId,
            fields
        );
    }

    public static void applyCustomFieldValues(CollaborationRequestDTO form, java.util.Map<Long, String> values) {
        if (form.getCustomFields() == null || values == null || values.isEmpty()) {
            return;
        }
        for (FieldMetadataDTO field : form.getCustomFields()) {
            if (field.getFieldId() == null) {
                continue;
            }
            String provided = values.get(field.getFieldId());
            if (provided != null) {
                field.setValue(provided.strip());
            }
        }
    }

    public static List<FormFieldDto> toFieldDtos(List<FieldMetadataDTO> fields) {
        if (fields == null) {
            return Collections.emptyList();
        }
        return fields.stream().map(FormTemplateMapper::toFieldDto).toList();
    }

    private static FormFieldDto toFieldDto(FieldMetadataDTO field) {
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
}
