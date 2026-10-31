package com.tsanet.api.connectapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One collaboration request as the API returns it.
 *
 * @param submitterCaseNumber the submitting member's own case number ({@code internalCaseNumber}
 *                            on create); null when the API sent none
 * @param description         the problem description ({@code problemDescription} on create),
 *                            exactly as the API returned it; ciphertext when {@code encrypted}
 * @param customFields        the custom-field answers in the order the API sent them, which it doesn't
 *                            promise to keep between reads, so match by id or name; empty when the API sent
 *                            none or left the field out, null when not known (an explicit null from
 *                            the API, or a record cached before this field existed)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollaborationRequestStatusDto(
    Long id,
    String status,
    String summary,
    String submitCompanyName,
    Long submitCompanyId,
    String receiveCompanyName,
    Long receiveCompanyId,
    String token,
    String createdAt,
    String updatedAt,
    Boolean testCase,
    String manifest,
    Boolean encrypted,
    String submitterCaseNumber,
    String description,
    List<CaseCustomFieldDto> customFields
) {
    @Override
    public String toString() {
        return "CollaborationRequestStatusDto[id=" + id + ", status=" + status
            + ", token=<redacted>, testCase=" + testCase + ", encrypted=" + encrypted + "]";
    }
}
