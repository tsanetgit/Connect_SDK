package com.tsanet.facade.crash;

import com.tsanet.facade.cli.CliCommandDispatcher;
import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.CollaborationRequestApprovalExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsAddExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsConfigExecutor;
import com.tsanet.facade.cli.CollaborationRequestAttachmentsListExecutor;
import com.tsanet.facade.cli.CollaborationRequestCloseExecutor;
import com.tsanet.facade.cli.CollaborationRequestInformationRequestExecutor;
import com.tsanet.facade.cli.CollaborationRequestInformationResponseExecutor;
import com.tsanet.facade.cli.CollaborationRequestNoteAddExecutor;
import com.tsanet.facade.cli.CollaborationRequestRejectionExecutor;
import org.crsh.cli.Argument;
import org.crsh.cli.Command;
import org.crsh.cli.Required;
import org.crsh.cli.Usage;
import org.crsh.command.BaseCommand;
import org.springframework.beans.factory.BeanFactory;
import java.util.Arrays;

@Usage("TSANet Connect API facade (uses connect-library via Spring)")
public class TsaCrashCommands extends BaseCommand {

    protected CliCommandDispatcher dispatcher() {
        return bean(CliCommandDispatcher.class);
    }

    protected CliRunContext cliRunContext() {
        return bean(CliRunContext.class);
    }

    protected <T> T bean(Class<T> type) {
        BeanFactory factory = (BeanFactory) context.getAttributes().get("factory");
        return factory.getBean(type);
    }

    protected void run(String commandName, String... args) {
        dispatcher().runPlain(commandName, args);
    }

    @Command
    @Usage("log in to Connect API (username password)")
    public void login(
        @Usage("username") @Required @Argument String username,
        @Usage("password") @Required @Argument String password
    ) {
        run("login", username, password);
    }

    @Command
    @Usage("log in and print JWT only (alias for scripting)")
    public void apiLogin(
        @Usage("username") @Required @Argument String username,
        @Usage("password") @Required @Argument String password
    ) {
        run("api-login", username, password);
    }

    @Command
    @Usage("log in with credentials from application.yml")
    public void loginConfigured() {
        run("login-configured");
    }

    @Command
    @Usage("print current bearer token")
    public void token() {
        run("token");
    }

    @Command
    @Usage("clear Connect API session")
    public void logout() {
        run("logout");
    }

    @Command
    @Usage("alias for logout")
    public void apiLogout() {
        logout();
    }

    @Command
    @Usage("show Connect API session state")
    public void session() {
        run("session");
    }

    @Command
    @Usage("fetch collaboration requests from Connect API")
    public void requests() {
        run("requests");
    }

    @Command
    @Usage("fetch collaboration requests filtered by company id")
    public void requestsForCompany(@Usage("companyId") @Required @Argument Long companyId) {
        run("requests", "--company-id", String.valueOf(companyId));
    }

    @Command
    @Usage("list collaboration requests stored in SQLite")
    public void storedRequests() {
        run("stored-requests");
    }

    @Command
    @Usage("list stored collaboration requests for company id")
    public void storedRequestsForCompany(@Usage("companyId") @Required @Argument Long companyId) {
        run("stored-requests", "--company-id", String.valueOf(companyId));
    }

    @Command
    @Usage("fetch notes timeline for one request id")
    public void notesList(@Usage("requestId") @Required @Argument Long requestId) {
        run("notes-list", "--id", String.valueOf(requestId));
    }

    @Command
    @Usage("fetch notes timeline for one request token")
    public void notesListForToken(@Usage("token") @Required @Argument String token) {
        run("notes-list", "--token", token);
    }

    @Command
    @Usage("alias for notesList")
    public void notes_list(@Usage("requestId") @Required @Argument Long requestId) {
        notesList(requestId);
    }

    @Command
    @Usage("add note to collaboration request by id")
    public void notesAdd(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("text") @Required @Argument String text
    ) {
        runNoteAdd("--id", String.valueOf(requestId), "--text", text);
    }

