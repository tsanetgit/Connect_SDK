package com.tsanet.e2ee;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.E2eeFields;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.e2ee.FieldCipher.FieldCipherException;
import com.tsanet.e2ee.Manifest.ManifestException;
import com.tsanet.e2ee.Records.CasePlaintext;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedCustomField;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Records.EncryptedCase;
import com.tsanet.e2ee.Records.EncryptedNote;
import com.tsanet.e2ee.Records.NotePlaintext;
import com.tsanet.e2ee.Verification.KeysUnavailable;
import com.tsanet.e2ee.Verification.Refused;
import com.tsanet.e2ee.Verification.Step;
import com.tsanet.e2ee.Verification.Verified;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end encryption of Connect cases and notes for one member, to the {@code tsanet-e2ee-1}
 * profile ("Connect E2E Encryption", Addenda B to D).
 *
 * <p>Outbound, {@link #encryptCase} and {@link #encryptNote} check the plaintext, encrypt each
 * protected field for the partner and for this member, and sign a manifest; the results go to
 * connect-library's create overloads unchanged. Inbound, {@link #verifyCase} and {@link #verifyNote}
 * run the six checks of Addendum D §5 in order and return the decrypted content only when every
 * one passes. Nothing here writes to connect-library's cache, which keeps what the platform returned.
 *
 * <p>Protected: {@code problemSummary}, {@code problemDescription}, each custom-field value (an empty
 * string included; a null stays null), and a note's {@code summary} and {@code description}.
 * Everything else, including information requests and responses and a rejection's reason, stays
 * readable; the API has no manifest for those.
 */
public final class E2eeProfile {
    /** How a record reached the caller, which decides whether the first-receipt check runs. */
    public enum Receipt {
        /** A webhook delivery, or the response to the caller's own create: checks {@code iat} and {@code jti}. */
        FIRST_RECEIPT,
        /** Any later read of a stored record: the platform returns the same manifest every time. */
        STORED_READ
    }

    private static final int MAX_SUMMARY = 500;
    private static final int MAX_DESCRIPTION = 10_000;
    private static final int MAX_CUSTOM_VALUE = 5_000;
    private static final int MAX_NOTE_DESCRIPTION = 5_000;

    private final String ownCompanyId;
    private final KeyOperations keys;
    private final PartnerKeys partnerKeys;
    private final ReplayGuard replayGuard;
    private final Clock clock;

    /**
     * @param ownCompanyId this member's TSANet company id
     * @param keys         this member's private-key operations
     * @param partnerKeys  public keys by company, this member's own included
     * @param replayGuard  the first-receipt check
     */
    public E2eeProfile(String ownCompanyId, KeyOperations keys, PartnerKeys partnerKeys, ReplayGuard replayGuard, Clock clock) {
        this.ownCompanyId = Objects.requireNonNull(ownCompanyId);
        this.keys = Objects.requireNonNull(keys);
        this.partnerKeys = Objects.requireNonNull(partnerKeys);
        this.replayGuard = Objects.requireNonNull(replayGuard);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---- outbound ----

    /** Encrypts a case this member is about to create for {@code partnerCompanyId}. */
    public EncryptedCase encryptCase(CasePlaintext plaintext, String partnerCompanyId) throws E2eeException {
        Map<Long, String> answers = plaintext.customFieldValues() == null ? Map.of() : plaintext.customFieldValues();
        List<FormFieldDto> form = plaintext.formFields() == null ? List.of() : plaintext.formFields();
        requireText(plaintext.caseNumber(), "internalCaseNumber");
        requireText(plaintext.summary(), Profile.PROBLEM_SUMMARY);
        requireLength(plaintext.summary(), MAX_SUMMARY, Profile.PROBLEM_SUMMARY);
        requireLength(plaintext.description(), MAX_DESCRIPTION, Profile.PROBLEM_DESCRIPTION);
        checkForm(answers, form);

        String jti = UUID.randomUUID().toString();
        List<RSAKey> recipients = List.of(partnerEncryptionKey(partnerCompanyId), ownEncryptionKey());
        List<Manifest.FieldDigest> digests = new ArrayList<>();

        String summary = encryptField(plaintext.summary(), Profile.PROBLEM_SUMMARY, jti, recipients, digests);
        String description = encryptField(plaintext.description(), Profile.PROBLEM_DESCRIPTION, jti, recipients, digests);
        Map<Long, String> customValues = new LinkedHashMap<>();
        for (Map.Entry<Long, String> answer : answers.entrySet()) {
            String name = Profile.customField(answer.getKey());
            customValues.put(answer.getKey(), encryptField(answer.getValue(), name, jti, recipients, digests));
        }

        String manifest = signManifest(ownCompanyId, partnerCompanyId, plaintext.caseNumber(), jti, digests);
        return new EncryptedCase(plaintext.caseNumber(), summary, description, customValues, new E2eeFields(manifest, true));
    }

    /** Encrypts a note this member is about to add to {@code caseRecord}, for the case's other party. */
    public EncryptedNote encryptNote(NotePlaintext plaintext, CollaborationRequestStatusDto caseRecord) throws E2eeException {
        requireText(plaintext.summary(), Profile.NOTE_SUMMARY);
        requireText(plaintext.description(), Profile.NOTE_DESCRIPTION);
        requireLength(plaintext.summary(), MAX_SUMMARY, Profile.NOTE_SUMMARY);
        requireLength(plaintext.description(), MAX_NOTE_DESCRIPTION, Profile.NOTE_DESCRIPTION);
        String partner = otherParty(caseRecord, ownCompanyId);
        if (partner == null) {
            throw new E2eeException("This member is not a party to the case");
        }
        requireText(caseRecord.submitterCaseNumber(), "submitterCaseNumber");

        String jti = UUID.randomUUID().toString();
        List<RSAKey> recipients = List.of(partnerEncryptionKey(partner), ownEncryptionKey());
        List<Manifest.FieldDigest> digests = new ArrayList<>();
        String summary = encryptField(plaintext.summary(), Profile.NOTE_SUMMARY, jti, recipients, digests);
        String description = encryptField(plaintext.description(), Profile.NOTE_DESCRIPTION, jti, recipients, digests);

        String manifest = signManifest(ownCompanyId, partner, caseRecord.submitterCaseNumber(), jti, digests);
        return new EncryptedNote(summary, description, new E2eeFields(manifest, true));
    }

    private String encryptField(String plaintext, String name, String jti, List<RSAKey> recipients,
                                List<Manifest.FieldDigest> digests) throws E2eeException {
        if (plaintext == null) {
            return null;
        }
        String jwe;
        try {
            jwe = FieldCipher.encrypt(plaintext, new FieldCipher.Binding(Profile.VERSION, jti, name), recipients);
        } catch (GeneralSecurityException failed) {
            throw new E2eeException("Encrypting " + name + " failed");
        }
        digests.add(new Manifest.FieldDigest(name, Manifest.digest(jwe)));
        return jwe;
    }

    private String signManifest(String iss, String aud, String ref, String jti, List<Manifest.FieldDigest> digests)
        throws E2eeException {
        Manifest.Claims claims = new Manifest.Claims(
            Profile.VERSION, iss, aud, ref, clock.instant().getEpochSecond(), jti, List.copyOf(digests));
        try {
            return Manifest.sign(claims, keys);
        } catch (GeneralSecurityException failed) {
            throw new E2eeException("Signing the manifest failed");
        }
    }

    // Provisional, waiting on the E2EE workgroup (a decision for the shared specification, which has no
    // issue tracker): the profile doesn't say which key to use when a member publishes two encryption
    // keys, so this refuses rather than guess, and the docs ask members to publish only the current one.
    // When the workgroup sets a rule (newest first, a use date, or all keys as recipients), it replaces this.
    private RSAKey partnerEncryptionKey(String companyId) throws E2eeException {
        RSAKey match = null;
        for (JWK key : publicKeysForEncrypting(companyId).getKeys()) {
            if (key instanceof RSAKey rsa && isEncryptionKey(rsa)) {
                if (match != null) {
                    throw new E2eeException("Company " + companyId
                        + " publishes more than one encryption key; a member publishes only its current one");
                }
                match = rsa;
            }
        }
        if (match == null) {
            throw new E2eeException("Company " + companyId + " publishes no encryption key");
        }
        return match;
    }

    private RSAKey ownEncryptionKey() throws E2eeException {
        JWK key = publicKeysForEncrypting(ownCompanyId).getKeyByKeyId(keys.encryptionKid());
        if (!(key instanceof RSAKey rsa) || !isEncryptionKey(rsa)) {
            throw new E2eeException("This member's JWKS has no encryption key with the configured kid");
        }
        return rsa;
    }

    private JWKSet publicKeysForEncrypting(String companyId) throws E2eeException {
        try {
            return partnerKeys.publicKeys(companyId);
        } catch (IOException unavailable) {
            throw new E2eeException("Public keys for company " + companyId + " are unavailable");
        }
    }

    /** The specification's floor for an encryption key: RSA, 2048 bits or more. */
    private static final int MIN_RSA_BITS = 2048;

    private static boolean isEncryptionKey(RSAKey key) {
        return key.getKeyID() != null
            && key.size() >= MIN_RSA_BITS
            && (key.getKeyUse() == null || KeyUse.ENCRYPTION.equals(key.getKeyUse()))
            && (key.getAlgorithm() == null || JWEAlgorithm.RSA_OAEP_256.equals(key.getAlgorithm()));
    }

    private static void checkForm(Map<Long, String> values, List<FormFieldDto> formFields) throws E2eeException {
        for (Map.Entry<Long, String> answer : values.entrySet()) {
            requireLength(answer.getValue(), MAX_CUSTOM_VALUE, Profile.customField(answer.getKey()));
        }
        for (FormFieldDto field : formFields) {
            if (field.required() && field.fieldId() != null) {
                String value = values.get(field.fieldId());
                if (value == null || value.isBlank()) {
                    throw new E2eeException("Required field " + Profile.customField(field.fieldId()) + " has no value");
                }
            }
        }
    }

    private static void requireText(String value, String name) throws E2eeException {
        if (value == null || value.isBlank()) {
            throw new E2eeException(name + " must not be empty");
        }
    }

    private static void requireLength(String value, int max, String name) throws E2eeException {
        if (value != null && value.length() > max) {
            throw new E2eeException(name + " exceeds " + max + " characters");
        }
    }

    // ---- inbound ----

    /** Verifies and decrypts a case read from Connect. */
    public Verification<DecryptedCase> verifyCase(CollaborationRequestStatusDto record, Receipt receipt) {
        String iss = idString(record.submitCompanyId());
        String aud = idString(record.receiveCompanyId());

        Map<String, String> wire = new LinkedHashMap<>();
        if (record.summary() != null) {
            wire.put(Profile.PROBLEM_SUMMARY, SanitizerCompat.restore(record.summary()));
        }
        if (record.description() != null) {
            wire.put(Profile.PROBLEM_DESCRIPTION, SanitizerCompat.restore(record.description()));
        }
        List<CaseCustomFieldDto> customFields = record.customFields() == null ? List.of() : record.customFields();

        Checked checked = checkManifest(record.manifest(), record.encrypted(), iss, aud, record.submitterCaseNumber(), receipt);
        if (checked.failed() != null) {
            return refused(checked.failed());
        }

        // Step 5. A custom field's read id isn't the form's fieldId, so its manifest name comes from its
        // JWE's tsa_fld; reading it before decryption is for lookup only, and step 6 checks it again.
        List<String> namesInOrder = new ArrayList<>();
        for (CaseCustomFieldDto field : customFields) {
            if (field.value() == null) {
                namesInOrder.add(null);
                continue;
            }
            String value = SanitizerCompat.restore(field.value());
            String name;
            try {
                name = FieldCipher.parse(value).binding().field();
            } catch (FieldCipherException notAJwe) {
                return refused(new Refused<>(Step.FIELDS, null, "a custom-field value is not a profile JWE"));
            }
            if (Profile.customFieldId(name) == null || wire.containsKey(name)) {
                // The name isn't authenticated yet, so it isn't repeated in the refusal.
                return refused(new Refused<>(Step.FIELDS, null, "a custom-field JWE names an invalid or repeated field"));
            }
            wire.put(name, value);
            namesInOrder.add(name);
        }

        Opened opened = checkAndOpenFields(wire, checked.claims());
        if (opened.refused() != null) {
            return refused(opened.refused());
        }

        List<DecryptedCustomField> decryptedFields = new ArrayList<>();
        for (int i = 0; i < customFields.size(); i++) {
            String name = namesInOrder.get(i);
            decryptedFields.add(name == null
                ? new DecryptedCustomField(null, customFields.get(i).id(), null)
                : new DecryptedCustomField(Profile.customFieldId(name), customFields.get(i).id(), opened.plaintext().get(name)));
        }
        return new Verified<>(new DecryptedCase(
            opened.plaintext().get(Profile.PROBLEM_SUMMARY),
            opened.plaintext().get(Profile.PROBLEM_DESCRIPTION),
            List.copyOf(decryptedFields)));
    }

    /**
     * Verifies and decrypts a note read from Connect. The case supplies the binding: {@code ref} is
     * the case's submitter case number, and the note's author and the other party are its two members.
     */
    public Verification<DecryptedNote> verifyNote(CaseNoteDto note, CollaborationRequestStatusDto caseRecord, Receipt receipt) {
        if (note.caseToken() != null && caseRecord.token() != null && !note.caseToken().equals(caseRecord.token())) {
            return refused(new Refused<>(Step.CLAIMS, null, "the note belongs to another case"));
        }
        String iss = idString(note.companyId());
        String aud = iss == null ? null : otherParty(caseRecord, iss);

        Map<String, String> wire = new LinkedHashMap<>();
        if (note.summary() != null) {
            wire.put(Profile.NOTE_SUMMARY, SanitizerCompat.restore(note.summary()));
        }
        if (note.description() != null) {
            wire.put(Profile.NOTE_DESCRIPTION, SanitizerCompat.restore(note.description()));
        }

        Checked checked = checkManifest(note.manifest(), note.encrypted(), iss, aud, caseRecord.submitterCaseNumber(), receipt);
        if (checked.failed() != null) {
            return refused(checked.failed());
        }
        Opened opened = checkAndOpenFields(wire, checked.claims());
        if (opened.refused() != null) {
            return refused(opened.refused());
        }
        return new Verified<>(new DecryptedNote(
            opened.plaintext().get(Profile.NOTE_SUMMARY), opened.plaintext().get(Profile.NOTE_DESCRIPTION)));
    }

    /** {@code failed} is a {@link Refused} or {@link KeysUnavailable}; null when steps 1 to 4 pass. */
    private record Checked(Manifest.Claims claims, Verification<?> failed) {
    }

    private record Opened(Map<String, String> plaintext, Refused<?> refused) {
    }

    @SuppressWarnings("unchecked")
    private static <T> Verification<T> refused(Verification<?> failure) {
        return (Verification<T>) failure;
    }

    /** Steps 1 to 4. */
    private Checked checkManifest(String manifest, Boolean encrypted, String iss, String aud, String ref, Receipt receipt) {
        // Step 1: the sender's key, from the JWKS for the company the record says sent it.
        if (iss == null) {
            return refuse(Step.SENDER_KEY, "the record names no sending company");
        }
        JWSObject jws;
        try {
            jws = Manifest.parse(manifest);
        } catch (ManifestException unusable) {
            return refuse(Step.SIGNATURE, "no manifest, or an unparseable one");
        }
        // Step 3 would refuse these whatever the keys say. Refusing them before the key lookup keeps a
        // record that can never pass from reading as KeysUnavailable when its sender has no keys.
        if (aud == null) {
            return refuse(Step.CLAIMS, "iss or aud does not match the record");
        }
        if (!ownCompanyId.equals(iss) && !ownCompanyId.equals(aud)) {
            return refuse(Step.CLAIMS, "this member is neither iss nor aud");
        }
        ECKey senderKey;
        try {
            String kid = jws.getHeader().getKeyID();
            JWK key = partnerKeys.publicKeys(iss).getKeyByKeyId(kid);
            if (key == null && kid != null) {
                key = partnerKeys.refreshedPublicKeys(iss).getKeyByKeyId(kid);
            }
            if (!(key instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve())
                || (ec.getKeyUse() != null && !KeyUse.SIGNATURE.equals(ec.getKeyUse()))
                || (ec.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(ec.getAlgorithm()))) {
                return refuse(Step.SENDER_KEY, "the sending company publishes no P-256 signing key with that kid");
            }
            senderKey = ec;
        } catch (IOException unavailable) {
            return new Checked(null, new KeysUnavailable<>());
        }

        // Step 2.
        try {
            Manifest.verify(jws, senderKey);
        } catch (ManifestException bad) {
            return refuse(Step.SIGNATURE, bad.getMessage());
        }

        // Step 3.
        Manifest.Claims claims;
        try {
            claims = Manifest.claims(jws);
        } catch (ManifestException malformed) {
            return refuse(Step.CLAIMS, malformed.getMessage());
        }
        if (!Boolean.TRUE.equals(encrypted)) {
            return refuse(Step.CLAIMS, "the record is not marked encrypted");
        }
        if (!Profile.VERSION.equals(claims.ver())) {
            return refuse(Step.CLAIMS, "unsupported profile version");
        }
        if (!iss.equals(claims.iss()) || aud == null || !aud.equals(claims.aud())) {
            return refuse(Step.CLAIMS, "iss or aud does not match the record");
        }
        if (!ownCompanyId.equals(claims.iss()) && !ownCompanyId.equals(claims.aud())) {
            return refuse(Step.CLAIMS, "this member is neither iss nor aud");
        }
        if (ref == null || !ref.equals(claims.ref())) {
            return refuse(Step.CLAIMS, "ref does not match the case number");
        }
        if (claims.jti() == null) {
            return refuse(Step.CLAIMS, "no jti");
        }

        // Step 4.
        if (receipt == Receipt.FIRST_RECEIPT) {
            Instant iat;
            try {
                iat = Instant.ofEpochSecond(claims.iat());
            } catch (java.time.DateTimeException outOfRange) {
                return refuse(Step.FIRST_RECEIPT, "iat out of range");
            }
            ReplayGuard.Result result = replayGuard.check(claims.iss(), claims.jti(), iat);
            if (result != ReplayGuard.Result.FIRST) {
                return refuse(Step.FIRST_RECEIPT, switch (result) {
                    case REPLAYED -> "jti already seen";
                    case TOO_OLD -> "iat older than the allowed age";
                    case IN_FUTURE -> "iat later than the allowed skew";
                    case FIRST -> throw new IllegalStateException();
                });
            }
        }
        return new Checked(claims, null);
    }

    private static Checked refuse(Step step, String reason) {
        return new Checked(null, new Refused<>(step, null, reason));
    }

    /** Steps 5 and 6, over the protected values present on the record, by manifest name. */
    private Opened checkAndOpenFields(Map<String, String> wire, Manifest.Claims claims) {
        // Step 5: the two sides list the same fields, and each digest matches the value as transmitted.
        Set<String> listed = new HashSet<>();
        for (Manifest.FieldDigest entry : claims.fields()) {
            if (!listed.add(entry.name())) {
                return new Opened(null, new Refused<>(Step.FIELDS, entry.name(), "listed twice in the manifest"));
            }
            if (!wire.containsKey(entry.name())) {
                return new Opened(null, new Refused<>(Step.FIELDS, entry.name(), "in the manifest but not on the record"));
            }
        }
        Map<String, FieldCipher.Parsed> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : wire.entrySet()) {
            if (!listed.contains(field.getKey())) {
                return new Opened(null, new Refused<>(Step.FIELDS, field.getKey(), "on the record but not in the manifest"));
            }
            String dig = claims.fields().stream().filter(f -> f.name().equals(field.getKey())).findFirst().orElseThrow().dig();
            if (!Manifest.sameDigest(dig, Manifest.digest(field.getValue()))) {
                return new Opened(null, new Refused<>(Step.FIELDS, field.getKey(), "digest does not match"));
            }
            try {
                parsed.put(field.getKey(), FieldCipher.parse(field.getValue()));
            } catch (FieldCipherException notAJwe) {
                return new Opened(null, new Refused<>(Step.FIELDS, field.getKey(), "not a profile JWE"));
            }
        }

        // Step 6: open each with this member's key, then check the authenticated binding.
        Map<String, String> plaintext = new LinkedHashMap<>();
        for (Map.Entry<String, FieldCipher.Parsed> field : parsed.entrySet()) {
            String value;
            try {
                value = FieldCipher.open(field.getValue(), keys);
            } catch (FieldCipherException failed) {
                return new Opened(null, new Refused<>(Step.DECRYPT, field.getKey(), failed.getMessage()));
            }
            FieldCipher.Binding binding = field.getValue().binding();
            if (!claims.ver().equals(binding.version()) || !claims.jti().equals(binding.jti())
                || !field.getKey().equals(binding.field())) {
                return new Opened(null, new Refused<>(Step.DECRYPT, field.getKey(), "the JWE's binding does not match the manifest"));
            }
            plaintext.put(field.getKey(), value);
        }
        return new Opened(plaintext, null);
    }

    private static String idString(Long id) {
        return id == null ? null : id.toString();
    }

    /** The case's other member, or null when {@code companyId} isn't one of its two. */
    private static String otherParty(CollaborationRequestStatusDto caseRecord, String companyId) {
        String submitter = idString(caseRecord.submitCompanyId());
        String receiver = idString(caseRecord.receiveCompanyId());
        if (companyId.equals(submitter)) {
            return receiver;
        }
        if (companyId.equals(receiver)) {
            return submitter;
        }
        return null;
    }
}
