# Troubleshooting

Symptom-first. Each entry: what you see, what it actually means, what to do.

## Build

**`cannot find symbol ... WebhooksApi` (or other generated classes missing)**
The spec sibling is on the wrong branch. The build generates the client from
`../Connect-API-Code/connect-core-api/src/main/resources/openapi.yaml`, and the
generated symbols track that branch. Fix:
`cd ../Connect-API-Code && git checkout beta`, then rebuild. The `develop` branch
currently breaks the build (V2 webhook APIs).

**Build fails immediately, complains it cannot read the OpenAPI spec**
There is no sibling checkout. Clone `tsanetgit/Connect-API-Code` (private; needs
access) beside the SDK checkout, directory named exactly `Connect-API-Code`, branch
`beta`. A Connect_SDK clone alone does not build; if the person cannot get spec
access, route them to the published artifact or hand them a built jar.

**Maven returns 401 downloading connect-library from GitHub Packages**
GitHub Packages authenticates every Maven download, public or not. Checklist:
a token with `read:packages`; a `<server>` entry in `~/.m2/settings.xml` whose
`<id>` exactly matches the repository `<id>` in the pom (conventionally `github`);
the repository URL `https://maven.pkg.github.com/tsanetgit/Connect_SDK`.

**JDK mismatch errors (release version, class file version)**
The build wants JDK 21. On macOS with Homebrew, `openjdk@21` is keg-only: export
`JAVA_HOME` to it and prepend `$JAVA_HOME/bin` to `PATH` before building or running.

## Runtime, demo

**Badge says: Auth failed: Connect API returned 500 — Error processing request**
Almost always wrong credentials. The Connect API's legacy error mode answers bad
logins with 500, not 401. Re-enter the username and password before suspecting the
platform.

**Everything authenticates but the dashboard is empty or case actions 403**
Authentication is not authorization. `/me` succeeds for any valid account, while
business endpoints require the API role. Have TSANet confirm the account is
API-enabled, and verify with a real case list rather than the badge.

**Collaboration request rejected on submit, complaint about the engineer email**
Platform business rule: the engineer email must be on the member company's
registered domain. Personal or off-domain addresses are rejected. Not in the API
schema; do not look for it there.

**Credentials keep disappearing on the hosted demo**
By design. The container filesystem is ephemeral (`TSANET_DEMO_DATA_DIR`, default
`/tmp/tsanet-demo`), so every redeploy, restart, or resume wipes saved credentials.
Re-enter them in Settings after each cycle. Locally, credentials persist in
`~/.tsanet-demo-ui/` until cleared.

**Hosted demo loads with no password prompt**
`TSANET_DEMO_AUTH_PASSWORD` is unset, so the gate is off and the demo is open to
anyone with the URL. Set `TSANET_DEMO_AUTH_USER` and `TSANET_DEMO_AUTH_PASSWORD` in
the host's configuration and redeploy immediately.

**Container exits or crashes on an amd64 host but ran fine locally on a Mac**
The image was built for arm64. Rebuild with `--platform linux/amd64`.

**Port 8090 already in use**
demo-ui and the console app's webhook bridge both default to 8090. Run one at a
time, or move the bridge (`tsanet.webhook.port`).

## Interpreting API errors generally

The API has two error personalities. By default (legacy mode) failures collapse into
`500 Error processing request`. Sending `Accept: application/problem+json` opts into
RFC 7807 structured errors with a usable `detail`. When debugging anything
API-side, turn that on first; it converts guessing into reading.

Test-mode note for anyone wiring their own client: you *write* `testSubmission` on
create but *read* the flag back as `testCase`. Filtering on the write-side name
silently treats every test case as real.

## When it is not in this file

Read the repo docs (`README.md`, `docs/RUNBOOK.md`, `docs/USER_GUIDE.md`,
`connect-library/README.md`) from the member's checkout; they are the source of
truth and updated with the code. If the behavior contradicts those docs, gather the
exact request, response, and app version, and raise it with TSANet rather than
patching around it.
