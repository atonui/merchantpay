# Simulated event journal and reconciliation v0.1

**Not a real provider integration.** Events are inserted only by a trusted test harness.
No public event ingestion endpoint exists and no cryptographic provider verification is claimed.

An event's provider-scoped identifier is unique, so exact duplicates do not grow the journal; conflicting reused IDs are rejected. Separate event IDs with contradictory reported outcomes produce a `conflicting_evidence` assessment that requires investigation. Assessments are snapshots, not final financial truth.

No operation in this module changes an intent from `created`, marks an order paid, or records settlement. The names `consistent_authorized` and `consistent_declined` mean **consistent unverified simulation messages**, not real bank authorization.

### Unresolved controls
Real provider authentication/signature verification, merchant account scoping, replay/time-window handling, provider reference consistency, out-of-order and late event semantics, reconciliation inquiry API, tamper-evident logs, operator approval controls, retention and alerting.

### Testing
Use the disposable Postgres database and `MERCHANTPAY_TEST_DSN`:
`python -m pytest -q backend/tests/test_reconciliation_db.py`.
