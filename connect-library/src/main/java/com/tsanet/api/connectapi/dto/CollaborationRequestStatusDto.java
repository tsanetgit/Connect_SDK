package com.tsanet.api.connectapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
    Boolean encrypted
) {
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
            receiveCompanyId, token, createdAt, updatedAt, testCase, null, null);
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
            receiveCompanyId, token, createdAt, updatedAt, null, null, null);
    }
}
