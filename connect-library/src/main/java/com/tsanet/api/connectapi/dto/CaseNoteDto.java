package com.tsanet.api.connectapi.dto;

/**
 * One case note.
 *
 * @param type      the note type's lowercase code as the API sent it, for example
 *                  {@code partner}, {@code public} or {@code system}; null when the sender set
 *                  no type. Don't treat a null or unrecognized type as public.
 * @param companyId the authoring company's id; null when the API sent none, which the spec
 *                  says is the case for a platform-generated note
 * @param direction {@code OUTBOUND} when the reading company wrote the note, {@code INBOUND}
 *                  otherwise; relative to the account that fetched it, and null when absent
 * @param typeDisplayName the type's human-readable label as the API sent it; null when the API
 *                  sent no type. For display only, never for matching
 */
public record CaseNoteDto(
    Long id,
    Long caseId,
    String caseToken,
    String companyName,
    String creatorUsername,
    String creatorEmail,
    String creatorName,
    String summary,
    String description,
    String priority,
    String status,
    String token,
    String createdAt,
    String updatedAt,
    String type,
    Long companyId,
    String direction,
    String typeDisplayName
) {
    /**
     * Compatibility constructor from before {@code type}, {@code companyId},
     * {@code direction} and {@code typeDisplayName} existed; leaves all four null, which readers
     * must treat as "not sent", never as a default type or direction.
     *
     * @deprecated pass {@code type}, {@code companyId}, {@code direction} and
     *     {@code typeDisplayName} explicitly;
     *     scheduled for removal at the next release boundary.
     */
    @Deprecated
    public CaseNoteDto(
        Long id,
        Long caseId,
        String caseToken,
        String companyName,
        String creatorUsername,
        String creatorEmail,
        String creatorName,
        String summary,
        String description,
        String priority,
        String status,
        String token,
        String createdAt,
        String updatedAt
    ) {
        this(id, caseId, caseToken, companyName, creatorUsername, creatorEmail, creatorName, summary,
            description, priority, status, token, createdAt, updatedAt, null, null, null, null);
    }
}
