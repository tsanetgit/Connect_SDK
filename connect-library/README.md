# connect-library

Standalone Java client for the TSANet Connect API. The library wraps generated OpenAPI clients behind a small facade API, and persists fetched data to a local SQLite database for offline review.

## Maven dependency

```xml
<dependency>
    <groupId>com.tsanet</groupId>
    <artifactId>connect-library</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Quick start

```java
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;

TsaNetApiSession session = TsaNetApi.initialize(
    TsaNetApiConfiguration.of(
        "http://localhost:8080",
        System.getProperty("user.home") + "/.tsanet-client-demo/data.db",
        "api-user",
        "secret"
    )
);

session.auth().login("api-user", "secret");
var requests = session.collaborationRequests().listRequests();
```

## Configuration

| Setting | Type | Description |
|---------|------|-------------|
| `apiBaseUrl` | `String` | Connect API base URL (required) |
| `sqlitePath` | `String` | Path to the SQLite database file (required) |
| `username` | `String` | Optional default username for `loginWithConfiguredCredentials()` |
| `password` | `String` | Optional default password for `loginWithConfiguredCredentials()` |

## Session factory (isolated caches)

Use `TsaNetApi.sessionFactory()` when multiple sessions need separate SQLite files and bearer tokens (for example per integration scenario or per account label):

```java
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConnectionSettings;
import com.tsanet.api.TsaNetApiSessionFactory;

TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
    TsaNetApiConnectionSettings.of("http://localhost:8080", "/path/to/data.db")
);

TsaNetApiSession acme = factory.openSession("acme", "acme-user", "secret");
TsaNetApiSession beta = factory.openSession("beta", "beta-user", "secret");

// Or derive the SQLite file from the login username:
TsaNetApiSession account = factory.openSessionForAccount("api@appko.com", "secret");
```

Each `sessionLabel` gets its own database file: `data-acme.db`, `data-beta.db` (when the base path ends with `.db`). `openSessionForAccount()` uses `AccountSessionLabel.fromUsername()` so `api@appko.com` maps to `data-api-appko.com.db`.

## SQLite persistence

On startup the library creates tables if they do not exist (`CREATE TABLE IF NOT EXISTS`). Schema changes are additive and backward-compatible.

| Table | Populated when |
|-------|----------------|
| `collaboration_request` | Listing or creating collaboration requests |
| `case_note` | Listing or creating notes |
| `case_response` | Listing responses, approving, rejecting, or closing a request |
| `user_context` | Calling `getCurrentUser()` |
| `webhook_subscription` | Listing, creating, or deleting webhooks (includes persisted HMAC secret after create) |
| `webhook_inbound_event` | Inbound webhook payloads received by the bridge app |
| `partner_selection` | Searching partners |
| `collaboration_request_form` | Fetching a create form or creating a request |
| `attachment_config` | Fetching attachment config |
| `attachment_forward_result` | Forwarding attachments |

Remote reads and successful writes upsert rows (`ON CONFLICT … DO UPDATE`). Methods prefixed with `listStored` read from SQLite only and do not call the remote API.

Logout clears the in-memory bearer token only; the SQLite cache is retained.

---

## API reference

Access facades from `TsaNetApiSession`:

```java
session.auth();
session.collaborationRequests();
session.caseNotes();
session.caseResponses();
session.users();
session.webhooks();
session.partners();
session.attachments();
```

Unless noted, remote operations require a prior successful `authenticate()` or `login()`. Unauthenticated calls throw `IllegalStateException: Not logged in`.

### Auth — `session.auth()`

| Method | Description |
|--------|-------------|
| `authenticate()` | Uses configured auth: Azure AD client-credentials for production accounts, or Connect1 password when configured. Refreshes OAuth tokens proactively before expiry. |
| `login(username, password)` | Connect1 username/password login. Only available when the session is configured for `connect1-password` auth. |
| `loginWithConfiguredCredentials()` | Alias for `authenticate()`. |
| `isAuthorized()` | `true` when a valid bearer token is present in the session. |
| `currentUsername()` | Username from the last successful login, if any. |
| `currentAccountId()` | Application user id for OAuth sessions. |
| `authMode()` | `CLIENT_CREDENTIALS` or `CONNECT1_PASSWORD`. |
| `tokenExpiresAt()` | OAuth token expiry (OAuth sessions only). |
| `currentBearerToken()` | Current JWT, if any. |
| `logout()` | Clears in-memory session state (token and username). Does not delete SQLite data. |

#### OAuth client-credentials (Azure AD M2M)

```java
import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountConfigMapper;
import com.tsanet.api.TsaNetApiConfiguration;

