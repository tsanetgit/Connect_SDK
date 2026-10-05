package com.tsanet.api.connectapi.dto;

/**
 * One case note.
 *
 * @param type      the note's type exactly as the API sent it ({@code USER_PUBLIC},
 *                  {@code USER_PARTNER} or {@code SYSTEM}); null when the API sent none
 * @param companyId the authoring company's id; null when the API sent none, which the spec
 *                  says is the case for a platform-generated note
 * @param direction {@code OUTBOUND} when the reading company wrote the note, {@code INBOUND}
 *                  otherwise; relative to the account that fetched it, and null when absent
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
    String direction
) {
    /**
     * Compatibility constructor from before {@code type}, {@code companyId} and
     * {@code direction} existed; leaves all three null, which readers must treat as
     * "not sent", never as a default type or direction.
     *
     * @deprecated pass {@code type}, {@code companyId} and {@code direction} explicitly;
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
            description, priority, status, token, createdAt, updatedAt, null, null, null);
    }
}
