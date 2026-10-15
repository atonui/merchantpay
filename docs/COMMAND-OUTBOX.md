# Durable simulation command outbox (v0.1)

The payment intent and its command are committed **in the same PostgreSQL transaction**. The command uniqueness constraint (one command per intent) prevents duplicate recording across concurrent API workers.

A worker calls `claim_one` using `FOR UPDATE SKIP LOCKED`. This changes `queued` to `in_progress` durably before any eventual external side effect. The worker only runs a simulation; no provider or network payment call exists.

**Critical recovery rule:** if a worker disappears after claiming, the command remains `in_progress`. We deliberately do not automatically return it to `queued` because the future external provider may have authorized money movement before the worker crashed. In production, a reconciliation worker must query an authoritative provider status and use documented partner-supported idempotency before safely deciding to retry.

The `simulated_complete` state is **not** proof of authorization. An older intent without a command triggers an error rather than automatic backfill.

## Remaining before production
API authentication and tenant-bound authorization, actual provider contract and verified callbacks, reliable dispatch with provider side-effect IDs, reconciliation, retry policies, migrations framework, ledger, refunds, dispute handling, audit events, observability, security assessment and regulatory approvals.

## Tests
`python -m pytest -q backend/tests/test_payment_commands_db.py` with disposable PostgreSQL and `MERCHANTPAY_TEST_DSN`.
