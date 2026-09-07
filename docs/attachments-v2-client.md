# Sending an attachment on the direct path (V2)

This page is for a member implementing the sender's side of the V2 attachment contract
(`tsanetgit/Connect-API-Code#147`), with or without the Connect SDK. The SDK's
`AttachmentsV2Facade` in `connect-library` is the reference implementation; every request
and response below is the shape that implementation sends and expects, taken from its test
double. The contract is a draft until it lands in the Connect OpenAPI spec; field names here
follow it exactly.

## The idea in one paragraph

The sender asks the platform for a **grant** for one file on one case. The platform resolves
the partner's delivery target and answers with an `upload` block: where to put the bytes and
how. The sender executes that block **verbatim**, never branching on who the partner is or
what store they use. Then the sender calls **complete** with what it uploaded; the platform
seals the upload where sealing is its job, verifies arrival where it can, records the
attachment, posts the case note and emits the webhook event. The file never passes through
the platform, so its size is bounded by the partner's store, not by the API's ingress.
**Abandon** exists for the case where the sender gives up after a grant; nothing ever becomes
visible from an abandoned or expired grant.

The sender reports the platform's recorded outcome, never its own evidence. An upload that
sent every byte without error is still whatever `complete` says it is.

## With the SDK

```java
TsaNetApiSession session = ...;                       // logged in as usual
AttachmentCompleteResult outcome = session.attachmentsV2().send(
    caseToken,
    Path.of("diag.tar.gz"),
    "application/gzip",
    "Diagnostics from the failing node",              // optional, goes into the case note
    true,                                             // compute and send a SHA-256
    progress -> log.info("{} {}/{} parts, {} of {} bytes",
        progress.mode(), progress.partsDone(), progress.partsTotal(),
        progress.bytesSent(), progress.bytesTotal()));

switch (outcome.status()) {
    case "DELIVERED"            -> ...;              // verified by the platform
    case "DELIVERED_UNVERIFIED" -> ...;              // sender-reported; the note says so
    case "FAILED", "EXPIRED"    -> ...;              // nothing was announced to the partner
}
```

`send` grants, uploads, completes; on any upload failure it abandons the grant and throws
`AttachmentV2Exception`; on a complete-time `attachment/grant-expired` it re-grants exactly
once and uploads again. The four calls are also available individually (`grant`, `upload`,
`complete`, `abandon`) for a client that drives the flow itself.

The SDK streams from disk one part at a time and retries individual parts (three attempts,
exponential backoff, on I/O failures, 5xx and 429). A `403` from a signed URL
that expired mid-upload is terminal: the SDK abandons the grant and throws, and the caller
decides whether to run `send` again.

## Without the SDK: the three calls

All three go to the Connect API with the usual bearer token. Errors are
`application/problem+json`; the `type` values are listed at the end.

### 1. Grant

```http
POST /v2/collaboration-requests/{token}/attachments/grants
Authorization: Bearer ...
Idempotency-Key: 3f1c...                           (optional; repeats return the same grant)
Content-Type: application/json

{"fileName": "diag.tar.gz", "contentType": "application/gzip",
 "sizeBytes": 734003200, "sha256": "9f86d0...", "description": "Diagnostics from the failing node"}
```

```http
201 Created
{"grantId": "3f2b7e6a-2c1d-4a4e-9b8f-0c1d2e3f4a5b", "fileName": "diag.tar.gz",
 "expiresAt": "2026-09-09T10:00:00Z",
 "receiver": {"companyId": 7, "targetKind": "s3", "maxSizeBytes": 5000000000},
 "upload": {"mode": "multipart", "method": "PUT",
            "headers": {"Content-Type": "application/octet-stream"},
            "parts": [{"partNumber": 1, "url": "https://...signed...", "sizeBytes": 104857600},
                      {"partNumber": 2, "url": "https://...signed...", "sizeBytes": 104857600},
                      ...]},
 "verification": {"mode": "platform"}}
```

