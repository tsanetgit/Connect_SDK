package com.tsanet.api.connectapi.dto;

/**
 * A note type a caller may send on create, as {@code GET /v1/note-types} lists it.
 *
 * @param code        the stable lowercase code to send; match on it, never on the label
 * @param displayName the human-readable label
 */
public record NoteTypeDto(String code, String displayName) {
}