    @Command
    @Usage("add note with summary to collaboration request by id")
    public void notesAddDetailed(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("summary") @Required @Argument String summary,
        @Usage("text") @Required @Argument String text
    ) {
        runNoteAdd("--id", String.valueOf(requestId), "--summary", summary, "--text", text);
    }

    @Command
    @Usage("add note to collaboration request by token")
    public void notesAddForToken(
        @Usage("token") @Required @Argument String token,
        @Usage("text") @Required @Argument String text
    ) {
        runNoteAdd("--token", token, "--text", text);
    }

    @Command
    @Usage("alias for notesAdd")
    public void notes_add(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("text") @Required @Argument String text
    ) {
        notesAdd(requestId, text);
    }

    @Command
    @Usage("alias for notesAdd")
    public void add_note(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("text") @Required @Argument String text
    ) {
        notesAdd(requestId, text);
    }

    @Command
    @Usage("list stored attachments for collaboration request by id")
    public void attachmentsList(@Usage("requestId") @Required @Argument Long requestId) {
        runAttachmentsList("--id", String.valueOf(requestId));
    }

    @Command
    @Usage("list stored attachments for collaboration request by token")
    public void attachmentsListForToken(@Usage("token") @Required @Argument String token) {
        runAttachmentsList("--token", token);
    }

    @Command
    @Usage("fetch attachment configuration for collaboration request by id")
    public void attachmentsConfig(@Usage("requestId") @Required @Argument Long requestId) {
        runAttachmentsConfig("--id", String.valueOf(requestId));
    }

    @Command
    @Usage("fetch attachment configuration by token")
    public void attachmentsConfigForToken(@Usage("token") @Required @Argument String token) {
        runAttachmentsConfig("--token", token);
    }

    @Command
    @Usage("forward attachment file to collaboration request by id")
    public void attachmentsAdd(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("description") @Required @Argument String description,
        @Usage("filePath") @Required @Argument String filePath
    ) {
        runAttachmentAdd(
            "--id",
            String.valueOf(requestId),
            "--description",
            description,
            "--file",
            filePath
        );
    }

    @Command
    @Usage("forward attachment file by token")
    public void attachmentsAddForToken(
        @Usage("token") @Required @Argument String token,
        @Usage("description") @Required @Argument String description,
        @Usage("filePath") @Required @Argument String filePath
    ) {
        runAttachmentAdd("--token", token, "--description", description, "--file", filePath);
    }

    @Command
    @Usage("list stored attachment forward results")
    public void storedAttachments() {
        run("stored-attachments");
    }

    @Command
    @Usage("alias for attachmentsAdd")
    public void add_attachment(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("description") @Required @Argument String description,
        @Usage("filePath") @Required @Argument String filePath
    ) {
        attachmentsAdd(requestId, description, filePath);
    }

    @Command
    @Usage("fetch notes for all collaboration requests")
    public void notesAll() {
        run("notes", "--all");
    }

    @Command
    @Usage("fetch notes for one request token")
    public void notesForToken(@Usage("token") @Required @Argument String token) {
        run("notes", "--token", token);
    }

    @Command
    @Usage("list notes stored in SQLite")
    public void storedNotes() {
        run("stored-notes");
    }

    @Command
    @Usage("list stored notes for request token")
    public void storedNotesForToken(@Usage("token") @Required @Argument String token) {
        run("stored-notes", "--token", token);
    }

    @Command
    @Usage("fetch case responses for all collaboration requests")
    public void responsesAll() {
        run("responses", "--all");
    }

    @Command
    @Usage("fetch case responses for one request token")
    public void responsesForToken(@Usage("token") @Required @Argument String token) {
        run("responses", "--token", token);
    }

    @Command
    @Usage("list case responses stored in SQLite")
    public void storedResponses() {
        run("stored-responses");
    }

    @Command
    @Usage("list stored responses for request token")
    public void storedResponsesForToken(@Usage("token") @Required @Argument String token) {
        run("stored-responses", "--token", token);
    }

    @Command
    @Usage("fetch current user from Connect API")
    public void me() {
        run("me");
    }

