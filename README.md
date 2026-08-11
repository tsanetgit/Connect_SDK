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
2. In this repository, check out the commit you are building (see the branch notes
   below).
3. `mvn install` from the root, or `mvn -pl connect-library -am install` for the
   library alone. JDK 21.

## Branch notes: what builds today

- **`oauth` at `0f57facd3326` builds against the beta specification and passes the
  live BETA contract suite.** This is the commit the Connect Gateway's `v0.1.0`
  certifies against, and the pin to use until `oauth` merges and a release is
  tagged.
- **`main` at `332e5c7` does not compile against the beta specification**:
  `ConnectApiWebhooksGateway` references generated symbols that specification does
  not produce (cannot-find-symbol at its webhook-gateway call sites), because that
  code tracks a newer specification than the sibling's `beta` branch carries. This
  is a known condition on the release path, not a local setup problem; do not
  debug your environment over it.

An alternative to the sibling clone, for consumers who need a hermetic build, is
vendoring the specification into the repository and pointing `connect.openapi.spec`
at the vendored file (the pattern the Fin adapter uses). That is a maintainer
decision and is deliberately not part of this document.
