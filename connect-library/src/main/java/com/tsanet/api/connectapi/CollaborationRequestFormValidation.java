package com.tsanet.api.connectapi;

import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.api.connectapi.internal.FormTemplateMapper;
import com.tsanet.api.generated.model.FieldMetadataDTO;
import java.util.ArrayList;
import java.util.List;

public final class CollaborationRequestFormValidation {
    private CollaborationRequestFormValidation() {
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateRequiredFields(List<FormFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return ValidationResult.ok();
        }
        List<String> missing = new ArrayList<>();
        for (FormFieldDto field : fields) {
            if (!field.required()) {
                continue;
            }
            if (field.value() == null || field.value().isBlank()) {
                missing.add(labelFor(field));
            }
        }
        if (missing.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.invalid("Required custom fields are missing values: " + String.join(", ", missing));
    }

    public static ValidationResult validateRequiredApiFields(List<FieldMetadataDTO> fields) {
        return validateRequiredFields(FormTemplateMapper.toFieldDtos(fields));
    }

    private static String labelFor(FormFieldDto field) {
        if (field.label() != null && !field.label().isBlank()) {
            return field.label();
        }
        if (field.fieldId() != null) {
            return "fieldId=" + field.fieldId();
        }
        return "unknown field";
    }
}