    @Command
    @Usage("show current user stored in SQLite")
    public void storedMe() {
        run("stored-me");
    }

    @Command
    @Usage("fetch webhook subscriptions")
    public void webhooks() {
        run("webhooks");
    }

    @Command
    @Usage("list webhook subscriptions stored in SQLite")
    public void storedWebhooks() {
        run("stored-webhooks");
    }

    @Command
    @Usage("register webhook subscription using configured callback URL")
    public void createWebhook() {
        run("create-webhook");
    }

    @Command
    @Usage("delete webhook subscription by id")
    public void deleteWebhook(@Usage("webhookId") @Required @Argument Long webhookId) {
        run("webhooks", "delete", "--id", String.valueOf(webhookId));
    }

    @Command
    @Usage("list webhook delivery log by subscription id")
    public void webhookDeliveries(@Usage("webhookId") @Required @Argument Long webhookId) {
        run("webhooks", "deliveries", "--id", String.valueOf(webhookId));
    }

    @Command
    @Usage("list inbound webhook events stored locally")
    public void webhookEvents() {
        run("webhook-events");
    }

    @Command
    @Usage("alias for createWebhook")
    public void create_webhook() {
        createWebhook();
    }

    @Command
    @Usage("alias for webhookEvents")
    public void webhook_events() {
        webhookEvents();
    }

    @Command
    @Usage("search partners by name")
    public void partners(@Usage("searchTerm") @Required @Argument String searchTerm) {
        run("partners", "--search", searchTerm);
    }

    @Command
    @Usage("semantic partner search by natural language query")
    public void partnersSemantic(@Usage("query") @Required @Argument String query) {
        run("partners", "--search", query, "--semantic");
    }

    @Command
    @Usage("create collaboration request by partner search and index")
    public void createRequestBySearch(
        @Usage("searchTerm") @Required @Argument String searchTerm,
        @Usage("partnerIndex") @Required @Argument Integer partnerIndex,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("summary") @Required @Argument String summary,
        @Usage("description") @Required @Argument String description
    ) {
        run(
            "create-request",
            "--search",
            searchTerm,
            "--partner-index",
            String.valueOf(partnerIndex),
            "--case-number",
            caseNumber,
            "--summary",
            summary,
            "--description",
            description
        );
    }

    @Command
    @Usage("list partners stored in SQLite")
    public void storedPartners() {
        run("stored-partners");
    }

    @Command
    @Usage("list stored partners for search term")
    public void storedPartnersForSearch(@Usage("searchTerm") @Required @Argument String searchTerm) {
        run("stored-partners", "--search", searchTerm);
    }

    @Command
    @Usage("fetch collaboration request form for receiver company")
    public void form(@Usage("companyId") @Required @Argument Long companyId) {
        run("form", "--company-id", String.valueOf(companyId));
    }

    @Command
    @Usage("fetch collaboration request form for department id")
    public void formForDepartment(@Usage("departmentId") @Required @Argument Long departmentId) {
        run("forms", "show", "--department-id", String.valueOf(departmentId));
    }

    @Command
    @Usage("fetch collaboration request form for document id")
    public void formForDocument(@Usage("documentId") @Required @Argument Long documentId) {
        run("forms", "show", "--document-id", String.valueOf(documentId));
    }

    @Command
    @Usage("list stored collaboration request forms")
    public void storedForms() {
        run("stored-forms");
    }

    @Command
    @Usage("create collaboration request (companyId caseNumber summary description)")
    public void createRequest(
        @Usage("companyId") @Required @Argument Long companyId,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("summary") @Required @Argument String summary,
        @Usage("description") @Required @Argument String description
    ) {
        run(
            "create-request",
            "--company-id",
            String.valueOf(companyId),
            "--case-number",
            caseNumber,
            "--summary",
            summary,
            "--description",
            description
        );
    }

    @Command
    @Usage("approve incoming collaboration request by id")
    public void approveRequest(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("nextSteps") @Required @Argument String nextSteps
    ) {
        runApprove(
            "--id",
            String.valueOf(requestId),
            "--case-number",
            caseNumber,
            "--engineer-name",
            engineerName,
            "--engineer-email",
            engineerEmail,
            "--engineer-phone",
            engineerPhone,
            "--next-steps",
            nextSteps
        );
    }

