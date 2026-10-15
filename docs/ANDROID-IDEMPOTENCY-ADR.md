# ADR-0004 — Durable Android intent-creation attempts

**Status:** Proposed. Sandbox-only. No network payment submission enabled in the app.

## Problem

A client can crash after an HTTP request is accepted but before it receives the response. If it generates a new idempotency key on restart, the backend can treat the new key as a new intent. A successful HTTP response also does **not** mean a payment was authorized, settled or received.

## Decision

Persist an immutable logical intent-creation attempt in local SQLite **before** making any future API call. The record is scoped to `(merchant_id, request_id)` where `request_id` is a canonical UUID representing one user-authorized logical operation. A newly generated idempotency key and exact positive KES minor-unit amount are written atomically on first preparation.

The same logical request ID always recovers the same key and amount. Changing its amount is an error; starting a genuinely new purchase requires an explicit new logical request. A separate unique constraint protects `(merchant_id, idempotency_key)`.

### State machine

| State | Meaning | Permitted transitions |
| --- | --- | --- |
| `prepared` | Durable local record exists; request **may** already have reached server after crash | `uncertain`, `acknowledged` |
| `uncertain` | Transport or response outcome unknown | `uncertain`, `acknowledged` |
| `acknowledged` | Backend returned the matching **created intent ID** | Terminal for this local operation |

`acknowledged` is **not paid**, authorized, captured, settled or reconciled. The API's `created` state is the only accepted success response in this phase.

A `DurablePaymentSubmission` coordinator persists first, then calls the existing `PaymentRepository`. It marks ambiguous outcomes uncertain. A subsequent **explicit** `retryExisting` uses the same key, amount and merchant ID; it cannot silently create a new record. There is no background retry loop. In-process calls through one coordinator are serialized, and server-side merchant-scoped idempotency remains the final concurrency protection across processes/devices.

### Recovery and integrity

- `unresolved(merchantId)` lists prepared/uncertain records without network traffic.
- On a crash after dispatch but before acknowledgement, the record remains `prepared`. Treat it as potentially sent.
- If the backend returns a mismatched merchant, amount, currency, status or intent ID, do not acknowledge.
- Once acknowledged, the server intent ID cannot be overwritten by a conflicting response.
- Journal records remain on local logout so unresolved work is not forgotten; **no request may be retried under a different authenticated merchant**.
- SQLite's uniqueness and transaction boundaries are tested with Robolectric; actual process-kill, power-loss, multi-process, database corruption and device-compromise testing remain outstanding.

## Security and operational limitations

The app still has **no INTERNET/NFC permission** and the coordinator is **not wired** to the launcher. The local database contains merchant identifiers, amounts and idempotency keys but **no tokens or cardholder data**. Android app isolation and `allowBackup=false` reduce exposure; they are not production at-rest encryption. A production implementation needs data-at-rest threat assessment, explicit schema migrations, retention and deletion policy, user-facing unresolved-attempt UX, merchant session binding, monitoring, and device integrity review. Do not wipe unresolved records on logout or install a silent retry worker.

Deleting the app's data, reinstalling, or switching devices destroys the local record. The server must therefore continue to enforce idempotency and provide authenticated reconciliation; local persistence alone cannot guarantee exactly-once effects. Provider idempotency and authoritative reconciliation are separate requirements.

## Verification

Android JVM tests use Robolectric and real SQLite to verify persistence across store reopen, same-key replay, amount conflicts, merchant scoping, acknowledgement immutability, malformed responses, network uncertainty, cancellation and concurrency. Android CI assembles the debug sandbox app. None of these tests demonstrate a real payment.

## Next gates

1. Verify the Android CI job and security review of the journal.
2. Select and configure the OIDC identity provider and reconcile the backend JWT contract.
3. Build a lifecycle-aware UI for choosing/recovering one logical request, tied to the authenticated merchant, then connect the API client behind secure login.
4. Add retention/migrations, encryption-at-rest decision, server-side intent lookup/reconciliation, and process-death instrumentation before any public beta.