ApplicationUserAccount account = ApplicationUserAccountConfigMapper.clientCredentialsAccount(
    "production",
    System.getProperty("user.home") + "/.tsanet/production.db",
    "your-tenant-id",
    null,
    "your-client-id",
    System.getenv("TSANET_CLIENT_SECRET"),
    "api://your-connect-api-audience",
    null
);

TsaNetApiSession session = TsaNetApi.sessionFactory(
    TsaNetApiConnectionSettings.of("https://connect.example.com", account.sqlitePath())
).openSessionForApplicationUser(account);

session.auth().authenticate();
```

Spring Boot apps can configure accounts in `application.yml`:

```yaml
tsanet:
  accounts:
    - id: production
      sqlite-path: "${user.home}/.tsanet/production.db"
      auth:
        type: client-credentials
        tenant-id: "..."
        client-id: "..."
        client-secret: "${TSANET_CLIENT_SECRET}"
        audience: "api://..."
    - id: local-dev
      sqlite-path: "${user.home}/.tsanet/local.db"
      auth:
        type: connect1-password
        username: "api@appko.com"
        password: "..."
```

Legacy top-level `username` / `password` on an account entry still map to `connect1-password`.

### Collaboration requests — `session.collaborationRequests()`

| Method | Description |
|--------|-------------|
| `listRequests()` | Fetches all collaboration requests from the API and upserts them into SQLite. |
| `listStoredRequests()` | Returns all collaboration requests from the local SQLite cache. |
| `listStoredRequestsForCompany(companyId)` | Returns cached requests where the company is submitter or receiver. Useful for separating inbound vs outbound views by company. |
| `getCreateForm(receiverCompanyId)` | Fetches the collaboration request form for a partner company and caches form metadata locally. |
| `getCreateFormByCompanyId(receiverCompanyId)` | Returns full form template with custom field schema. |
| `getCreateFormByDepartmentId(departmentId)` | Returns form template for a department. |
| `getCreateFormByDocumentId(documentId)` | Returns form template for a document id. |
| `listStoredForms()` | Returns all cached form metadata records. |
| `listStoredFormsForReceiver(receiverCompanyId)` | Returns cached form metadata for one receiver company. |
| `listStoredFormsForDocument(documentId)` | Returns cached form metadata for one document id. |
| `createRequest(receiverCompanyId, caseNumber, summary, description)` | Creates a collaboration request (outbound). Fetches form, validates required custom fields, caches metadata. |
| `createRequest(formTemplate, caseNumber, summary, description, customFieldValues)` | Creates using a resolved form template and custom field values. |
| `syncAllDetails()` | Fetches all requests, then for each request fetches notes, case responses, and attachment config from the API and updates SQLite. |

### Case notes — `session.caseNotes()`

| Method | Description |
|--------|-------------|
| `listNotesForRequest(caseToken)` | Fetches notes for one collaboration request and upserts them into SQLite. |
| `listNotesForAllRequests()` | Fetches notes for every known collaboration request. |
| `listStoredNotes()` | Returns all notes from SQLite. |
| `listStoredNotesForRequest(caseToken)` | Returns cached notes for one request. |
| `createNote(caseToken, summary, description, priority)` | Creates a note on the API. Validates non-empty summary/text and OpenAPI size limits (summary ≤ 500, description ≤ 5000). Refreshes the full notes list in SQLite for that request. |

### Case responses — `session.caseResponses()`

Case responses include approval and other comment-like activity on a collaboration request.

| Method | Description |
|--------|-------------|
| `listResponsesForRequest(caseToken)` | Fetches case responses for one request and upserts them into SQLite. |
| `listResponsesForAllRequests()` | Fetches responses for every collaboration request. |
| `listStoredResponses()` | Returns all cached case responses. |
| `listStoredResponsesForRequest(caseToken)` | Returns cached responses for one request. |
| `approveRequest(caseToken, caseNumber, engineerName, engineerEmail, engineerPhone, nextSteps)` | Approves an incoming collaboration request. Updates the cached request status and refreshes case responses for that token. |
| `closeRequest(caseToken)` | Closes a collaboration request. Updates the cached request status to `CLOSED` and refreshes case responses for that token. |
| `rejectRequest(caseToken, engineerName, engineerEmail, engineerPhone, reason)` | Rejects a collaboration request in `INFORMATION` status. Sends rejection reason to the API, updates cached status to `REJECTED`, and refreshes case responses. |
| `submitInformationRequest(caseToken, engineerName, engineerEmail, engineerPhone, requestedInformation)` | Requests additional information on an open case (`OPEN` or `ACCEPTED`). Updates cached status (typically to `INFORMATION`) and refreshes case responses. |
| `submitInformationResponse(caseToken, requestedInformation)` | Submits a response to an information request when status is `INFORMATION`. Updates cached status and refreshes case responses. |

### Current user — `session.users()`

| Method | Description |
|--------|-------------|
| `getCurrentUser()` | Fetches the authenticated user context from the API and stores it in SQLite. |
| `listStoredUsers()` | Returns the cached user context (at most one row per session database). |

### Webhooks — `session.webhooks()`

| Method | Description |
|--------|-------------|
| `listSubscriptions()` | Lists webhook subscriptions from the API and upserts them into SQLite. |
| `listStoredSubscriptions()` | Returns cached webhook subscriptions. |
| `createSubscription(callbackUrl, eventTypes)` | Creates a subscription on the API, persists the returned HMAC secret locally, then refreshes the stored list. |
| `deleteSubscription(id)` | Deletes a subscription on the API, then refreshes the stored list. |
| `listDeliveries(subscriptionId, page, size)` | Fetches delivery log entries from `GET /v1/webhooks/{id}/deliveries`. |
| `listStoredInboundEvents()` | Returns inbound webhook events stored locally by the receiver. |
| `receiveInbound(signatureHeader, rawBody)` | Verifies `X-Hub-Signature-256`, parses `WebhookPayload`, stores the event, and syncs collaboration request/notes into SQLite when authenticated. |

### Partners — `session.partners()`

| Method | Description |
|--------|-------------|
| `searchPartners(searchTerm)` | Keyword search via `GET /v1/partners/{searchTerm}`. Stores results locally (keyed by search term). |
| `searchPartnersSemantic(query, limit)` | Semantic search via `GET /v1/partners/search`. Stores results locally (keyed by query). |
| `listStoredPartners()` | Returns all cached partner search results. |
| `listStoredPartnersForSearchTerm(searchTerm)` | Returns cached results for one search term. |

### Attachments — `session.attachments()`

| Method | Description |
|--------|-------------|
| `getAttachmentConfig(caseToken)` | Fetches attachment configuration for a request and caches submitter/receiver company IDs. |
| `forwardAttachments(caseToken, description, files)` | Forwards one or more local files to the partner. Appends forward results to SQLite. |
| `analyzeHttpsAttachmentConfig(caseToken, requestBody)` | Proposes normalized HTTPS attachment settings without saving them. |
| `updateHttpsAttachmentConfig(caseToken, config)` | Updates HTTPS attachment configuration for a request and refreshes cached config. |
| `listStoredAttachmentConfigs()` | Returns all cached attachment configs. |
| `listStoredAttachmentConfigsForRequest(caseToken)` | Returns cached attachment config for one request. |
| `listStoredForwardResults()` | Returns the history of attachment forward operations. |
| `listStoredForwardResultsForRequest(caseToken)` | Returns forward results for one request. |

---

## Typical workflows

### Login and sync

```java
session.auth().login(username, password);
session.users().getCurrentUser();
session.collaborationRequests().syncAllDetails();
```

### Approve an inbound request

```java
var requests = session.collaborationRequests().listRequests();
var inbound = requests.stream()
    .filter(r -> r.receiveCompanyId().equals(myCompanyId))
    .findFirst()
    .orElseThrow();