Per the contract draft, expiry is 15 minutes for `single` and `relay` and 24 hours for
`multipart` and `resumable`, and `sizeBytes` is enforced by the signed URLs: a PUT of a
different length is refused.

### 2. Upload, exactly as instructed

Send the `headers` block on every request, unchanged, and add nothing else. Do not set
ACL headers. Each mode:

| `mode` | What to do | What to keep for `complete` |
|---|---|---|
| `single` | One `PUT url` with the whole file as the body, `Content-Length` = `sizeBytes`. | nothing |
| `multipart` | One `PUT parts[i].url` per part, in `partNumber` order, body = that part's byte range of the file (sizes are fixed by the platform). | each response's `ETag`, exactly as received (quotes included), with its `partNumber` |
| `resumable` | Sequential `PUT url` of chunks (the SDK uses 8 MiB, a multiple of 256 KiB) with `Content-Range: bytes start-end/total`; expect `308` with `Range: bytes=0-N` (N+1 bytes committed) until the last chunk answers `200`/`201`. A `308` without `Range` means nothing is committed: resend. After a lost connection, `PUT url` with `Content-Range: bytes */total` and an empty body asks the session where it stands. | nothing |
| `relay` | One streamed `PUT url` with the whole file; the `headers` block carries the relay's grant token. The relay pushes to the partner as the bytes arrive, so do not re-send after the relay has answered. | nothing |

Any non-2xx from a signed URL (other than the resumable `308`) means the upload failed:
abandon the grant (or let it expire) and start over with a new grant.

### 3. Complete

```http
POST /v2/collaboration-requests/{token}/attachments/grants/{grantId}/complete
Content-Type: application/json

{"sizeBytes": 734003200, "sha256": "9f86d0...",
 "parts": [{"partNumber": 1, "receipt": "\"a1b2...\""}, {"partNumber": 2, "receipt": "\"c3d4...\""}, ...]}
```

`parts` is present in `multipart` mode only. A `single` or `relay` complete carries just
`sizeBytes` (and `sha256` when one was granted).

```http
200 OK
{"grantId": "3f2b7e6a-...", "fileName": "diag.tar.gz", "status": "DELIVERED",
 "verification": {"method": "HEAD", "verifiedAt": "2026-09-08T10:12:31Z", "sizeMatched": true, "checksumMatched": true},
 "noteId": 501}
```

`status` is the platform's word: `DELIVERED` (verified), `DELIVERED_UNVERIFIED` (the platform
could not read the object back; the case note says arrival was not verified), `FAILED` or
`EXPIRED`. Complete is idempotent on `grantId`: a repeat returns the recorded outcome, so it
is safe to retry on a connection failure or a 5xx.

### Abandon

```http
DELETE /v2/collaboration-requests/{token}/attachments/grants/{grantId}
```

`204 No Content`. Any open upload session is aborted and nothing becomes visible.

## Problem types

| `type` | When | What to do |
|---|---|---|
| `attachment/receiver-not-configured` | the partner has no delivery target | nothing to send; tell the user |
| `attachment/receiver-config-invalid` | the partner's target is misconfigured | as above |
| `attachment/size-exceeds-receiver-limit` | larger than the partner accepts | as above; `receiver.maxSizeBytes` on a grant tells the limit |
| `attachment/grant-expired` | complete after expiry | re-grant once and upload again |
| `attachment/grant-already-completed` | complete repeated after a different outcome | read the recorded outcome |
| `attachment/upload-not-found` | complete before any bytes arrived | the upload did not happen; start over |
| `attachment/size-mismatch`, `attachment/checksum-mismatch` | the sealed object disagrees with the declaration | the file changed under you, or bytes were lost; start over |
| `attachment/relay-unavailable` | the relay host is down | try later |

## What never to do

- Never log or echo a signed URL, the `headers` block, or a grant token; they are credentials.
- Never announce the attachment to the partner yourself; the platform posts the case note after verifying.
- Never treat your own upload success as delivery. Read `status`.
