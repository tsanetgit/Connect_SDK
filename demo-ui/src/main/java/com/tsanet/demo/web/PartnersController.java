package com.tsanet.demo.web;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PartnersController {

    private final SessionGuard guard;

    public PartnersController(SessionGuard guard) {
        this.guard = guard;
    }

    @GetMapping("/api/partners")
    public List<PartnerSelectionDto> searchPartners(@RequestParam("q") String searchTerm) {
        return guard.session().partners().searchPartners(searchTerm);
    }

    /**
     * Resolves the partner's process form. Exactly one of documentId,
     * departmentId, or companyId is used, in that priority order —
     * matching how a PartnerSelectionDto routes to a form.
     */
    @GetMapping("/api/partners/form")
    public CollaborationRequestFormTemplateDto getForm(
        @RequestParam(value = "documentId", required = false) Long documentId,
        @RequestParam(value = "departmentId", required = false) Long departmentId,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        var session = guard.session();
        if (documentId != null) {
            return session.collaborationRequests().getCreateFormByDocumentId(documentId);
        }
        if (departmentId != null) {
            return session.collaborationRequests().getCreateFormByDepartmentId(departmentId);
        }
        if (companyId != null) {
            return session.collaborationRequests().getCreateFormByCompanyId(companyId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "one of documentId, departmentId, or companyId is required");
    }
}