session.caseResponses().approveRequest(
    inbound.token(),
    "CASE-001",
    "Engineer Name",
    "engineer@example.com",
    "+1-555-0100",
    "Next steps after approval"
);
```

### Close a collaboration request

```java
session.caseResponses().closeRequest(inbound.token());
```

### Review cached data offline

```java
session.collaborationRequests().listStoredRequests();
session.caseNotes().listStoredNotes();
session.caseResponses().listStoredResponses();
session.attachments().listStoredForwardResults();
```

### Search partners and create an outbound request

```java
var partners = session.partners().searchPartners("Beta");
var receiver = partners.get(0);
session.collaborationRequests().createRequest(
    receiver.companyId(),
    "CASE-001",
    "Problem summary",
    "Detailed description"
);
```

Semantic search:

```java
session.partners().searchPartnersSemantic("partners specializing in cloud infrastructure", 10);
```

---

## Integration with TSANet-integration-app

The console application (`TSANet-integration-app`) exposes CLI commands that call this library. Examples:

| CLI command | Library call |
|-------------|--------------|
| `login` | `auth().login()` |
| `requests` | `collaborationRequests().listRequests()` |
| `stored-requests` | `collaborationRequests().listStoredRequests()` |
| `create-request` | `createRequest(formTemplate, ...)` with `--field fieldId=value` |
| `form` / `forms show` | `getCreateFormByCompanyId()` / department / document / partner search |
| `forms list` / `stored-forms` | `listStoredForms()` |
| `approve-request` / `requests approve` | `caseResponses().approveRequest()` |
| `close-request` / `requests close` | `caseResponses().closeRequest()` |
| `reject-request` / `requests reject` | `caseResponses().rejectRequest()` |
| `request-information` / `requests info-request` | `caseResponses().submitInformationRequest()` |
| `respond-information` / `requests info-response` | `caseResponses().submitInformationResponse()` |
| `notes` | `caseNotes().listNotesForAllRequests()` |
| `notes list` / `notes-list` | `caseNotes().listNotesForRequest()` (chronological timeline for one request) |
| `notes add` / `add-note` | `caseNotes().createNote()` (prompts for text when omitted) |
| `responses` | `caseResponses().listResponsesForAllRequests()` |
| `sync` | `collaborationRequests().syncAllDetails()` |
| `me` | `users().getCurrentUser()` |
| `webhooks` / `webhooks list` | `webhooks().listSubscriptions()` |
| `webhooks create` / `create-webhook` | `webhooks().createSubscription()` |
| `webhooks delete --id ID` | `webhooks().deleteSubscription()` |
| `webhooks deliveries --id ID` | `webhooks().listDeliveries()` |
| `webhook-events` / `stored-webhook-events` | `webhooks().listStoredInboundEvents()` |
| `stored-webhooks` | `webhooks().listStoredSubscriptions()` |
| `partners` | `partners().searchPartners()` or `searchPartnersSemantic()` with `--semantic` |
| `stored-partners` | `partners().listStoredPartners()` |
| `attachments list/add/config/https-analyze/https-set` | `attachments()` facade methods |
| `add-attachment` | `attachments().forwardAttachments()` |
| `stored-attachments` | `attachments().listStoredForwardResults()` |

### Attachments

Read configuration:

```text
attachments config --id 123
attachments list --token abc-case-token-xyz --with-config
```

Forward files to a partner:

```text
attachments add --id 123 --description "Diagnostic logs" --file /tmp/logs.txt
add-attachment --token abc-case-token-xyz --description "Screenshot" --file ./screen.png
stored-attachments --id 123
```

Analyze and set HTTPS transport configuration:

```text
attachments https-analyze --id 123 --config-file ./https-input.json
attachments https-set --id 123 --config-file ./https-config.json
attachments https-set --id 123 --https-domain files.example.com --https-password secret --https-expiration 2026-12-31T23:59:59Z --https-path /uploads --https-port 443
```

Validation examples:

```text
Attachment description must not be empty.
At least one attachment file is required (--file PATH).
Attachment file does not exist: /tmp/missing.txt
HTTPS attachment path must start with '/'.
```

### Search partners and create a collaboration request

Keyword search, numbered results, and create with partner selection:

```text
api-login api-user secret
partners --search Beta
create-request --search Beta --partner-index 1 --case-number CASE-001 --summary "Issue" --description "Details"
```

Interactive selection when multiple partners match (console only):

```text
create-request --search Beta --case-number CASE-001 --summary "Issue" --description "Details"
Select partner (1-3): 2
```

Semantic search:

```text
partners --search "partners specializing in AWS networking" --semantic --limit 5
create-request --search "partners specializing in AWS networking" --semantic --partner-index 1 --case-number CASE-001 --summary "Issue" --description "Details"
```

Fetch and inspect form templates:

```text
forms show --company-id 2
forms show --department-id 20
forms show --document-id 100
forms show --search Beta --partner-index 1
stored-forms --company-id 2
```

Create with validated custom fields:

```text
create-request --company-id 2 --case-number CASE-001 --summary "Issue" --description "Details" --field 101=SN-12345
```

Form validation examples:

```text
Required custom fields are missing values: Serial Number
Custom field must use fieldId=value format: invalid
Provide --company-id ID, --department-id ID, --document-id ID, or --search TERM
```

Validation examples:

```text
No partners matched the search. Try a different --search value or use --semantic for natural language search.
Partner index 4 is out of range (1-3).
Provide --company-id ID or --search TERM to find a partner (optional --partner-index N).
```

### Inbound webhooks

Register a subscription (callback URL defaults to `tsanet.webhook.public-base-url` + `path`):

```java
session.webhooks().createSubscription(
    "http://localhost:8090/webhooks/tsanet",
    List.of("collaboration-request.created", "note.created")
);
```

When the bridge app receives a signed `WebhookPayload`, it stores the event and syncs the related collaboration request into SQLite so `stored-requests` / `requests` show inbound partner traffic.

### Reject a collaboration request (INFORMATION status)

```java
session.caseResponses().rejectRequest(
    request.token(),
    "Engineer Name",
    "engineer@example.com",
    "+1-555-0100",
    "Insufficient information provided"
);
```

### CLI usage examples

#### Close a collaboration request

```text
api-login api-user secret
requests close --id 123
requests close --token abc-case-token-xyz
close-request --id 123
close-request --token abc-case-token-xyz
```

Successful close prints the updated request (status becomes `CLOSED`):

```text
Closed collaboration request (1):
 - id=123 status=CLOSED token=abc-case-token-xyz submitCompanyId=1 receiveCompanyId=2 from=Acme to=Beta summary=...
