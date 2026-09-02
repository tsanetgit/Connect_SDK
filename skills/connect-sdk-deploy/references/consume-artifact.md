# Consuming connect-library from GitHub Packages

The path for a member embedding the SDK in their own Java tooling. No source build, no
access to the private spec repository.

## Coordinates

`com.tsanet:connect-library`, published to
`https://maven.pkg.github.com/tsanetgit/Connect_SDK`.

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

Three versions are published: 0.1.0, 0.2.0, and 1.0.0. **1.0.0 requires Jackson 3**
(`tools.jackson.core:jackson-databind`) and is built on the Spring Boot 4.1 line; a
service still on Jackson 2 or Spring Boot 3 should take 0.2.0, which has the same
facade surface on the older runtime. The library works outside Spring Boot; from 1.0.0
`java.time` support comes from Jackson 3's databind itself, so no `jsr310` module is
declared or needed.

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
`caseResponses()`, `users()`, `webhooks()`, `partners()`, `attachments()`. Remote calls
before a successful login throw `IllegalStateException: Not logged in`.

Every remote read and successful write upserts into the SQLite cache;
`listStored*` methods read the cache without touching the network. `logout()` clears
the in-memory token only, never the cache.

## Auth modes

Two modes, selected by configuration:

- **`connect1-password`**: username and password, the mode shown above. Typical for
  evaluation and non-production accounts.
- **`client-credentials`**: OAuth 2.0 machine-to-machine via Microsoft Entra, the mode
  for production application accounts. The library refreshes tokens proactively before
  expiry. TSANet provisions the tenant, client ID, and audience values with the
  application account.

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
- Known deprecation: the always-test create signatures (pre-0.1.0 behavior) are still
  present in 1.0.0 as `@Deprecated(forRemoval = true)` shims; migrate callers to the
  explicit per-call `testSubmission` flag before they are removed.