    @Command
    @Usage("alias for approveRequest")
    public void requestsApprove(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("nextSteps") @Required @Argument String nextSteps
    ) {
        approveRequest(requestId, caseNumber, engineerName, engineerEmail, engineerPhone, nextSteps);
    }

    @Command
    @Usage("sync requests, notes, and responses for all requests")
    public void sync() {
        run("sync");
    }

    @Command
    @Usage("alias for storedRequests")
    public void stored_requests() {
        storedRequests();
    }

    @Command
    @Usage("alias for storedNotes")
    public void stored_notes() {
        storedNotes();
    }

    @Command
    @Usage("alias for storedResponses")
    public void stored_responses() {
        storedResponses();
    }

    @Command
    @Usage("alias for storedMe")
    public void stored_me() {
        storedMe();
    }

    @Command
    @Usage("alias for storedWebhooks")
    public void stored_webhooks() {
        storedWebhooks();
    }

    @Command
    @Usage("alias for storedPartners")
    public void stored_partners() {
        storedPartners();
    }

    @Command
    @Usage("alias for notesAll")
    public void notes() {
        notesAll();
    }

    @Command
    @Usage("alias for notesListForToken")
    public void notesForRequest(@Usage("token") @Required @Argument String token) {
        notesListForToken(token);
    }

    @Command
    @Usage("alias for responsesAll")
    public void responses() {
        responsesAll();
    }

    @Command
    @Usage("alias for createRequest")
    public void create_request(
        @Usage("companyId") @Required @Argument Long companyId,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("summary") @Required @Argument String summary,
        @Usage("description") @Required @Argument String description
    ) {
        createRequest(companyId, caseNumber, summary, description);
    }

    @Command
    @Usage("alias for approveRequest")
    public void approve_request(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("caseNumber") @Required @Argument String caseNumber,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("nextSteps") @Required @Argument String nextSteps
    ) {
        approveRequest(requestId, caseNumber, engineerName, engineerEmail, engineerPhone, nextSteps);
    }

    @Command
    @Usage("reject collaboration request in INFORMATION status by id")
    public void rejectRequest(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("reason") @Required @Argument String reason
    ) {
        runReject(
            "--id",
            String.valueOf(requestId),
            "--engineer-name",
            engineerName,
            "--engineer-email",
            engineerEmail,
            "--engineer-phone",
            engineerPhone,
            "--reason",
            reason
        );
    }

    @Command
    @Usage("reject collaboration request by token")
    public void rejectRequestForToken(
        @Usage("token") @Required @Argument String token,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("reason") @Required @Argument String reason
    ) {
        runReject(
            "--token",
            token,
            "--engineer-name",
            engineerName,
            "--engineer-email",
            engineerEmail,
            "--engineer-phone",
            engineerPhone,
            "--reason",
            reason
        );
    }

    @Command
    @Usage("alias for rejectRequest")
    public void requestsReject(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("reason") @Required @Argument String reason
    ) {
        rejectRequest(requestId, engineerName, engineerEmail, engineerPhone, reason);
    }

    @Command
    @Usage("alias for rejectRequest")
    public void reject_request(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone") @Required @Argument String engineerPhone,
        @Usage("reason") @Required @Argument String reason
    ) {
        rejectRequest(requestId, engineerName, engineerEmail, engineerPhone, reason);
    }

    @Command
    @Usage("close collaboration request by id")
    public void closeRequest(@Usage("requestId") @Required @Argument Long requestId) {
        runClose("--id", String.valueOf(requestId));
    }

    @Command
    @Usage("close collaboration request by token")
    public void closeRequestForToken(@Usage("token") @Required @Argument String token) {
        runClose("--token", token);
    }

    @Command
    @Usage("alias for closeRequest")
    public void requestsClose(@Usage("requestId") @Required @Argument Long requestId) {
        closeRequest(requestId);
    }

