# Connect_SDK

Demonstration of Integration with TSANet API - In Java.

Modules: `connect-library` (the Java client for the TSANet Connect API),
`TSANet-integration-app` (console app), `TSANet-integration-demo` (demo scenarios).

## Building

The client code in `connect-library` is generated from the Connect API's OpenAPI
specification at build time, and the build reads that specification from a **sibling
clone** of `tsanetgit/Connect-API-Code` (the `connect.openapi.spec` property in the
root pom resolves to `../Connect-API-Code/connect-core-api/src/main/resources/openapi.yaml`).
A clone of this repository alone does not build.

The working steps, in order:

1. Clone `tsanetgit/Connect-API-Code` beside this repository (the directory must be
   named `Connect-API-Code`) and check out the `beta` branch. The generated symbols
   depend on which specification the sibling provides, so the branch matters.
2. In this repository, check out `main` (see the branch notes below).
3. `mvn install` from the root, or `mvn -pl connect-library -am install` for the
   library alone. JDK 21.

## Branch notes: what builds today

- **`main` builds.** `tsanetgit/Connect_SDK#44` merged the `oauth` branch on
  2026-08-11 (commit `66f44fe`). `main` builds against the sibling `beta`
  specification and passes the full suite (verified 2026-08-11: `mvn install`,
  JDK 21, 122 tests, 0 failures). Build from `main`; no commit pin is needed.
- **Historical: the Connect Gateway `v0.1.0` certification pin.** Gateway
  `v0.1.0` certified against `oauth` at `0f57facd3326`, from before the merge.
  The pin is the record of what was certified, not the commit to build today.
  The `oauth` branch itself remains only as history; do not build or consume
  from it.
- `v0.1.0` is released; `connect-library 0.1.0` is published to GitHub Packages
  (`tsanetgit/Connect_SDK#43`).

An alternative to the sibling clone, for consumers who need a hermetic build, is
vendoring the specification into the repository and pointing `connect.openapi.spec`
at the vendored file (the pattern the Fin adapter uses). That is a maintainer
decision and is deliberately not part of this document.