```

Confirm the status in the live or cached list:

```text
requests
stored-requests
```

Re-closing the same request prints an informational message and does not call the API again:

```text
Request is already closed (status=CLOSED).
```

### Reject a collaboration request

Reject only works when the request is in `INFORMATION` status. The console prompts for a reason when `--reason` is omitted:

```text
api-login api-user secret
requests reject --id 123 --engineer-name "Engineer" --engineer-email engineer@example.com --engineer-phone "+1-555-0100"
reject-request --token abc-case-token-xyz --engineer-name "Engineer" --engineer-email engineer@example.com --reason "Insufficient details"
```

Successful reject prints updated status (`REJECTED`) and the reason sent:

```text
Rejected collaboration request (1):
 - id=123 status=REJECTED token=... summary=...
Rejection reason: Insufficient details
```

Invalid state examples:

```text
Request must be in INFORMATION status to reject (current status=OPEN).
Request is already rejected (status=REJECTED).
```

### Request additional information (OPEN / ACCEPTED)

Request information transitions the case to `INFORMATION` status:

```text
requests info-request --id 123 --engineer-name "Engineer" --engineer-email engineer@example.com --requested-information "Please provide serial number"
request-information --token abc-case-token-xyz --engineer-name "Engineer" --engineer-email engineer@example.com --engineer-phone "+1-555-0100" --requested-information "Please provide logs"
```

Successful request prints updated status and the information request response:

```text
Information requested on collaboration request (1):
 - id=123 status=INFORMATION token=... summary=...
