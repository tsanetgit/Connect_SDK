package com.tsanet.demo.web;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
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
     */
    @PostMapping("/api/requests")
    public CollaborationRequestStatusDto createRequest(@RequestBody CreateRequestBody body) {
        var session = guard.session();
        if (body.formTemplate() != null) {
            return session.collaborationRequests().createRequest(
                body.formTemplate(),
                body.caseNumber(),
                body.summary(),
                body.description(),
                body.customFieldValues() != null ? body.customFieldValues() : Map.of(),
                true
            );
        }
        if (body.receiverCompanyId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "either formTemplate or receiverCompanyId is required");
        }
        return session.collaborationRequests().createRequest(
            body.receiverCompanyId(),
            body.caseNumber(),
            body.summary(),
            body.description(),
            true
        );
    }

    @GetMapping("/api/requests/{token}")
    public CaseDetail getRequest(@PathVariable String token) {
        var session = guard.session();
        CollaborationRequestStatusDto status = session.collaborationRequests().fetchRequestByToken(token);
        List<CaseNoteDto> notes = session.caseNotes().listNotesForRequest(token);
        List<CaseResponseDto> responses = session.caseResponses().listResponsesForRequest(token);
        return new CaseDetail(status, notes, responses);
    }

    @PostMapping("/api/requests/{token}/notes")
    public CaseNoteDto addNote(@PathVariable String token, @RequestBody NoteBody body) {
        return guard.session().caseNotes().createNote(token, body.summary(), body.description(), body.priority());
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

    public record CreateRequestBody(
        Long receiverCompanyId,
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues
    ) {
    }

    public record NoteBody(String summary, String description, String priority) {
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

    public record CaseDetail(
        CollaborationRequestStatusDto status,
        List<CaseNoteDto> notes,
        List<CaseResponseDto> responses
    ) {
    }
}