    @Command
    @Usage("alias for closeRequest")
    public void close_request(@Usage("requestId") @Required @Argument Long requestId) {
        closeRequest(requestId);
    }

    @Command
    @Usage("request additional information on an open case by id")
    public void requestInformation(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone (use - if none)") @Required @Argument String engineerPhone,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        runInformationRequest(
            "--id",
            String.valueOf(requestId),
            "--engineer-name",
            engineerName,
            "--engineer-email",
            engineerEmail,
            "--engineer-phone",
            engineerPhone,
            "--requested-information",
            requestedInformation
        );
    }

    @Command
    @Usage("request additional information on an open case by token")
    public void requestInformationForToken(
        @Usage("token") @Required @Argument String token,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone (use - if none)") @Required @Argument String engineerPhone,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        runInformationRequest(
            "--token",
            token,
            "--engineer-name",
            engineerName,
            "--engineer-email",
            engineerEmail,
            "--engineer-phone",
            engineerPhone,
            "--requested-information",
            requestedInformation
        );
    }

    @Command
    @Usage("alias for requestInformation")
    public void requestsInfoRequest(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone (use - if none)") @Required @Argument String engineerPhone,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        requestInformation(requestId, engineerName, engineerEmail, engineerPhone, requestedInformation);
    }

    @Command
    @Usage("alias for requestInformation")
    public void request_information(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("engineerName") @Required @Argument String engineerName,
        @Usage("engineerEmail") @Required @Argument String engineerEmail,
        @Usage("engineerPhone (use - if none)") @Required @Argument String engineerPhone,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        requestInformation(requestId, engineerName, engineerEmail, engineerPhone, requestedInformation);
    }

    @Command
    @Usage("respond to an information request (status INFORMATION) by id")
    public void respondInformation(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        runInformationResponse(
            "--id",
            String.valueOf(requestId),
            "--requested-information",
            requestedInformation
        );
    }

    @Command
    @Usage("respond to an information request by token")
    public void respondInformationForToken(
        @Usage("token") @Required @Argument String token,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        runInformationResponse(
            "--token",
            token,
            "--requested-information",
            requestedInformation
        );
    }

    @Command
    @Usage("alias for respondInformation")
    public void requestsInfoResponse(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        respondInformation(requestId, requestedInformation);
    }

    @Command
    @Usage("alias for respondInformation")
    public void respond_information(
        @Usage("requestId") @Required @Argument Long requestId,
        @Usage("requestedInformation") @Required @Argument String requestedInformation
    ) {
        respondInformation(requestId, requestedInformation);
    }

    private void runApprove(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestApprovalExecutor.class).execute(args, cliRunContext);
    }

    private void runClose(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestCloseExecutor.class).execute(args, cliRunContext);
    }

    private void runReject(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestRejectionExecutor.class).execute(args, null, cliRunContext);
    }

    private void runNoteAdd(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestNoteAddExecutor.class).execute(args, null, cliRunContext);
    }

    private void runAttachmentsList(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestAttachmentsListExecutor.class).execute(args, cliRunContext);
    }

    private void runAttachmentsConfig(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestAttachmentsConfigExecutor.class).execute(args, cliRunContext);
    }

    private void runAttachmentAdd(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestAttachmentsAddExecutor.class).execute(args, null, cliRunContext);
    }

    private void runInformationRequest(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        String[] normalized = normalizeOptionalPhone(args);
        bean(CollaborationRequestInformationRequestExecutor.class).execute(normalized, null, cliRunContext);
    }

    private void runInformationResponse(String... args) {
        CliRunContext cliRunContext = cliRunContext();
        cliRunContext.configure(true, true);
        bean(CollaborationRequestInformationResponseExecutor.class).execute(args, null, cliRunContext);
    }

    private static String[] normalizeOptionalPhone(String[] args) {
        String[] copy = Arrays.copyOf(args, args.length);
        for (int i = 0; i < copy.length - 1; i++) {
            if ("--engineer-phone".equals(copy[i]) && "-".equals(copy[i + 1])) {
                copy[i + 1] = "";
            }
        }
        return copy;
    }
}
