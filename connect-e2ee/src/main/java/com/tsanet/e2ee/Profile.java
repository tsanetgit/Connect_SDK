package com.tsanet.e2ee;

/**
 * The names the {@code tsanet-e2ee-1} profile fixes: the version string, the manifest's JWS type,
 * and each protected field's manifest name. The workgroup specification "Connect E2E Encryption"
 * (Addenda B, C and D) is the authority; these are its values.
 */
public final class Profile {
    /** The profile version: the manifest's {@code ver} and each JWE's {@code tsa_ver}. */
    public static final String VERSION = "tsanet-e2ee-1";

    /** The manifest's JWS {@code typ}. */
    public static final String MANIFEST_TYPE = "tsanet-manifest+jws";

    /** The digest algorithm name in each manifest {@code fields[]} entry. */
    public static final String DIGEST_ALG = "S256";

    public static final String PROBLEM_SUMMARY = "problemSummary";
    public static final String PROBLEM_DESCRIPTION = "problemDescription";
    public static final String NOTE_SUMMARY = "summary";
    public static final String NOTE_DESCRIPTION = "description";

    private static final String CUSTOM_FIELD_PREFIX = "customFields/";
    private static final String CUSTOM_FIELD_SUFFIX = "/value";

    private Profile() {
    }

    /** A custom-field value's manifest name, {@code customFields/{fieldId}/value}, by the form's {@code fieldId}. */
    public static String customField(long fieldId) {
        return CUSTOM_FIELD_PREFIX + fieldId + CUSTOM_FIELD_SUFFIX;
    }

    /**
     * The form {@code fieldId} in a custom-field manifest name, or null when the name isn't one in
     * canonical form: {@code customFields/0101/value} or {@code customFields/+101/value} isn't, so no
     * two names can claim the same field.
     */
    static Long customFieldId(String name) {
        if (name == null || !name.startsWith(CUSTOM_FIELD_PREFIX) || !name.endsWith(CUSTOM_FIELD_SUFFIX)) {
            return null;
        }
        String id = name.substring(CUSTOM_FIELD_PREFIX.length(), name.length() - CUSTOM_FIELD_SUFFIX.length());
        try {
            long fieldId = Long.parseLong(id);
            return customField(fieldId).equals(name) ? fieldId : null;
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
