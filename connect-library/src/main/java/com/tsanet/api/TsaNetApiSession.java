package com.tsanet.api;

import com.tsanet.api.facade.AttachmentStorageFacade;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AttachmentsV2Facade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;

public interface TsaNetApiSession extends AutoCloseable {
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

    /** The receiver's side of V2: where files sent to this account's company land. */
    AttachmentStorageFacade attachmentStorage();

    /**
     * Stops this session's HTTP client, so a session you are done with doesn't keep its selector
     * thread and pooled connections until it is garbage-collected. A session holds them from the
     * moment it opens, whether or not it signed in. Close each session you drop, for example on a
     * credential change.
     *
     * <p>This doesn't wait: requests already sent run to completion, and a request made afterward
     * fails as "cannot reach the Connect API", a {@code ConnectApiException} of kind
     * {@code CONNECTIVITY}, so a caller's connectivity retries run and fail too. An attachment upload
     * runs on a client of its own, so its {@code PUT}s finish, but the calls around them need this
     * session: a {@code send} still running when the session closes can't complete its grant, which
     * stays open until it expires. The SQLite cache stays on disk, and reads of stored data keep
     * working. Closing twice does nothing more.
     *
     * <p>{@code AccountScopedTsaNetApiSession} closes its active account's session and goes back to
     * having none: its facades refuse until the next login or authenticate opens a new one.
     */
    @Override
    void close();
}
