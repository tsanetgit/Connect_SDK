package com.tsanet.facade.cli;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class FormResolver {
    private final TsaNetApiSession session;
    private final PartnerSelectionResolver partnerSelectionResolver;

    public FormResolver(TsaNetApiSession session, PartnerSelectionResolver partnerSelectionResolver) {
        this.session = session;
        this.partnerSelectionResolver = partnerSelectionResolver;
    }

    public void requireAuthentication() {
        partnerSelectionResolver.requireAuthentication();
    }

    public CollaborationRequestFormTemplateDto resolve(String[] args, Scanner scanner, CliRunContext cliRunContext) {
        if (CliArgs.documentId(args).isPresent()) {
            return session.collaborationRequests().getCreateFormByDocumentId(CliArgs.documentId(args).get());
        }
        if (CliArgs.departmentId(args).isPresent()) {
            return session.collaborationRequests().getCreateFormByDepartmentId(CliArgs.departmentId(args).get());
        }
        if (CliArgs.companyId(args).isPresent()) {
            return session.collaborationRequests().getCreateFormByCompanyId(CliArgs.companyId(args).get());
        }
        if (CliArgs.search(args).isPresent()) {
            List<PartnerSelectionDto> partners = partnerSelectionResolver.search(args);
            PartnerSelectionDto selected = partnerSelectionResolver.selectPartner(
                partners,
                args,
                scanner,
                cliRunContext
            );
            return resolveFromPartner(selected);
        }
        throw new IllegalArgumentException(
            "Provide --company-id ID, --department-id ID, --document-id ID, or --search TERM"
        );
    }

    private CollaborationRequestFormTemplateDto resolveFromPartner(PartnerSelectionDto partner) {
        if (partner.documentId() != null) {
            return session.collaborationRequests().getCreateFormByDocumentId(partner.documentId());
        }
        if (partner.departmentId() != null) {
            return session.collaborationRequests().getCreateFormByDepartmentId(partner.departmentId());
        }
        if (partner.companyId() != null) {
            return session.collaborationRequests().getCreateFormByCompanyId(partner.companyId());
        }
        throw new IllegalStateException("Selected partner has no company, department, or document identifier.");
    }
}
