package com.tsanet.e2ee;

import com.tsanet.api.connectapi.dto.E2eeFields;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.util.List;
import java.util.Map;

/**
 * The plaintext a caller hands in and the results it gets back. Every type that holds case content
 * keeps it out of {@code toString}, so logging one never logs a value.
 */
public final class Records {
    private Records() {
    }

    /**
     * A case to create, in plaintext.
     *
     * @param caseNumber        the sender's own case number, sent as {@code internalCaseNumber} and bound as {@code ref}
     * @param customFieldValues each answer by the form's {@code fieldId}; a null answer stays null and unencrypted
     * @param formFields        the form's field definitions, to check {@code required} and lengths on the plaintext
     *                          (the platform can't check ciphertext); may be empty or null
     */
    public record CasePlaintext(
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        List<FormFieldDto> formFields
    ) {
        @Override
        public String toString() {
            return "CasePlaintext[caseNumber=" + caseNumber + ", "
                + (customFieldValues == null ? 0 : customFieldValues.size()) + " custom fields]";
        }
    }

    /**
     * A case encrypted for one partner, ready for
     * {@code createRequest(formTemplate, caseNumber, summary, description, customFieldValues, testSubmission, e2ee)}:
     * pass these values and {@code e2ee} exactly as they are.
     */
    public record EncryptedCase(
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        E2eeFields e2ee
    ) {
        @Override
        public String toString() {
            return "EncryptedCase[caseNumber=" + caseNumber + ", " + customFieldValues.size() + " custom fields]";
        }
    }

    /** A note to create, in plaintext. */
    public record NotePlaintext(String summary, String description) {
        @Override
        public String toString() {
            return "NotePlaintext[]";
        }
    }

    /**
     * A note encrypted for the case's other party, ready for
     * {@code createNote(caseToken, summary, description, priority, type, e2ee)}.
     */
    public record EncryptedNote(String summary, String description, E2eeFields e2ee) {
        @Override
        public String toString() {
            return "EncryptedNote[]";
        }
    }

    /**
     * One decrypted custom-field answer.
     *
     * @param fieldId the form's {@code fieldId}, from the JWE's {@code tsa_fld}; null for an answer that was null,
     *                which has no JWE to name it
     * @param readId  the API's id for the field on this case, as the read returned it. Not authenticated: only
     *                {@code fieldId} and {@code value} are bound by the manifest, so key on {@code fieldId}
     * @param value   the answer; null stays null
     */
    public record DecryptedCustomField(Long fieldId, Long readId, String value) {
        @Override
        public String toString() {
            return "DecryptedCustomField[fieldId=" + fieldId + ", readId=" + readId + "]";
        }
    }

    /** A verified, decrypted case. Detached from the cached record, which keeps the ciphertext. */
    public record DecryptedCase(String summary, String description, List<DecryptedCustomField> customFields) {
        @Override
        public String toString() {
            return "DecryptedCase[" + customFields.size() + " custom fields]";
        }
    }

    /** A verified, decrypted note. */
    public record DecryptedNote(String summary, String description) {
        @Override
        public String toString() {
            return "DecryptedNote[]";
        }
    }
}
