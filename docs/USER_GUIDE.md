# Connect SDK Demo — User Guide

How to drive the demo app itself. For build/start/stop mechanics see the
[Runbook](RUNBOOK.md); this guide assumes the server is running at
http://localhost:8090 (or a tunneled/hosted URL).

> **Status note.** Everything below reflects the app as built. A full case
> lifecycle round trip is runtime-verified against **DEV** (2026-07-13, case
> #3472): identity, dashboard with direction split, partner search, dynamic
> form rendering, **create → approve → close** played from both sides of the
> collaboration using the loadtest UAT account pair. Items still marked
> *unverified* have not run against live data on the noted environment.

## 1. What this demonstrates

The demo tells the member integration story end-to-end on top of
`connect-library` (the Connect SDK): a member's engineer authenticates,
finds a partner company, fills the partner's own process form, submits a
collaboration request, and works the case through its lifecycle — without
ever leaving their (simulated) tooling. It exists to show prospective
members what the SDK gives them out of the box.

## 2. The header badge

The badge in the top-right corner is the connection truth:

| Badge | Meaning |
|---|---|
| Green — "Company — email" | Authenticated; shows who BETA thinks you are |
| Amber — "Not configured" | No credentials saved yet (Settings tab) |
| Amber — "Auth failed: Connect API returned 500 — Error processing request" | BETA rejected the credentials. The API's legacy error mode returns 500 (not 401) for bad logins, so this is almost always a wrong username/password |

## 3. Settings — environments and credentials

The Settings tab shows one card per environment (**BETA** and **DEV**). Each
card holds that environment's credentials; the highlighted card is the
**active** environment, and the header chip always shows which one you're on.

1. Enter the member username and password on an environment's card →
   **Save Credentials**
2. **Make Active** switches the whole app to that environment (dashboard,
   partner search, everything repoints; the badge re-authenticates)
3. The active environment persists across restarts

Each environment keeps an isolated credentials file (mode 600) and its own
SQLite cache under `~/.tsanet-demo-ui/` (ephemeral `/tmp` in the container),
so BETA and DEV data never mix. Credentials are never committed, logged, or
returned by the API — `GET /api/settings` reports only the username.
**Clear** wipes that environment's file.

When the app is deployed with the Basic-auth gate enabled
(`TSANET_DEMO_AUTH_PASSWORD` set), the browser prompts for the gate
credentials before the page loads — that gate protects the demo itself and is
unrelated to the BETA member credentials entered inside it.

## 4. Dashboard — collaboration cases

Lists every case visible to your member account, newest activity first.

- **Direction chips**: *outbound* = submitted by your company; *inbound* =
  received from a partner. Computed by comparing each case's companies with
  the identity in the badge. The dropdown filters by direction.
- Click any row to open the case detail view.

## 5. New Collaboration — partner search and the dynamic form

The heart of the demo:

1. **Find a Partner** — search the member directory by name. Results show
   company, department, and (where routing dictates) a specific process form.
2. **Select** — the app fetches that partner's *configured process form* live
   from BETA and renders it: sections, required markers, and field types
   (text, dropdown, date, textarea) exactly as the partner defined them.
3. Fill the standard fields (internal case number, summary, description) plus
   the partner's custom fields, then **Submit Collaboration Request**.
4. On success you get the new case id + a link straight into the case detail.

*Verified on DEV (2026-07-13):* partner search, form rendering (sections,
required markers, text fields), and submit-through-to-created-case, all
confirmed live. *Still unverified:* dropdown-option formatting
(newline-delimited first, comma fallback) — no select-type field has been
seen live yet — and everything on BETA. If a field renders as the wrong
input type, that's a one-line mapping fix — report it.

## 6. Case detail — lifecycle, notes, attachments

Opens from the Dashboard or after creating a case.

- **Summary grid** — status, direction, both companies, timestamps, case token.
- **Actions** — contextual by direction:
  - *Inbound* (you are the receiver): **Approve** (with engineer contact +
    next steps), **Reject** (with reason), **Request Info**
  - *Outbound* (you submitted): **Respond to Info Request**, **Close Case**
  - Always: **Add Note** (summary, description, priority)
  
  The demo deliberately does not second-guess which actions the case's current
  state allows — the API is the authority, and its validation errors surface
  in the status line. *All lifecycle actions verified live on DEV (cases
  #3472 and #3474, 2026-07-13): approve, reject, request-info, respond-info,
  close, and add-note.*

  The state machine as observed live:

  ```
  OPEN ──approve──▶ ACCEPTED ──close──▶ CLOSED   (terminal)
   │
   ├─reject──▶ REJECTED                          (terminal — close is invalid)
   │
   └─request-info──▶ INFORMATION ──respond-info──▶ (stays INFORMATION;
        receiver must still approve/reject — close is invalid while pending)
  ```

  Invalid transitions come back as the legacy 500 with a meaningful message,
  e.g. `{"message":"INFORMATION cases cannot be closed."}` — that's the
  business contract enforcing itself, shown verbatim.
- **Notes Timeline** and **Response History** — the full conversation and
  every engineer response on the case. System-generated notes (e.g.
  "Case accepted.") arrive as HTML; the demo renders them formatted through a
  strict allowlist sanitizer, never as raw markup.
- **Attachments** — *Load Config* shows the case's attachment configuration;
  when the partner supports it, the upload form forwards files to the case.
  *(Unverified against live BETA.)*

Engineer emails in action forms must be on your member company's registered
domain — the API rejects others (business rule, not a demo bug).

## 7. Webhooks

Manage the member account's webhook subscriptions:

- List subscriptions with active state; **Deliveries** shows the recent
  delivery log per subscription (event, HTTP status, attempt, success).
- **New Subscription** — callback URL + comma-separated event types.
- **Delete** removes a subscription (confirmation prompt).

Note: inbound webhook *receiving* is not part of this demo — it lists and
manages subscriptions and shows TSANet's delivery attempts outward.

## 8. Troubleshooting

| Symptom | Cause / action |
|---|---|
| Everything says "BETA credentials not configured" | No credentials saved — Settings tab |
| Badge: "Auth failed … 500 Error processing request" | Wrong BETA credentials (legacy error mode — 500 means login rejected) |
| Actions fail with "Connect API returned 4xx/5xx — …" | Upstream validation: wrong case state, off-domain engineer email, or the legacy-500 quirk. The response body is shown verbatim |
| Case list loads but partner search errors | Partner search requires a valid session — re-check the badge first |
| Build fails: `cannot find symbol … WebhooksApi` | Sibling `Connect-API-Code` checkout is on the wrong branch — needs `beta` |

## 9. What the demo intentionally does not do

- No inbound webhook receiver (subscriptions management only)
- No multi-user/session handling — one shared SDK session per server instance
- No case assignment, SLA, or reporting views — outside the SDK's surface

## 10. References

- Upstream SDK: `tsanetgit/Connect_SDK` (branch `main`)
- Transport contract: `tsanetgit/Connect-API-Code`
  `connect-core-api/src/main/resources/openapi.yaml` (branch `beta`)
- Business rules (case lifecycle validity): TSANet Connect GitBook /
  Integration Guide — never inferred from the schema
