# CLAUDE.md: Connect_SDK

The Java SDK for the TSANet Connect REST API, with the demos and tools built on it. Members consume `connect-library`, `attachment-receiver` and `connect-e2ee` from GitHub Packages and run `demo-ui`, so public APIs and docs here are a contract with them. This repo is public.

## Never

- **Never hand-edit generated code.** The API client (`com.tsanet.api.generated.*`) is generated at build time from the OpenAPI spec in `tsanetgit/Connect-API-Code`, which is the source of truth for endpoints, fields and enums. If the spec is wrong, change it there.
- **Never push to `main` or `develop`.** Work on a branch and open a draft PR.
- **Never put internal or member hostnames, account IDs, real member or case data, or credentials** in code, tests, docs, commit messages or PR text. Use placeholders. Keep your own values in `.env`, which git ignores.
- **Never let a secret reach a log or an exception message,** including a value from configuration that may be malformed.
- **Never use production.** Work against DEV or BETA with test cases only (`testSubmission: true` on create, read back as `testCase`).
- **Never set the project `<version>` in a `pom.xml`.** It comes from the release tag (`publish.yml`).
- **Never hand-edit `.security-audit.json`.**

## Build and test

Use **JDK 21**. A clone of this repo alone doesn't build: `connect.openapi.spec` reads the spec from a sibling clone named `Connect-API-Code`, and the generated symbols depend on its branch. Use `beta` for `main` and `develop` for `develop`. That repo is private, so building from source needs access to it, and CI fails on PRs from forks. A build error such as `cannot find symbol` in generated code usually means the sibling is on the wrong branch.

```bash
# Fast loop: one test class in one module (-am builds what it depends on)
mvn -pl connect-library -am test "-Dtest=<TestClass>" "-Dsurefire.failIfNoSpecifiedTests=false"

# What CI runs on every PR: the full reactor
mvn -B --no-transfer-progress verify

# CI's mechanical security audit
python3 -m unittest -v scripts/test_security_audit.py
python3 scripts/security-audit.py
```

`IntegrationScenariosIT` in `TSANet-integration-demo` is skipped unless `TSANET_RUN_SCENARIOS=true`. It writes to whichever environment its credentials point at, so run it only against DEV or BETA.

## Layout

- `connect-library`: the Java client, with facades over the generated API
- `connect-e2ee`: optional end-to-end encryption of cases and notes, over `connect-library`
- `attachment-receiver`: the streaming storage SPI and its cloud adapters
- `demo-ui`: the web demo over the SDK facades
- `TSANet-integration-app`: console app
- `TSANet-integration-demo`: demo scenarios
- `docs`: `USER_GUIDE.md` and `RUNBOOK.md` for the demo, plus hosting and client guides
- `skills/connect-sdk-deploy`: the skill that helps members deploy the SDK and demo
- `scripts`: the mechanical security audit

## Branches and PRs

- **Base branch:** `develop` for library work, `main` for changes only to `demo-ui`, the hosted demo or repo config. A change that lands on `main` alone is merged back into `develop`.
- **`develop` reaches `main`** through one promotion PR, which builds against `beta` and stays red until `beta` carries what it needs. Releases are cut from `main`.
- **Branch names:** `feat/<slug>`, `fix/<slug>`, `chore/<slug>`.
- **Closing keywords** (`Closes #N`) only fire on merges into `main`, the default branch. Issues worked on `develop` close on the promotion.

## Before you open a PR

- [ ] `mvn -B --no-transfer-progress verify` passes on JDK 21, with the sibling on the matching branch
- [ ] A change members can see is in the docs it affects: the module's `README.md`, `docs/USER_GUIDE.md`, `docs/RUNBOOK.md` and `skills/connect-sdk-deploy`
- [ ] Install instructions point to the latest release, not a version number
- [ ] The diff has no hostnames, account IDs, real data or credentials
- [ ] The PR is a draft, into the right base
