package com.tsanet.api;

import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AttachmentsV2Facade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;

public interface TsaNetApiSession {
    AuthFacade auth();

    CollaborationRequestsFacade collaborationRequests();

    CaseNotesFacade caseNotes();

    CaseResponsesFacade caseResponses();

    UserFacade users();

    WebhooksFacade webhooks();

    PartnersFacade partners();

    AttachmentsFacade attachments();

    /** The V2 direct-delivery attachment client (tsanetgit/Connect-API-Code#147). */
    AttachmentsV2Facade attachmentsV2();
}
