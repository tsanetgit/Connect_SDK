# Sending an attachment on the direct path (V2)

This page is for a member implementing the sender's side of V2 attachment delivery, with or
without the Connect SDK. The contract is the Connect OpenAPI spec: the operations tagged
**Attachment Grants**, under `/v2/collaboration-requests/{token}/attachments/grants`. The spec
marks them `x-stability-level: alpha`, so they can still change. The SDK's
`AttachmentsV2Facade` in `connect-library` is generated from that spec and is the reference
implementation of everything below.

## The idea in one paragraph

The sender asks the platform for a **grant** for one file on one case. The receiving
company's storage decides the grant's **mode** and, for uploads in parts, its **plan**: how
many parts and how big. A grant carries no upload links. The sender asks for **links** just
before it uses them, uploads the bytes straight into the receiver's storage, and then calls
**complete** for the grant's mode. The platform checks the upload against the grant and
finalizes it. The file never passes through the Connect API, so its size is bounded by the
receiver's storage, not by the API. **Abandon** is for a sender that gives up: nothing is
ever announced on the case for an abandoned grant.

Only the case's submitting company can create a grant, and the grant's receiver is always
the case's receiving company.

## With the SDK

```java
TsaNetApiSession session = ...;                       // logged in as usual
AttachmentGrant grant = session.attachmentsV2().send(
    caseToken,
    Path.of("diag.tar.gz"),
    progress -> log.info("{} {}/{} parts, {} of {} bytes",
        progress.mode().value(), progress.partsDone(), progress.partsTotal(),
        progress.bytesSent(), progress.bytesTotal()));

if (grant.completed()) {
    ...                                                // the platform completed the grant
}
```

What `send` does, from its javadoc:

<!-- sync: AttachmentsV2Facade.send. AttachmentsV2GuideSyncTest keeps these paragraphs equal to the javadoc's. -->

Create a grant, upload, complete. A failed upload or complete abandons the grant and rethrows,
except a 409, where the grant is already terminal. That includes a 422 from complete, although
the platform leaves that grant open for another upload: a caller who wants to upload again
drives `upload` and `complete` itself. Complete is retried on a 5xx answer (a 502 is the
documented one) or a lost response: completing an already-completed grant returns it unchanged.

When complete fails and abandon doesn't settle the grant, the grant is read once, and a grant
that reads completed is returned as delivered. An interrupted thread makes no more calls: it
doesn't start an abandon or a read, and an open grant expires on the platform. The code is
usually `client/interrupted`, but an interrupt during a complete call that isn't retried (the
last attempt, or a failure that isn't retryable) surfaces that call's own code; the interrupt
flag is set either way. So after the upload, `client/connectivity`, any failure with the
interrupt flag set, or an `attachment/api-error` for an answer this client couldn't read (a
client built against an older spec than the server runs) doesn't prove the file wasn't
delivered: read the grant before sending it again.

<!-- /sync -->

