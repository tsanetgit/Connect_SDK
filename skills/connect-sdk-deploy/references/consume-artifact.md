# Consuming connect-library from GitHub Packages

The path for a member embedding the SDK in their own Java tooling. No source build, no
access to the private spec repository.

## Coordinates

`com.tsanet:connect-library`, published to
`https://maven.pkg.github.com/tsanetgit/Connect_SDK`.

`com.tsanet:attachment-receiver`, same repository and same version from the first release
that carries it: the receive side's `AttachmentStorage` SPI, the S3, Azure Files, Azure
Blob and GCS adapters, the tenant config model and the go-live verifier. A plain library
jar with no main class; bring your own runtime. Its intended consumer is the gateway's
attachments-only profile (`tsanetgit/Connect_Gateway`); a member deploying its own receiver
is the other. It does not depend on `connect-library`. Taking it means taking all four cloud
SDKs at once: about 170 runtime artifacts, including AWS SDK, Azure, Google Cloud, Netty,
gRPC and OpenTelemetry, because the storage factory links every adapter. A per-provider
split is a follow-up, not something the first release offers.

`com.tsanet:connect-e2ee`, same repository and same version from the first release that
carries it: optional end-to-end encryption of cases and notes to the `tsanet-e2ee-1`
profile. It depends on `connect-library` and on `com.nimbusds:nimbus-jose-jwt`; a member
that doesn't use E2EE leaves it out and gets neither. Setup (key operations, pinned partner
JWKS, the replay window) is in `connect-e2ee/README.md` in the repository.

Always point people at the **latest release** for the current version number:
<https://github.com/tsanetgit/Connect_SDK/releases/latest>. Do not hardcode a version
into docs you write for them; quote the coordinates pattern and let the release page
supply the number.

## One-time token setup

GitHub Packages requires authentication for Maven downloads even on public packages.
Any GitHub account works; the token needs only the `read:packages` scope.

`~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username><GITHUB_USERNAME></username>
      <password><PAT_WITH_READ_PACKAGES></password>
    </server>
  </servers>
</settings>
```

Consumer `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/tsanetgit/Connect_SDK</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.tsanet</groupId>
  <artifactId>connect-library</artifactId>
  <version><LATEST_RELEASE_VERSION></version>
</dependency>
```

The `<id>github</id>` in the pom must match the `<id>` in settings.xml, or Maven will
not send credentials and you get an opaque 401. The parent pom
(`tsanet-client-parent`) is published alongside the library and resolves from the same
repository.

