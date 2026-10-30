package com.tsanet.demo.web;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import com.tsanet.api.connectapi.dto.NoteTypeDto;
import com.tsanet.demo.config.E2eeKeys;
import com.tsanet.e2ee.E2eeException;
import com.tsanet.e2ee.E2eeProfile;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Records.CasePlaintext;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedCustomField;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Records.EncryptedCase;
import com.tsanet.e2ee.Records.EncryptedNote;
import com.tsanet.e2ee.Records.NotePlaintext;
import com.tsanet.e2ee.Verification;
import com.tsanet.e2ee.Verification.Refused;
import com.tsanet.e2ee.Verification.Verified;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CollaborationRequestsController {

    private final SessionGuard guard;

    public CollaborationRequestsController(SessionGuard guard) {
        this.guard = guard;
    }

    @GetMapping("/api/requests")
    public List<CollaborationRequestStatusDto> listRequests() {
        return guard.session().collaborationRequests().listRequests();
    }

    /**
     * Creates a collaboration request. Form mode (formTemplate + customFieldValues)
     * drives the partner's configured process form; simple mode falls back to
     * receiverCompanyId with just the standard fields.
     *
     * <p>With {@code encrypt} true the protected fields (summary, description, each custom-field
     * value) are encrypted for the partner and signed before the SDK sees them, and the create goes
     * through the {@code E2eeFields} overload (tsanetgit/Connect_SDK#149). A partner with no key
     * entry or a plaintext that breaks the form's rules is a 400, E2EE off a 403; never a plaintext send.
     */
    @PostMapping("/api/requests")
    public CollaborationRequestStatusDto createRequest(@RequestBody CreateRequestBody body) {
        var session = guard.session();
        Map<Long, String> customFieldValues = body.customFieldValues() != null ? body.customFieldValues() : Map.of();
        boolean encrypt = Boolean.TRUE.equals(body.encrypt());
        if (body.formTemplate() != null) {
            if (encrypt) {
                // A template fetched by document or department names no receiver (the API resolves it from
                // the document), so the partner the page selected supplies the company to encrypt for.
                Long partner = body.formTemplate().receiverCompanyId() != null
                    ? body.formTemplate().receiverCompanyId() : body.receiverCompanyId();
                EncryptedCase out = encryptCase(guard.e2ee(session), body, customFieldValues, partner,
                    body.formTemplate().fields());
                return session.collaborationRequests().createRequest(
                    body.formTemplate(), out.caseNumber(), out.summary(), out.description(),
                    out.customFieldValues(), true, out.e2ee());
            }
            return session.collaborationRequests().createRequest(
                body.formTemplate(),
                body.caseNumber(),
                body.summary(),
                body.description(),
                customFieldValues,
                true
            );
        }
        if (body.receiverCompanyId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "either formTemplate or receiverCompanyId is required");
        }
        if (encrypt) {
            EncryptedCase out = encryptCase(guard.e2ee(session), body, customFieldValues, body.receiverCompanyId(), null);
            return session.collaborationRequests().createRequest(
                body.receiverCompanyId(), out.caseNumber(), out.summary(), out.description(), true, out.e2ee());
        }
        return session.collaborationRequests().createRequest(
            body.receiverCompanyId(),
            body.caseNumber(),
            body.summary(),
            body.description(),
            true
        );
    }

    /** The same condition the Settings table answers 403 for: the demo cannot encrypt, so the request is refused. */
    private static final String E2EE_OFF = "E2EE is off for this demo: " + E2eeKeys.VARIABLE
        + " is not set (or the session was just replaced; retry)";

    private static EncryptedCase encryptCase(E2eeProfile e2ee, CreateRequestBody body, Map<Long, String> customFieldValues,
                                             Long partnerCompanyId, List<FormFieldDto> formFields) {
        if (e2ee == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, E2EE_OFF);
        }
        if (partnerCompanyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Neither the form nor the request names the receiver company, so there is nobody to encrypt for");
        }
        try {
            return e2ee.encryptCase(
                new CasePlaintext(body.caseNumber(), body.summary(), body.description(), customFieldValues, formFields),
                partnerCompanyId.toString());
        } catch (E2eeException refused) {
            // The module's message names the field or the company id, never a value.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
        }
    }

    /**
     * The case, its notes and its responses. Every record here is a stored read, so with E2EE on
     * each {@code encrypted} record is verified as {@link Receipt#STORED_READ} (signature, binding
     * and digests, not freshness) and carries an {@code e2ee} block: verified with the decrypted
     * content, or refused with the step and field and no value. The DTOs keep their ciphertext,
     * and the SDK's cache is untouched. With E2EE off nothing is added (tsanetgit/Connect_SDK#149).
     */
    @GetMapping("/api/requests/{token}")
    public CaseDetail getRequest(@PathVariable String token) {
        var session = guard.session();
        CollaborationRequestStatusDto status = session.collaborationRequests().fetchRequestByToken(token);
        List<CaseNoteDto> notes = session.caseNotes().listNotesForRequest(token);
        List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(token);
        E2eeProfile e2ee = guard.e2ee(session);
        E2eeView caseView = e2ee == null ? null : caseView(e2ee, status);
        List<NoteDetail> noteDetails = notes.stream()
            .map(note -> new NoteDetail(note, e2ee == null ? null : noteView(e2ee, note, status)))
            .toList();
        return new CaseDetail(status, noteDetails, responses, caseView);
    }

    // A record whose encrypted isn't true is plaintext and isn't verified (connect-e2ee README).
    private static E2eeView caseView(E2eeProfile e2ee, CollaborationRequestStatusDto status) {
        if (!Boolean.TRUE.equals(status.encrypted())) {
            return E2eeView.PLAINTEXT;
        }
        return switch (e2ee.verifyCase(status, Receipt.STORED_READ)) {
            case Verified<DecryptedCase> ok -> E2eeView.verifiedCase(ok.content());
            case Refused<DecryptedCase> no -> E2eeView.refused(no);
        };
    }

    private static E2eeView noteView(E2eeProfile e2ee, CaseNoteDto note, CollaborationRequestStatusDto status) {
        if (!Boolean.TRUE.equals(note.encrypted())) {
            return E2eeView.PLAINTEXT;
        }
        return switch (e2ee.verifyNote(note, status, Receipt.STORED_READ)) {
            case Verified<DecryptedNote> ok -> E2eeView.verifiedNote(ok.content());
            case Refused<DecryptedNote> no -> E2eeView.refused(no);
        };
    }

    /** The note types the Add note form offers, in the server's order (GET /v1/note-types). */
    @GetMapping("/api/note-types")
    public List<NoteTypeDto> noteTypes() {
        return guard.session().caseNotes().listNoteTypes();
    }

    /**
     * Adds a note. With {@code encrypt} true the summary and text are encrypted for the case's other
     * party, which the case record names, and the create goes through the {@code E2eeFields} overload.
     */
    @PostMapping("/api/requests/{token}/notes")
    public CaseNoteDto addNote(@PathVariable String token, @RequestBody NoteBody body) {
        String type = body.type() == null || body.type().isBlank() ? null : body.type();
        var session = guard.session();
        if (Boolean.TRUE.equals(body.encrypt())) {
            E2eeProfile e2ee = guard.e2ee(session);
            if (e2ee == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, E2EE_OFF);
            }
            CollaborationRequestStatusDto caseRecord = session.collaborationRequests().fetchRequestByToken(token);
            EncryptedNote note;
            try {
                note = e2ee.encryptNote(new NotePlaintext(body.summary(), body.description()), caseRecord);
            } catch (E2eeException refused) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
            }
            return session.caseNotes().createNote(token, note.summary(), note.description(), body.priority(), type, note.e2ee());
        }
        return session.caseNotes().createNote(token, body.summary(), body.description(), body.priority(), type);
    }

    @PostMapping("/api/requests/{token}/approve")
    public CollaborationRequestStatusDto approve(@PathVariable String token, @RequestBody EngineerActionBody body) {
        return guard.session().caseResponses().approveRequest(
            token, body.caseNumber(), body.engineerName(), body.engineerEmail(), body.engineerPhone(), body.text());
    }

    @PostMapping("/api/requests/{token}/reject")
    public CollaborationRequestStatusDto reject(@PathVariable String token, @RequestBody EngineerActionBody body) {
        return guard.session().caseResponses().rejectRequest(
            token, body.engineerName(), body.engineerEmail(), body.engineerPhone(), body.text());
    }

    @PostMapping("/api/requests/{token}/request-info")
    public CollaborationRequestStatusDto requestInfo(@PathVariable String token, @RequestBody EngineerActionBody body) {
        return guard.session().caseResponses().submitInformationRequest(
            token, body.engineerName(), body.engineerEmail(), body.engineerPhone(), body.text());
    }

    @PostMapping("/api/requests/{token}/respond-info")
    public CollaborationRequestStatusDto respondInfo(@PathVariable String token, @RequestBody TextBody body) {
        return guard.session().caseResponses().submitInformationResponse(token, body.text());
    }

    @PostMapping("/api/requests/{token}/close")
    public CollaborationRequestStatusDto close(@PathVariable String token) {
        return guard.session().caseResponses().closeRequest(token);
    }

    /**
     * {@code encrypt} true encrypts the protected fields for the receiver company first; absent or false sends
     * plaintext. In form mode {@code receiverCompanyId} is the selected partner's company, used to encrypt when
     * the template itself names no receiver; it plays no part in a plaintext form create.
     */
    public record CreateRequestBody(
        Long receiverCompanyId,
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        Boolean encrypt
    ) {
    }

    /**
     * {@code type} is a code from {@code /api/note-types}, passed through as given; blank or absent creates a note with no type.
     * {@code encrypt} true encrypts the summary and text for the case's other party.
     */
    public record NoteBody(String summary, String description, String priority, String type, Boolean encrypt) {
    }

    /** Shared body for approve/reject/request-info; {@code text} carries nextSteps, reason, or requestedInformation. */
    public record EngineerActionBody(
        String caseNumber,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String text
    ) {
    }

    public record TextBody(String text) {
    }

    /**
     * The verification of one stored record for the page. {@code state} is {@code plaintext}
     * (not marked encrypted; not verified), {@code verified} (with the decrypted summary,
     * description and, for a case, custom-field values by the form's {@code fieldId}) or
     * {@code refused} (with the step, the field when the failure is about one, and the module's
     * reason; never a value). Nulls are left out, so a plaintext record shows only its state.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record E2eeView(
        String state,
        String step,
        String field,
        String reason,
        String summary,
        String description,
        Map<Long, String> customFields
    ) {
        static final E2eeView PLAINTEXT = new E2eeView("plaintext", null, null, null, null, null, null);

        static E2eeView verifiedCase(DecryptedCase content) {
            Map<Long, String> values = new LinkedHashMap<>();
            for (DecryptedCustomField field : content.customFields()) {
                // A null answer has no JWE and so no fieldId to show it under.
                if (field.fieldId() != null) {
                    values.put(field.fieldId(), field.value());
                }
            }
            return new E2eeView("verified", null, null, null, content.summary(), content.description(), values);
        }

        static E2eeView verifiedNote(DecryptedNote content) {
            return new E2eeView("verified", null, null, null, content.summary(), content.description(), null);
        }

        static E2eeView refused(Refused<?> refusal) {
            return new E2eeView("refused", refusal.step().name(), refusal.field(), refusal.reason(), null, null, null);
        }

        @Override
        public String toString() {
            return "E2eeView[" + state + (step == null ? "" : ", " + step + (field == null ? "" : "/" + field)) + "]";
        }
    }

    /** A note as the API returned it, plus its {@code e2ee} block; the block is left out with E2EE off. */
    public record NoteDetail(
        @JsonUnwrapped CaseNoteDto note,
        @JsonInclude(JsonInclude.Include.NON_NULL) E2eeView e2ee
    ) {
    }

    public record CaseDetail(
        CollaborationRequestStatusDto status,
        List<NoteDetail> notes,
        List<CaseResponseDto> responses,
        @JsonInclude(JsonInclude.Include.NON_NULL) E2eeView e2ee
    ) {
    }
}