Every Connect API call is also on the facade on its own, for a client that drives the flow
itself: `createGrant`, `getGrant`, `listGrants`, `singleUploadLink`, `s3PartLinks`,
`azureBlockLinks`, `completeSingle`, `completeS3Multipart`, `completeAzureBlock`, `complete`
(the one for the grant's mode) and `abandon`. `upload` runs the upload for any supported
mode and never completes or abandons.

How the SDK uploads:

- It streams from disk one part at a time and never follows a redirect.
- It asks for links when it reaches a part that has none, for that part and the ones after
  it, at most 1,000 per call.
- Before each `PUT` it asks again for a link that expires within 60 seconds. A link that was
  just issued is used as it is.
- A `403` from the storage asks for that link again and retries. Some receivers return the
  same link every time: that is fine while the link is valid, and once it has expired the
  upload fails with `client/link-not-refreshable`.
- Each part gets three attempts, shared by every kind of retry: a `403` refresh, and the same
  link again after an I/O failure, a `429` or a `5xx`.

`AttachmentV2Exception.code()` says what failed:

| Code | Cause |
|---|---|
| `attachment/invalid-request` | 400: a link call named a part or block number outside the plan, or S3 receipts don't cover it |
| `attachment/forbidden` | 403: the caller's company isn't the case's sender, or the receiver isn't on the sender's allowlist |
| `attachment/not-found` | 404: no such case or grant, a receiver that has registered no storage configuration (on create), or a link or complete call that doesn't match the grant's mode |
| `attachment/grant-terminal` | 409: the grant is completed, abandoned or expired, so it can't take this call |
| `attachment/upload-mismatch` | 422: complete found the upload doesn't match the grant. The platform leaves the grant open; `send` abandons it |
| `attachment/provider-error` | 502: the receiver's storage provider failed; nothing changed, retry later |
| `attachment/api-error` | any other non-2xx answer from the Connect API, or an answer this client could not read |
| `client/upload-rejected` | the storage answered an upload `PUT` with a status this client doesn't retry, or kept failing |
| `client/upload-unreachable` | an upload `PUT` could not reach the storage after the retry budget |
| `client/link-not-refreshable` | a link is past its expiry and asking again returned the same link, so the upload can't go on |
| `client/unsupported-upload-mode` | the grant's mode is not one this client uploads (`gcsResumable`) |
| `client/precondition` | a client-side precondition failed: the file is empty, unreadable or not the size the grant expects; the grant's plan is missing or doesn't fit the file; a link doesn't fit the plan or isn't a usable request; or an answer is empty or missing a field this client needs |
| `client/connectivity` | the Connect API could not be reached, or its answer was lost |
| `client/interrupted` | the calling thread was interrupted; the interrupt is restored and nothing more is sent |

## Without the SDK

Every call goes to the Connect API with the usual bearer token. The spec defines the request
and response bodies; the examples below show their fields.

### 1. Create the grant

```http
POST /v2/collaboration-requests/{token}/attachments/grants
Authorization: Bearer ...
Content-Type: application/json

{"fileName": "diag.tar.gz", "expectedSizeBytes": 734003200}
```

```http
201 Created
{"grantId": 9001, "status": "open", "fileName": "diag.tar.gz",
 "expectedSizeBytes": 734003200, "createdAt": "...", "expiresAt": "...",
 "mode": "s3Multipart", "s3Multipart": {"totalParts": 140, "partSizeBytes": 5242880}}
```

`mode` is `single`, `s3Multipart` or `azureBlock`. The spec also lists `gcsResumable` and
documents it as not available in the current release. An `s3Multipart` grant carries
`s3Multipart.totalParts` and `partSizeBytes`; an `azureBlock` grant carries
`azureBlock.totalBlocks` and `blockSizeBytes`. Parts and blocks are numbered from 1; each is
the plan's size except the last, which holds the rest of the file.

The platform decides the plan; read it from the grant rather than working it out yourself.
With the platform's defaults, a file of up to 5 MiB gets a `single` upload. Above that, S3
parts are 5 MiB, or the file size divided by 10,000 if that is larger; Azure blocks are
8 MiB, or the file size divided by 50,000 if that is larger. So the 700 MiB file above
uploads as 140 parts of 5 MiB.

A `404` means the case wasn't found, or the receiving company hasn't registered a storage
configuration for V2 delivery; the `detail` says which ("Receiver has not registered a
storage configuration"). Until the receiver registers one, there is nothing to retry.

A `502` means the receiver's storage provider failed and no grant was created: retry later.

### 2. Get links, then upload

Ask for links just before you use them. Each link has a `url`, an `expiresAt` that is never
later than the grant's, and sometimes `headers` and `sizeBytes`. Asking again for the same
number signs a fresh link.

| Mode | Links | Upload |
|---|---|---|
| `single` | `POST .../grants/{grantId}/single/url`, no body | one `PUT url` with the whole file |
| `s3Multipart` | `POST .../grants/{grantId}/s3-multipart/parts` with `{"partNumbers": [1, 2, ...]}`, at most 1,000 numbers per call | one `PUT` per part with that part's bytes; keep each response's `ETag` exactly as received, quotes included |
| `azureBlock` | `POST .../grants/{grantId}/azure-block/blocks` with `{"blockNumbers": [1, 2, ...]}`, at most 1,000 numbers per call | one `PUT` per block with that block's bytes |

Send each link's `headers` unchanged and nothing else. Don't follow redirects. Send exactly
`sizeBytes` bytes where the link gives it. If a `PUT` gets `403`, the link has probably
expired: ask for that number again and retry.

### 3. Complete

| Mode | Call |
|---|---|
| `single` | `POST .../grants/{grantId}/single/complete`, no body |
| `s3Multipart` | `POST .../grants/{grantId}/s3-multipart/complete` with every part from 1 to `totalParts` exactly once |
| `azureBlock` | `POST .../grants/{grantId}/azure-block/complete`, no body; the platform commits the blocks in order |

```http
POST /v2/collaboration-requests/{token}/attachments/grants/9001/s3-multipart/complete
Content-Type: application/json

{"parts": [{"partNumber": 1, "etag": "\"a1b2...\""}, {"partNumber": 2, "etag": "\"c3d4...\""}]}
```

`200` returns the grant. Completing a grant that is already completed returns `200` with the
grant unchanged, so complete is safe to retry after a lost response. The other answers:

- `422`: the upload doesn't match the grant (a part is missing, a size is wrong, an ETag
  doesn't match). Nothing is finalized and the grant stays open: upload again and retry.
- `502`: the receiver's storage provider failed. The grant is unchanged: retry later.
- `409`: the grant is abandoned or expired.
- `400` (S3 only): the receipts don't cover every part exactly once, or one has no ETag.

### Abandon, read, list

```http
POST /v2/collaboration-requests/{token}/attachments/grants/{grantId}/abandon
```

`200` returns the abandoned grant; `409` means it was already terminal. Where the provider
supports it, the platform also cancels the unfinished upload. Nothing already written to the
receiver's storage is deleted.

`GET .../grants/{grantId}` reads one grant. `GET .../grants?page=0&size=20` lists the case's
grants a page at a time. A grant's `status` is `open`, `completed`, `abandoned` or
`expired`.

## What never to do

- Never log or echo a link's URL or its headers; they are credentials.
- Never announce the attachment to the partner yourself.
- Never treat your own upload success as delivery. Read the grant that complete returns.
