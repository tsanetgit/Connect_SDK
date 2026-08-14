# Porting connect-library to Python / JS / TS — scoping note

Not started. This is a separate follow-on from the demo UI, scoped here for later prioritization.

## What would need porting

`connect-library`'s public surface (`TsaNetApi`, `TsaNetApiSession`, and the eight facades: auth, collaborationRequests, caseNotes, caseResponses, users, webhooks, partners, attachments) wraps an OpenAPI-generated client over `connect-core-api/src/main/resources/openapi.yaml`. Each target language needs:

1. An OpenAPI-generated client from the same spec (openapi-generator supports Python, `typescript-fetch`/`typescript-axios`, and plain JS templates — no new codegen infrastructure needed, just a different `-g` target).
2. A thin facade layer re-implementing the same eight-facade shape, so parity with the Java client is easy to audit.
3. A local cache equivalent to the SQLite offline-review store (sqlite3 in Python, better-sqlite3 or sql.js in Node — both trivial).

## Suggested order

- **Python first** — matches the Anyscale/Pylon integration need already tracked (`tsanetgit/Pylon#15` per memory: Python port is the right call for Anyscale + community reference, since Pylon Custom Apps are externally hosted/language-agnostic). This has a real consumer, not just the demo.
- **TS/JS second** — mainly useful if the demo UI itself grows past static HTML/JS into a proper SPA, or for a future JS-based integration target. Lower urgency than Python right now.

## Not scoped yet

- ~~Where ports live~~ resolved by the demo-ui adoption (`tsanetgit/Connect_SDK#59`): they would live in this repo alongside the Java client, since a Python client is real deliverable IP for the Pylon integration, not demo scaffolding.
- Test-parity strategy (does the Karate contract suite in `connect-test-harness` get reused across languages, or does each port need its own contract tests).
