---
name: connect-sdk-deploy
description: >
  Help TSANet members (and anyone assisting them) deploy the TSANet Connect SDK and the
  SDK demo from tsanetgit/Connect_SDK. Covers adding connect-library to a member's own
  Java / Maven / Spring Boot tooling via GitHub Packages, building the SDK from source,
  running the demo-ui web demo or the console app locally, and hosting the demo in a
  container for stakeholders. Use this skill whenever someone mentions the Connect SDK,
  connect-library, the SDK demo or demo-ui, Java integration with the TSANet Connect API,
  consuming TSANet Maven artifacts, or deploying, hosting, or showing the demo, even if
  they never name the repository. For Connect REST API semantics (endpoints, case
  lifecycle rules) defer to the tsanet-connect skill; this skill owns getting the SDK and
  demo built, configured, and running.
---

# Deploying the Connect SDK and SDK demo

This skill gets a TSANet member from zero to a working Connect SDK integration or a
running SDK demo. The source of truth is the `tsanetgit/Connect_SDK` repository itself:
its root `README.md`, `docs/RUNBOOK.md`, `docs/USER_GUIDE.md`, and
`connect-library/README.md`. When you have a checkout available, read those files before
answering; this skill tells you what matters, where the traps are, and what has changed
recently, but the repo docs win on any conflict.

Facts below were verified against `main` on 2026-08-18. If months have passed, re-verify
the release version, branch names, and module list against the live repository.

## Repository map

| Module | What it is | Runnable? |
|---|---|---|
| `connect-library` | The Java client for the TSANet Connect API. Facade API over generated OpenAPI clients, with a local SQLite cache. This is "the SDK". | Library only |
| `TSANet-integration-app` | Console reference app exposing the library as CLI commands (`login`, `requests`, `notes add`, `webhooks create`, ...) | Spring Boot jar |
| `TSANet-integration-demo` | Scripted demo scenarios over the library | Spring Boot jar |
| `demo-ui` | The branded web demo: dashboard, partner search, dynamic process forms, full case lifecycle from the browser. This is "the SDK demo" most people mean. | Spring Boot jar, port 8090 |
| `attachment-receiver` | Attachment receive endpoint with pluggable storage (S3, Azure Files). Early stage; no standalone docs yet. | Spring Boot jar |

## Access model (read this first, it shapes everything)

- `tsanetgit/Connect_SDK` is **public**.
- The build generates client code from the Connect OpenAPI specification, which lives in
  `tsanetgit/Connect-API-Code`. That repository is **private**. A clone of Connect_SDK
  alone does not build.
- The released library is published to **GitHub Packages**, which requires a GitHub
  personal access token with `read:packages` for downloads, even though the package is
  public. Any GitHub account works; no tsanetgit org membership needed.

So there are two very different situations:

1. **Member consuming the SDK** (the common case): no source build needed. Pull
   `com.tsanet:connect-library` from GitHub Packages with any GitHub account.
   See `references/consume-artifact.md`.
2. **Anyone building from source** (required for the demo apps, or for unreleased
   changes): needs read access to the private `tsanetgit/Connect-API-Code` repository.
   Members who need this should ask their TSANet contact for access, or ask TSANet to
   hand them a built jar or container image instead.

## Choose the path

**"We want to call the Connect API from our own Java service"** →
`references/consume-artifact.md`. Maven coordinates, the GitHub Packages token dance,
auth configuration (OAuth client-credentials and password modes), and working code for
the common operations.

**"We want to run or host the demo"** → `references/demo-deploy.md`. Local build and
run, entering member credentials, the ngrok option for a one-off screen share, the
Docker image, and hosting with the mandatory auth gate.

**"The build or the app is misbehaving"** → `references/troubleshooting.md`. Symptom
to cause to fix, including the classic ones (wrong sibling branch, the 500-on-bad-login
error mode, GitHub Packages 401s).

## Building from source (the short version)

Prerequisites: JDK 21, Maven, and read access to `tsanetgit/Connect-API-Code`.

1. Clone both repositories side by side. The spec sibling **must** be named
   `Connect-API-Code` and **must** have the `beta` branch checked out. The generated
   symbols depend on which specification the sibling provides, so the branch matters:
   `develop` currently breaks the build (V2 webhook APIs).

   ```bash
   git clone https://github.com/tsanetgit/Connect_SDK.git
   git clone https://github.com/tsanetgit/Connect-API-Code.git
   cd Connect-API-Code && git checkout beta && cd ..
   ```

2. Build from the Connect_SDK root on `main`:

   ```bash
   mvn install                          # everything, with tests
   mvn -pl connect-library -am install  # library only
   ```

Module poms stay at `0.0.1-SNAPSHOT` by design; the release workflow stamps real
versions. Do not build from the historical `oauth` branch: it merged to `main` in
`tsanetgit/Connect_SDK#44` and remains only as history.

## Cross-cutting API gotchas

These bite regardless of path, and none of them are visible in the OpenAPI schema:

- **Bad credentials return HTTP 500, not 401.** The Connect API's legacy error mode
  answers a wrong username or password with
  `500 "Error processing request"`. Sending `Accept: application/problem+json` opts
  into structured RFC 7807 errors. Do not let a member burn an afternoon debugging
  their network for what is a typo in a password.
- **Test-mode asymmetry.** You *write* the `testSubmission` flag when creating a case
  but *read* it back as `testCase`. The library's create API takes an explicit
  per-call `testSubmission` flag with no silent default; older always-test method
  signatures are deprecated shims scheduled for removal in 0.2.0.
- **Engineer emails must be on the member's registered domain.** The platform rejects
  collaboration requests whose engineer email is off the member company's registered
  domain. This is a business rule, not a schema rule, and the error is not
  self-explanatory.
- **A successful `/me` proves authentication, not authorization.** Business endpoints
  require the API role on the account; `/v1/me` does not. A green "who am I" check
  can coexist with 403s on every case operation. Verify with a real case list, not
  with `/me`.

## Credential hygiene

Member credentials, tokens, and tenant IDs never go in git, in the workflow files, or
in anything shared. Keep real values in an untracked local file or environment
variables, and use placeholders like `<MEMBER_USERNAME>` in anything written down.
The demo persists credentials to `~/.tsanet-demo-ui/` (mode 600) on purpose; that
directory stays on the machine that entered them.
