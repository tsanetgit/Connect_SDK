# connect-e2ee

Optional end-to-end encryption for TSANet Connect cases and notes, to the `tsanet-e2ee-1` profile in the workgroup specification "Connect E2E Encryption" (Addenda B, C and D). The platform holds no keys: each member encrypts and signs at its own connector, and this module is that step for a connector built on `connect-library`.

`connect-library` only carries the two fields (`manifest`, `encrypted`) and the protected values; it does no cryptography. A consumer that doesn't add this module sees no change and gets no JOSE dependency. This module adds one: `com.nimbusds:nimbus-jose-jwt` 10.10, which has no required transitive dependencies.

## What is protected

- **Encrypted:** `problemSummary`, `problemDescription`, each custom-field value, and a note's `summary` and `description`. An empty string is encrypted; a null stays null and isn't listed in the manifest.
- **Readable:** everything else, including the custom fields' structure, routing fields, `adminNote`, `escalationInstructions`, `internalNotes` and `nextSteps`. The information request and response and a rejection's reason have no manifest field in the API, so they can't be encrypted: send sensitive detail as an encrypted note.

Each protected value is one JWE in general JSON serialization: AES-256-GCM, with the content key wrapped with RSA-OAEP-256 for the partner and for the sender. The manifest is a compact JWS (ES256, `typ` `tsanet-manifest+jws`). It binds the record (`iss` and `aud` company ids, `ref` the submitter's case number, `jti`, `iat`) and lists a SHA-256 digest of each protected value as transmitted.

## Setting it up

```java
KeyOperations keys = ...;            // this member's two private-key operations (below)
PartnerKeys partners = new PinnedJwksPartnerKeys(Map.of(
        "1042", URI.create("https://keys.member-a.example/.well-known/jwks.json"),   // this member
        "2087", URI.create("https://keys.member-b.example/.well-known/jwks.json")),  // each partner
    Duration.ofHours(1));
ReplayGuard replay = new InMemoryReplayGuard(maxAge, skew, Clock.systemUTC());
E2eeProfile e2ee = new E2eeProfile("1042", keys, partners, replay, Clock.systemUTC());
```

- **`KeyOperations`** is the member's two private-key operations: unwrap a content key (RSA-OAEP-256) and sign (ES256, returning the raw 64-byte `r || s`). Implement it against your key service (Azure Key Vault, AWS KMS, OCI Vault, an HSM) so the private keys never enter this process. `SoftwareKeyOperations` holds keys in memory, for tests and local use.
- **`PartnerKeys`** gives each company's public keys, this member's own included. `PinnedJwksPartnerKeys` fetches each company's JWKS from a URL pinned in your configuration (HTTPS; plain HTTP only to this machine, for tests), caches it, and refuses a set that holds a private key. Its constructor rejects URLs without a host (for example `https:keys.partner.example`) with `IllegalArgumentException`, before encryption or verification can fetch them. Publish your own with `Jwks.publicJson(...)`, which drops every private member. Keys are only ever taken from here, for the company the record says sent it; never from a manifest or a JWE.
- **Rotating keys:** publish only your current encryption key; a sender refuses a JWKS with two, since it couldn't tell which to use. The profile doesn't settle this yet; the refusal is provisional until the workgroup sets a rule. Keep the earlier ones in your key service, and have `KeyOperations.holdsEncryptionKey` accept them, so records encrypted before the rotation stay readable. Keep earlier signing keys published while records signed with them are still read, since a stored manifest is re-verified by its `kid`. When a manifest names a `kid` the cached set doesn't hold, the set is fetched again, at most once a minute per company.
- **`ReplayGuard`** is the first-receipt check: `iat` within the window, and `jti` not seen before. The window has no default; set it from the profile's guidance. `InMemoryReplayGuard` is single-process and forgets on restart. A deployment with several instances needs a shared, persistent implementation.

Which partners have E2EE turned on is the platform's to decide (Groups), and email notifications are off for them. This module encrypts for whatever partner you name.

## Sending

```java
EncryptedCase out = e2ee.encryptCase(
    new CasePlaintext(caseNumber, summary, description, customFieldValues, formTemplate.fields()), "2087");
session.collaborationRequests().createRequest(formTemplate, out.caseNumber(), out.summary(), out.description(),
    out.customFieldValues(), testSubmission, out.e2ee());

EncryptedNote note = e2ee.encryptNote(new NotePlaintext(summary, text), caseRecord);
session.caseNotes().createNote(caseToken, note.summary(), note.description(), priority, type, note.e2ee());
```

The platform can't check ciphertext, so `encryptCase` checks the plaintext first: required form fields have a value, and nothing exceeds the spec's limits (summary 500, description 10,000, custom value 5,000; a note's summary 500 and text 5,000). The form's `options` and `validationRules` aren't checked: the API doesn't document their format.

## Receiving

```java
Verification<DecryptedCase> result = e2ee.verifyCase(record, Receipt.FIRST_RECEIPT);
switch (result) {
    case Verified<DecryptedCase> ok -> handle(ok.content());
    case Refused<DecryptedCase> no -> audit(no.step(), no.field(), no.reason());   // never a value
}
```

Branch on the record's `encrypted` first: a partner without E2EE sends plaintext and no manifest, and verifying that record refuses it at `SIGNATURE`.

Verification runs the six checks of Addendum D §5 in order and returns the content only when all pass. A failure is a `Refused` that names the step (`SENDER_KEY`, `SIGNATURE`, `CLAIMS`, `FIRST_RECEIPT`, `FIELDS`, `DECRYPT`) and the field. There is no partial result and no plaintext fallback.

- **`Receipt.FIRST_RECEIPT`:** a webhook delivery, or the response to your own create. Use it on exactly one of those paths per record. Connect returns the stored manifest on every later read, so freshness and replay can only be checked once.
- **A first receipt refused for a reason on your side at `DECRYPT`** (your key service down, say): the replay check at step 4 has already used the record's `jti`, so a redelivery is refused as a replay, and the sender never learns of the failure. Recover by fetching the case and verifying it as `STORED_READ`. A refusal at steps 1 to 3 (`SENDER_KEY`, `SIGNATURE`, `CLAIMS`) comes before that check and uses nothing, so a redelivery is checked afresh.
- **`Receipt.STORED_READ`:** every later read. It checks the signature, the binding and the digests, not freshness.
- **Notes:** `verifyNote(note, caseRecord, receipt)` takes the case too, since it supplies the note's binding.

Results are detached. `connect-library`'s SQLite cache keeps what the platform returned, ciphertext and manifest, so a stored read verifies the same way as a wire read, and decrypted text is never written back to it. Every type that holds content keeps it out of `toString`.

While the API's sanitizer rewrites `"` as `&#34;` in `problemDescription` and note text (`tsanetgit/Connect-API-Code#214`), the module HTML-decodes a protected value that starts with `{&#34;` before checking it, as the Power App does. That code goes when #214 ships.

## Hosted deployments

The profile keeps each member's private keys at the member's edge. When TSANet hosts a member's connector, TSANet would hold those keys. Whether that's acceptable is the workgroup's decision; this module doesn't decide it.

## Tests

`src/test/resources/addendum-d-vectors-THROWAWAY-TEST-KEYS.json` is the Addendum D worked example, with the throwaway test keys it was generated with: two members with placeholder company ids (1042, 2087) and placeholder key ids. Never use these keys anywhere else. The tests check that both members decrypt the example, and that each step refuses a tampered record. They also cover the round trip of what this module encrypts.
