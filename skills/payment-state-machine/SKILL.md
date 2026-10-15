---
name: payment-state-machine
description: Use when implementing, testing, or reviewing payment initiation, authorization status, callbacks, retries, idempotency, refunds, and reconciliation.
---
# MerchantPay Payment State Machine

Read `../../docs/ARCHITECTURE.md` and `../../docs/EXPERIMENTS.md` first.

## Invariants
- Client requests never establish payment success.
- Same merchant, operation, key and payload resolve to one logical attempt; key reuse with a changed payload is an error.
- Enforce idempotency atomically in durable storage, not solely with an application-level if-check.
- Duplicate and out-of-order provider callbacks cannot revert confirmed terminal facts.
- Timeout means unknown/pending reconciliation until authoritative status is obtained.
- Distinguish payment state, refund state and settlement state.
- Do not reissue an unknown external charge without provider-approved reconciliation/deduplication.
- Use currency and integer minor units, deterministic arithmetic and scoped references.

## Workflow
1. Write transitions and invariants for the operation.
2. Identify all network and database failure windows.
3. Build transaction boundaries, uniqueness constraints and durable commands.
4. Write concurrency, timeout, replay, conflict and crash-recovery tests.
5. Prove the external side-effect contract is supported; otherwise mark test-only.
6. Obtain independent reviewer sign-off.

## Required cases
Concurrent identical requests, payload mismatch, duplicate callback, delayed confirmation, provider status disagreement, unknown outcome, database restart, merchant cross-tenant access.

## Never
Invent provider semantics; claim exactly-once network delivery; retry uncertain charges blindly; record success on UI tap or unverified callback.
