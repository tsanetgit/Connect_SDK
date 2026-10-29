package com.tsanet.api.connectapi.dto;

/**
 * One custom-field answer on a collaboration request, as the API returns it on a read.
 *
 * @param id        the API's id for this field on the case. The spec doesn't say it equals the
 *                  form's {@code fieldId} that a create sends, so don't rely on that
 * @param fieldName the field's label as the API sent it
 * @param value     the answer exactly as the API returned it; an empty string and null are
 *                  different answers and are kept apart, in the cache too
 */
public record CaseCustomFieldDto(Long id, String fieldName, String value) {
}
