# Durable idempotency: sandbox experiment

This milestone persists **payment intents only**, not payment success or actual external charges. Never confuse the `created` state with a confirmed payment.

## Design
PostgreSQL enforces a unique tuple `(merchant_id, operation, idempotency_key)`. Simultaneous inserts use `INSERT ... ON CONFLICT DO NOTHING` followed by a read in READ COMMITTED isolation. A reused key with a different amount/currency raises `IdempotencyConflict`.

This addresses multiple application threads/processes sharing the database and persistence across application restarts. It does not produce exactly-once external payment effects.

## Limitations / next steps
- No external processor, credential handling, card SDK, funds movement, receipts or settlement.
- No authorization boundary: callers pass arbitrary merchant identifiers. Later API authentication must supply merchant scope server-side.
- No refund, cancellation, provider event verification, financial ledger or reconciliation yet.
- Idempotency key retention/expiry policy is undecided; deletion could permit later duplicate effects.
- Database migrations need a managed versioning system when the backend grows.
- Provider initiation requires a durable outbox/command queue, provider-side deduplication where supported, status queries and unknown-outcome reconciliation.
- No evidence of security certification or production readiness.

## Test locally
Create an isolated PostgreSQL database, configure `MERCHANTPAY_TEST_DSN`, then:
```sh
python -m pip install -r backend/requirements-dev.txt
python -m pytest -q backend/tests/test_payment_intents_db.py
```
CI supplies a disposable PostgreSQL container.