Requested information: Please provide serial number
```

Validation examples:

```text
Case is already awaiting an information response (status=INFORMATION).
Information can only be requested on open cases (current status=REJECTED).
```

### Respond to an information request (INFORMATION status)

```text
requests info-response --id 123 --requested-information "Serial number is SN-12345"
respond-information --token abc-case-token-xyz --requested-information "Logs attached via partner portal"
```

Successful response prints updated status:

```text
Information response submitted for collaboration request (1):
 - id=123 status=ACCEPTED token=... summary=...
Information response: Serial number is SN-12345
```

Validation examples:

```text
Information response requires status INFORMATION (current status=OPEN).
```

### Add a note to a collaboration request

Interactive console (prompts for note text when `--text` / `--description` omitted):

```text
api-login api-user secret
notes add --id 123
notes add --token abc-case-token-xyz --text "Investigating on our side."
add-note --id 123 --summary "Update" --text "Customer confirmed the issue."
notes add --id 123 --text "Follow-up" --priority HIGH
```

After a successful post, the command prints a confirmation and the refreshed notes timeline:

```text
Note created: id=42 summary=Investigating on our side. priority=MEDIUM
Notes timeline for request id=123 token=... status=ACCEPTED
 1. [2026-01-01T12:00:00Z] engineer@example.com | Investigating on our side.
```

Validation examples:

```text
Note text must not be empty.
Note text exceeds maximum length of 5000 characters.
Cannot add notes to a closed request (status=CLOSED).
```

#### Inbound webhooks

Bridge app listens on `tsanet.webhook.port` (default 8090) at `tsanet.webhook.path`:

```text
api-login api-user secret
create-webhook
webhooks create --callback-url http://localhost:8090/webhooks/tsanet --events collaboration-request.created,note.created
webhooks list
webhooks delete --id 1
webhooks deliveries --id 1
webhook-events
stored-requests
```

See `TSANet-integration-app` for full CLI usage and flags.
