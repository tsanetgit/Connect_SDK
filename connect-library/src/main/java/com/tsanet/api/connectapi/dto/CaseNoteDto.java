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
 * @param manifest  the end-to-end encryption manifest exactly as the API returned it; null when
 *                  the note has none. Opaque to the library: never parsed or verified here
 * @param encrypted whether the sender marked the note's text as encrypted. An omitted value reads
 *                  false (the spec's default); null means not known: the API sent an explicit null,
 *                  or the note was cached before this field existed
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
    String typeDisplayName,
    String manifest,
    Boolean encrypted
) {
    @Override
    public String toString() {
        return "CaseNoteDto[id=" + id + ", caseId=" + caseId
            + ", caseToken=<redacted>, token=<redacted>, type=" + type + ", encrypted=" + encrypted + "]";
    }
}