Only the current release is published and supported (see
<https://github.com/tsanetgit/Connect_SDK/releases/latest>); earlier releases are
obsolete, are not offered to members, and there are no external deployments to keep them
for. Never point a member at an older version. The current release **requires Jackson 3**
(`tools.jackson.core:jackson-databind`) and the Spring Boot 4.1 line; a service still on
Jackson 2 or Spring Boot 3 has to move to that runtime before it can take the library.
What changed in each release, including how a failure looks, is under Upgrading. The
library works outside Spring Boot; from 1.0.0 `java.time` support comes from Jackson 3's
databind itself, so no `jsr310` module is declared or needed.

## Opening a session

```java
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;

TsaNetApiSession session = TsaNetApi.initialize(
    TsaNetApiConfiguration.of(
        "<CONNECT_API_BASE_URL>",                 // TSANet provides per environment
        System.getProperty("user.home") + "/.tsanet/data.db",  // local SQLite cache
        "<MEMBER_USERNAME>",
        "<MEMBER_PASSWORD>"
    )
);
session.auth().login("<MEMBER_USERNAME>", "<MEMBER_PASSWORD>");
```

Facades hang off the session: `auth()`, `collaborationRequests()`, `caseNotes()`,
`caseResponses()`, `users()`, `webhooks()`, `partners()`, `attachments()`, `attachmentsV2()` (the direct-delivery attachment client; see `docs/attachments-v2-client.md`). Remote calls
before a successful login throw `IllegalStateException: Not logged in`.

Every login ends with a `/v1/me` call; the answer is kept as `auth().currentUserContext()`
(company and user), and a failure there fails the login. Failures the API answers, and
transport failures, arrive as the unchecked `ConnectApiException`: `kind()` is `PROBLEM`,
`LEGACY`, `CONNECTIVITY` or `OTHER`, with the API's own `status()`, `title()`, `detail()`
and `isProblem("case-update-error")`-style checks. Its message never carries the URL, a
header or a token. The library asks for problem details, so a failed `/v1/` call carries the
documented status and a `type`. Handle by kind, status and `isProblem(...)`, not by parsing
the message (see "Errors" in `connect-library/README.md`).

Every remote read and successful write upserts into the SQLite cache;
`listStored*` methods read the cache without touching the network. `logout()` clears
the in-memory token only, never the cache.

## Auth modes

Two modes, selected by configuration:

- **`connect1-password`**: username and password, the mode shown above. Typical for
  evaluation and non-production accounts.
- **`client-credentials`**: OAuth 2.0 machine-to-machine via Microsoft Entra, the mode
  for production application accounts. TSANet provisions the tenant, client ID, and
  audience values with the application account.

In both modes, when the credentials are configured, the library renews the token before
expiry and once after a 401 mid-call, re-sending the request; concurrent callers share one
renewal. A password typed into `login(username, password)` is never retained, so that
session is not renewed silently: on expiry the next call fails with a classified 401 and the
caller logs in again.

Client-credentials, programmatic:

```java
import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountConfigMapper;

ApplicationUserAccount account = ApplicationUserAccountConfigMapper.clientCredentialsAccount(
    "production",
    System.getProperty("user.home") + "/.tsanet/production.db",
    "<ENTRA_TENANT_ID>",
    null,
    "<ENTRA_CLIENT_ID>",
    System.getenv("TSANET_CLIENT_SECRET"),
    "<API_AUDIENCE>",       // e.g. api://... value provided by TSANet
    null
);

TsaNetApiSession session = TsaNetApi.sessionFactory(
    TsaNetApiConnectionSettings.of("<CONNECT_API_BASE_URL>", account.sqlitePath())
).openSessionForApplicationUser(account);

session.auth().authenticate();
```

Spring Boot, in `application.yml`:

```yaml
tsanet:
  accounts:
    - id: production
      sqlite-path: "${user.home}/.tsanet/production.db"
      auth:
        type: client-credentials
        tenant-id: "<ENTRA_TENANT_ID>"
        client-id: "<ENTRA_CLIENT_ID>"
        client-secret: "${TSANET_CLIENT_SECRET}"
        audience: "<API_AUDIENCE>"
    - id: evaluation
      sqlite-path: "${user.home}/.tsanet/eval.db"
      auth:
        type: connect1-password
        username: "<MEMBER_USERNAME>"
        password: "<MEMBER_PASSWORD>"
```

Secrets always arrive via environment variables, never literals in the yml.

## Multiple accounts or environments

Use the session factory so each account gets an isolated SQLite file and bearer token:

```java
TsaNetApiSessionFactory factory = TsaNetApi.sessionFactory(
    TsaNetApiConnectionSettings.of("<CONNECT_API_BASE_URL>", "/path/to/data.db")
);
TsaNetApiSession a = factory.openSession("acme", "<USER_A>", "<PASSWORD_A>");
TsaNetApiSession b = factory.openSessionForAccount("<USER_B_EMAIL>", "<PASSWORD_B>");
```

Session labels map to per-label database files (`data-acme.db`, ...), so two
environments or two member companies never share a cache.

## The operations members actually start with

```java
session.users().getCurrentUser();                       // who am I
session.collaborationRequests().syncAllDetails();       // pull everything into SQLite
var partners = session.partners().searchPartners("<PARTNER_NAME>");
session.collaborationRequests().createRequest(
    partners.get(0).companyId(), "<YOUR_CASE_NUMBER>",
    "Problem summary", "Detailed description",
    true);   // testSubmission: explicit per call; false pages a real partner engineer
session.caseResponses().approveRequest(token, "<CASE_NUMBER>", "<ENGINEER_NAME>",
    "<ENGINEER_EMAIL_ON_REGISTERED_DOMAIN>", "<PHONE>", "Next steps");
```

The full facade reference, the CLI command table, and lifecycle validation examples
live in `connect-library/README.md` in the repository. Read it when the member's
question goes past setup and into API usage. Remember the lifecycle rules are the
business contract: reject requires `INFORMATION` status, notes are refused on closed
cases, and so on. For those semantics defer to TSANet's Connect documentation or the
tsanet-connect skill rather than inferring from method signatures.

## Upgrading

Watch the release notes on each release.

- **0.2.0** added `CacheRetention.sweep(JdbcTemplate, Duration, Instant)`: age-based
  eviction of closed and rejected cases from the SQLite cache. Open cases are never
  evicted; attachment forward results are never touched. Nothing runs it for you;
  call it from your own schedule if the cache should not grow forever.
- **1.0.0** moved to Spring Boot 4.1 and Jackson 3. Facade signatures are unchanged.
  Breaking for consumers: Jackson 3 is required; Jackson 3 parse exceptions are
  unchecked, so a `catch (IOException)` around a mapper call compiles but stops
  catching malformed JSON; `FAIL_ON_NULL_FOR_PRIMITIVES` is on by default in your own
  mappers (the library's cache reader keeps the old behavior); the `JsonNullable`
  accessors on `CaseApprovalUpdateDTO` and `CollaborationRequestSubmitterUpdateDTO`
  are gone (plain accessors unchanged).
- **2.0.0** is a major for four reasons; the runtime line (Spring Boot 4.1, Jackson 3,
  JDK 21) is unchanged from 1.0.0.
  - Every failure the API answers, and every transport failure, is thrown as
    `com.tsanet.api.ConnectApiException` (unchecked, not a Spring `RestClientException`):
    `kind()` is `PROBLEM`, `LEGACY`, `CONNECTIVITY` or `OTHER`, with the API's own
    `status()`, `title()`, `detail()` and `isProblem(typeSuffix)`; the message never
    carries the URL, a header or a token. A `catch` of `HttpClientErrorException`,
    `RestClientResponseException` or `ResourceAccessException` around library calls
    compiles unchanged and no longer matches: sweep for it and classify by `kind()` and
    `status()` instead.
  - `TsaNetApiSession` gained `attachmentsV2()` and `AuthFacade` gained
    `currentUserContext()`. Callers are unaffected; an implementer or decorator of either
    interface must add them.
  - Every successful login ends with `/v1/me`; if that call fails, the login fails and the
    session stays logged out. Configured credentials renew transparently in both modes,
    before expiry and once after a 401. A password typed into `login(username, password)`
    is never retained, so that session is not renewed; its next call after expiry fails
    with a classified 401.
  - The always-test create signatures (pre-0.1.0 behavior, deprecated for removal since
    0.1.0) are removed: `createRequest` takes the explicit per-call `testSubmission` flag
    in both forms. Removing a `default` interface method breaks binary compatibility as
    well as source: a consumer that bumps the version without recompiling gets
    `NoSuchMethodError` at the call, not a compile error. Recompile against 2.0.0.
  - New, not breaking: the V2 direct-delivery attachment client (`attachmentsV2()`, built
    against a draft contract with no live endpoint yet) and the published
    `com.tsanet:attachment-receiver` at the same version (see Coordinates).
- **3.0.0** is a major for five reasons; the runtime line (Spring Boot 4.1, Jackson 3,
  JDK 21) is unchanged from 2.0.0. The V2 attachment client (`attachmentsV2()`) is rebuilt
  on the grant model in the Connect OpenAPI spec and its public types change incompatibly;
  `TsaNetApiSession` gains `attachmentStorage()`; `ApplicationUserAccount` and
  `TsaNetApiConfiguration` gain a record component; case notes take their types from the
  server, so `CaseNotesFacade` gains methods and `CaseNoteDto` gains components; and `/v1/`
  failures arrive as problem details instead of legacy bodies. V1 attachments and the other
  facades are unchanged.
  - Generation: the V2 APIs and their DTOs are generated from the spec, so the build needs a
    Connect-API-Code spec branch that has the Attachment Grants and Attachment Storage
    Config operations.
  - Facade: one method per Connect API call (`createGrant`, `getGrant`, `listGrants`,
    `singleUploadLink`, `s3PartLinks`, `azureBlockLinks`, `completeSingle`,
    `completeS3Multipart`, `completeAzureBlock`, `abandon`), plus `complete` for the grant's
    mode, `upload(caseToken, grant, file, listener)` and `send(caseToken, file, listener)`,
    which returns the completed `AttachmentGrant`. Removed: `grant`, the old `upload` and
    `complete` signatures, and the six-argument `send`.
  - Types: `AttachmentGrant` is the server's grant (a `long` grant id, `Status`,
    `UploadMode`, `UploadPlan`); new `UploadLink` and `AttachmentGrantPage`;
    `UploadReceipts` and `UploadProgress` carry an `UploadMode`. Removed:
    `AttachmentGrantRequest`, `AttachmentCompleteRequest` and `AttachmentCompleteResult`.
    The grant takes no content type, SHA-256 or description, and there is no
    `DELIVERED` / note-id outcome: read the grant that complete returns.
  - `AttachmentV2Exception`: `problemType()` and `isProblem` are replaced by `code()` and
    `is(code)`, with codes chosen by HTTP status (`attachment/upload-mismatch` for `422`,
    `attachment/provider-error` for `502`, `attachment/grant-terminal` for `409`,
    `attachment/invalid-request` for `400`, and others).
  - Two of the new codes aren't chosen by HTTP status. An interrupted thread sends nothing
    more in `send` and `upload`, whether the interrupt was already set when `send` started or
    arrived during a link call, an upload `PUT`, a complete or a retry wait. The code is
    usually `client/interrupted`, with `status()` 0, but an interrupt during a call that then
    fails and isn't retried (a link call; an upload `PUT` on its last attempt, or answered with
    a status that isn't retried; or a complete on its last attempt or with a failure that isn't
    retryable) surfaces that call's own code; the interrupt stays set either way. 2.0.0
    reported an interrupt during an upload as `client/upload-unreachable` and during complete
    as `client/connectivity`; both codes remain, for transport failures.
    `attachment/receiver-not-allowed` is the receiver allowlist's refusal (below): `status()`
    is 0 when the client refuses before any request, and `403` when the platform does.
  - Removed codes, and what a caller sees instead:
    - `attachment/grant-expired`: `attachment/grant-terminal` (`409`, the grant is abandoned
      or expired).
    - `attachment/grant-already-completed`: no error. Completing a completed grant returns
      it unchanged.
    - `attachment/upload-not-found` and `attachment/size-mismatch`:
      `attachment/upload-mismatch` (`422`, an object or part is missing or its size is wrong).
    - `attachment/checksum-mismatch`: nothing. The grant takes no SHA-256.
    - `attachment/receiver-not-configured`: `attachment/not-found` on create (`404`, which
      also means no such case).
    - `attachment/size-exceeds-receiver-limit`: nothing per receiver. The spec caps
      `expectedSizeBytes` at 5,000,000,000,000, and create answers a bad request with `400`,
      `attachment/invalid-request`.
  - `send` no longer re-grants when a grant expires before complete.
  - Receiver allowlist: `ApplicationUserAccount` and `TsaNetApiConfiguration` gain a
    `Set<Long> allowedReceiverCompanyIds` component, the companies the account may deliver
    V2 attachments to. A `null` or empty set means unrestricted; a set containing `null` is
    refused at construction. The 2.0.0 constructors, `of`, `forAccount` and the
    `ApplicationUserAccountConfigMapper` factories compile unchanged and leave it empty.
    Set it with `ApplicationUserAccount.withAllowedReceiverCompanyIds` or either record's
    canonical constructor. A record pattern over either type has one more component, so a
    2.0.0 `case ApplicationUserAccount(var id, var path, var auth)` stops compiling.
    `equals` and `hashCode` now include the list, so records that differ only in it aren't
    equal; records built with the 2.0.0 constructors all carry the empty set and compare as
    before. The console's own configuration binds `allowed-receiver-company-ids`, on an
    account entry beside `sqlite-path`, and passes it to `withAllowedReceiverCompanyIds`;
    the mapper doesn't read it, so another app passes its list the same way. With a list set,
    `createGrant` (and so `send`) refuses a case whose receiving company isn't on it with
    `attachment/receiver-not-allowed`, before any grant is requested. The platform's own
    sender allowlist answers `403`, which is reported as the same code. Details:
    `docs/attachments-v2-client.md`.
  - `TsaNetApiSession` gained `attachmentStorage()`, the receiving side of V2:
    `AttachmentStorageFacade` gets, registers and tests the company's S3 or Azure Blob
    storage, with the types `StorageConfig`, `StorageTarget` and `StorageTestResult`.
    Callers are unaffected; an implementer or decorator of `TsaNetApiSession` must add it.
    Details: "Receiver storage (V2)" in `connect-library/README.md`.
  - Note types come from the server. `CaseNotesFacade.listNoteTypes()` returns the types a
    caller may send, as `NoteTypeDto` (`code`, `displayName`), in the server's order from
    `GET /v1/note-types`. The server can add a type without an API release, so read the list
    at runtime rather than hard-coding codes, and match on `code`, never on `displayName`.
    `createNote(caseToken, summary, description, priority, type)` sends the code as given.
  - There is no default type. The four-argument `createNote` from 2.0.0 still compiles, now
    as a default method that passes a null type, and a null type creates a note with no type:
    neither the library nor the server picks one.
  - A code the server doesn't offer is refused with a `400`, which reaches the caller as a
    `ConnectApiException` with `status()` 400. It arrives as a `PROBLEM` body, type
    `invalid-note-type`, whose `detail()` names the allowed codes (see "Errors" in
    `connect-library/README.md`).
  - `CaseNotesFacade` gains `listNoteTypes()` and the five-argument `createNote`, both
    abstract. Callers are unaffected; an implementer or decorator of `CaseNotesFacade` must
    add them.
  - `CaseNoteDto` grows from 2.0.0's 14 components to 18: `type` (the code), `companyId`,
    `direction` (`OUTBOUND` or `INBOUND`, relative to the account that fetched the note) and
    `typeDisplayName` (for display only), each null when the API sent none. A null type is
    not a public note. The 2.0.0 14-argument constructor still compiles, deprecated, and
    leaves the four null. A 2.0.0 record pattern over `CaseNoteDto` has four fewer
    components, so it stops compiling. The SQLite cache adds the four `case_note` columns
    at startup; notes cached by 2.0.0 read them as null until they are fetched again.
  - `/v1/` failures are now `PROBLEM`. The library sends
    `Accept: application/json, application/problem+json` (tsanetgit/Connect_SDK#127); 2.0.0
    sent `application/json` alone, so the API answered every `/v1/` failure in its legacy
    mode. A failed `/v1/` call now carries the documented `4xx` status where 2.0.0 often saw
    a `500` (tsanetgit/Connect-API-Code#122), with `type()` and `title()` set, and
    `getMessage()` reads `HTTP <status> <title> (<type suffix>): <detail>` instead of
    `HTTP <status> (legacy error): <detail>`. Code that branches on `kind() == LEGACY`, on
    `status()`, or on the message text sees different values: sweep for it. A wrong password
    is a `PROBLEM` too: `HTTP 401 Authentication Failed (authentication-error)`.
  - Connect API calls go over the JDK's `java.net.http.HttpClient` instead of
    `HttpURLConnection`, which dropped the body of a 401 answer to a `POST`
    (tsanetgit/Connect_SDK#128). The JVM's default proxy selector and SSL context still
    apply, so `https.proxyHost` and `javax.net.ssl.*` settings carry over. Settings only
    `HttpURLConnection` reads do not: a default `Authenticator` (for an authenticating
    proxy), and `HttpsURLConnection`'s default socket factory and hostname verifier. A `GET`
    that is redirected is no longer followed: any `3xx` is `OTHER`, with the status and
    `unexpected redirect` in the message, never the `Location`. The OAuth client-credentials
    token call rides the same client (tsanetgit/Connect_SDK#131), so the same proxy and TLS
    notes apply to it. A refused token call keeps its status-only message; one that can't reach
    the token endpoint is `CONNECTIVITY`, `cannot reach the OAuth token endpoint (<class>)`, with
    no URL or tenant in it.
  - An interrupted call stops: `OTHER`, status `0`, `Connect API call: interrupted`, with
    the thread left interrupted (the V2 attachment calls report it as `client/interrupted`).
  - Idle connections are kept for reuse for up to 30 seconds on JDK 21, the JDK client's
    `jdk.httpclient.keepalive.timeout` default, a JVM-wide setting the library leaves alone.
    A `POST` sent on a pooled connection that something on the network path closed while it
    sat idle fails as `CONNECTIVITY`: the JDK client can retry a `GET` there, never a `POST`.
- **4.0.0** adds optional end-to-end encryption (`tsanetgit/Connect_SDK#140`); the runtime line
  (Spring Boot 4.1, Jackson 3, JDK 21) is unchanged from 3.0.0. Coming from 2.0.0, apply the
  3.0.0 entry first. Existing calls compile, and a consumer that bumps without recompiling still
  links. It is a major because of two source breaks: a record pattern over the two changed
  records, and an implementer or decorator of the two changed facades. Also check a `-Werror`
  build, code that compares or logs the changed records, and code that reads `customFields` or
  `encrypted` from the cache.
  - New artifact `com.tsanet:connect-e2ee`, same repository and version: encrypts and signs a
    case or note for a create, and verifies and decrypts one on a read, to the `tsanet-e2ee-1`
    profile. Only a connector that uses E2EE adds it; it brings `com.nimbusds:nimbus-jose-jwt`.
    Setup is in `connect-e2ee/README.md`.
  - `createRequest` (both forms) and `createNote` gain an overload taking `E2eeFields(manifest,
    encrypted)`, sent exactly as given. `E2eeFields` is in `connect-library`
    (`com.tsanet.api.connectapi.dto`), so adding the overloads doesn't need `connect-e2ee`. The
    old overloads are now interface defaults that forward to the new ones, so callers are
    unaffected, but **an implementer or decorator of `CollaborationRequestsFacade` or
    `CaseNotesFacade` must add the new abstract overloads and move its logic to them**. An
    override of the old overload alone no longer sees every create: an E2EE create calls the new
    one directly, so a decorator that forces `testSubmission` or audits creates would let it
    through untouched. Point interceptors (a pointcut on the exact old signature) and test stubs
    at the new overloads too. The library normally strips leading and trailing whitespace from a
    note's summary and text and from custom-field values (the form-template `createRequest`);
    with `encrypted` true it sends them exactly as given, and the note length limits don't apply,
    since the values are ciphertext.
  - `CollaborationRequestStatusDto` gains `manifest`, `encrypted`, `submitterCaseNumber`,
    `description` and `customFields` (`CaseCustomFieldDto(id, fieldName, value)`); `CaseNoteDto`
    gains `manifest` and `encrypted`. The earlier constructors are deprecated and still work
    (a build with `-Werror` on deprecation sees them). A record pattern over either type has
    the new components, so a 3.0.0 `case CaseNoteDto(...)` or `case CollaborationRequestStatusDto(...)`
    stops compiling.
    The records' `equals` and `hashCode` cover the new components, so a case or note cached
    before the upgrade doesn't equal a fresh read of it (the old row reads the new components as
    null; a fresh read has values, and `encrypted` reads `false` when the API leaves it out).
    `toString` and JSON serialization cover them too, so logging or forwarding a
    `CollaborationRequestStatusDto` now carries the case's description and custom-field answers,
    where 3.0.0 carried only its summary. For an unencrypted case that is plaintext customer
    content.
  - The SQLite cache gains the new columns on its first open, with no manual step. A case or
    note cached by 3.0.0 reads the new components as null ("not known") until it is fetched
    again, while a fresh read of a case with no answers reads `customFields` as empty. On stored
    reads, until every case and note has been fetched again, null-check `customFields` and test
    `Boolean.TRUE.equals(record.encrypted())` rather than unboxing `encrypted()`, which throws on
    a 3.0.0 row. A null `encrypted` means not known, never not encrypted. The cache keeps what
    the platform returned, ciphertext included for an encrypted case; `connect-e2ee` never
    writes decrypted text to it. It now holds each case's description and custom-field answers
    as well as its summary; run `CacheRetention.sweep` if closed and rejected cases shouldn't
    stay on disk (it never sweeps an open case).
- **5.0.0** reports a sender's keys being unavailable as their own `connect-e2ee` outcome
  (`tsanetgit/Connect_SDK#147`) and redacts sensitive values in SDK records' `toString()`
  (`tsanetgit/Connect_SDK#162`); the runtime line (Spring Boot 4.1, Jackson 3, JDK 21) is
  unchanged from 4.0.0. It is a major because of one source break: an exhaustive `switch` over
  `Verification`. A connector that doesn't use `connect-e2ee` needs no code change, but check
  anything that reads the changed records' `toString()`.
  - `Verification` gains a third permitted subtype, `KeysUnavailable`, returned when
    `PartnerKeys` throws `IOException` fetching the sending company's keys (at first, or on the
    refetch for an unknown `kid`). An exhaustive `switch` without the new case stops compiling,
    and a consumer that bumps without recompiling throws `MatchException` at the first
    unavailable-keys read. Retry the record rather than refuse it: it comes before the replay
    check, so no `jti` is used, and verifying it again, as `FIRST_RECEIPT` too, is checked
    afresh. Bound the retries: `PinnedJwksPartnerKeys` also throws for a company with no
    configured JWKS URL and for a JWKS it rejects, which a retry won't fix. Code that matched the
    4.0.0 `SENDER_KEY` reason "the sending company's public keys are unavailable" should switch on
    `KeysUnavailable`; that refusal is no longer produced.
  - Step 3's party checks (no receiving party; this member neither `iss` nor `aud`) now run
    before the key lookup, so such a record is refused at `CLAIMS` where 4.0.0 could refuse it
    at `SENDER_KEY` or `SIGNATURE`. Anything that audits or alerts by `step` sees the shift; the
    reason strings are unchanged.
  - `PinnedJwksPartnerKeys` throws `IllegalArgumentException` from its constructor for a JWKS
    URL with no host, for every entry in the map, where 4.0.0 failed only when that company was
    fetched.
  - SDK records print `<redacted>` in `toString()` for passwords, client and webhook secrets,
    authentication tokens, and case, note and request tokens (webhook raw payloads included);
    attachment parameters print their names only. `CaseNoteDto`, `CaseResponseDto`,
    `CollaborationRequestStatusDto` and `StoredAttachmentForwardResultDto` leave partner-written
    text and personal details out, so the 4.0.0 note above about logging a
    `CollaborationRequestStatusDto` no longer applies to `toString()`; it still applies to JSON
    serialization and to accessors, which are unchanged. The SDK's own exception and webhook sync
    messages no longer carry request tokens; rows that 4.0.0 wrote to `webhook_inbound_event`
    keep their old messages.
