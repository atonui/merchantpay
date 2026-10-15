# MerchantPay — Architecture Baseline (Draft v0.1)

## Scope
Native Android (Kotlin/Compose) merchant app; FastAPI orchestration service; PostgreSQL transactions, audit events and reconciliation; Safaricom M-Pesa sandbox adapter; simulated card adapter until a certified SoftPOS SDK is available.

## Principle: orchestrator, not processor
Our application will not directly process EMV cards or handle PAN/PIN/CVV. Sensitive NFC card payment acceptance belongs to a certified mobile payment solution with its own approval and runtime requirements. No funds custody by MerchantPay under the proposed model; legal status still requires assessment.

## Boundaries
1. **Android (untrusted client):** UI, merchant session, transaction intent, presentation of provider-controlled payment UI. Never authority on payment success.
2. **API trust boundary:** authenticated merchant authorization, validation, idempotency, request-rate controls.
3. **Payment domain:** durable intents and state transition rules, immutable provider-event journal, reconciliation queue.
4. **External providers:** M-Pesa or acquiring/SoftPOS SDK; callbacks and SDK results must be authenticated/verified per documented provider capabilities; a device callback alone is not proof of final settlement.
5. **Data boundary:** PostgreSQL ACID transactions, constraints, tenant separation, minimal sensitive data and audit retention policy.

## Non-negotiable invariants
- Currency and amounts in integer minor units with explicitly recorded ISO currency.
- A repeated request with the same merchant+operation+idempotency key and same parameters returns the same logical intent; changed parameters must be rejected.
- Provider timeouts produce **unknown/pending reconciliation**, never an assumption of decline.
- Paid, refunded, disputed, and settled are separate concepts; a flat single status cannot represent every dimension.
- Unique provider references must be database-enforced within provider/account scope.
- Internal success requires corroborated authoritative provider evidence; reconciliation detects and resolves divergence.
- Side-effect calls to external providers are not atomic with our database; use durable command/outbox-style patterns and provider-supported deduplication or status inquiry. Never promise exactly-once network delivery.

## Suggested modules
`android/`, `backend/api/`, `backend/domain/`, `backend/providers/{mock,mpesa,softpos}/`, `backend/reconciliation/`, `backend/audit/`, `tests/`.

## Decisions deferred
Merchant-of-record and settlement responsibilities; acquiring contracts; callback verification specifics; refund and dispute ownership; precise data retention and audit policy; certification scope. Do not invent these.
