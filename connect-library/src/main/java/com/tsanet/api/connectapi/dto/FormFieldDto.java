package com.tsanet.api.connectapi.dto;

public record FormFieldDto(
    Long fieldId,
    String section,
    String label,
    String type,
    Integer displayOrder,
    boolean required,
    String options,
    String validationRules,
    String value
) {
}
