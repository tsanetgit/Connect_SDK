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
 * @param customFields        the custom-field answers in the API's order; empty when the API sent
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
    /**
     * Compatibility constructor from before {@code submitterCaseNumber}, {@code description} and
     * {@code customFields} existed; leaves all three null, which readers must treat as "not
     * known", never as "no description" or "no answers".
     *
     * @deprecated pass the three explicitly; scheduled for removal at the next release boundary.
     */
    @Deprecated
    public CollaborationRequestStatusDto(
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
        Boolean encrypted
    ) {
        this(id, status, summary, submitCompanyName, submitCompanyId, receiveCompanyName,
            receiveCompanyId, token, createdAt, updatedAt, testCase, manifest, encrypted, null, null, null);
    }

    /**
     * Compatibility constructor from before {@code manifest} and {@code encrypted} existed;
     * leaves both null. A null {@code encrypted} means "not known" (an explicit null from the API, or
     * a row cached before the field existed), never "not encrypted"; a value the API leaves out
     * reads false, the spec's default.
     *
     * @deprecated pass {@code manifest} and {@code encrypted} explicitly; scheduled for removal at
     *     the next release boundary.
     */
    @Deprecated
    public CollaborationRequestStatusDto(
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
        Boolean testCase
    ) {
        this(id, status, summary, submitCompanyName, submitCompanyId, receiveCompanyName,
            receiveCompanyId, token, createdAt, updatedAt, testCase, null, null, null, null, null);
    }

    /**
     * Compatibility constructor from before {@code testCase} existed; leaves it null,
     * which readers must treat as "unknown", never as "real case".
     *
     * @deprecated pass {@code testCase} explicitly; scheduled for removal at the next
     *     release boundary.
     */
    @Deprecated
    public CollaborationRequestStatusDto(
        Long id,
        String status,
        String summary,
        String submitCompanyName,
        Long submitCompanyId,
        String receiveCompanyName,
        Long receiveCompanyId,
        String token,
        String createdAt,
        String updatedAt
    ) {
        this(id, status, summary, submitCompanyName, submitCompanyId, receiveCompanyName,
            receiveCompanyId, token, createdAt, updatedAt, null, null, null, null, null, null);
    }
}
