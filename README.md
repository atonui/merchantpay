# MerchantPay — engineering sandbox

**Research and simulation only.** MerchantPay does not currently accept, authorize, settle or refund real payments. There is no approved card-capture SDK or live M-Pesa integration.

MerchantPay is a proposed native Android merchant app with a FastAPI/PostgreSQL payment-orchestration backend. Any future SoftPOS integration must use an independently approved, certified provider SDK and acquiring arrangement; this repository does not establish regulatory or PCI approval.

## Repository map

- `android/` — Kotlin/Jetpack Compose **offline demo**; a separate HTTP API client exists but is **not connected** to the launcher.
- `backend/api.py` — sandbox-only authenticated, merchant-scoped FastAPI create/read endpoints.
- `backend/migrations/` — PostgreSQL schema for payment intents, commands, simulated events, reconciliation and merchant memberships.
- `backend/payment_commands.py` — durable command outbox, no real provider calls.
- `backend/reconciliation.py` — unverified simulated provider-event journal and assessment.
- `experiments/payment_race/` — intentionally vulnerable and corrected idempotency simulations.
- `docs/` — architecture, security baseline, auth, reconciliation and Android decisions.
- `skills/` and `AGENTS.md` — engineering skill packs and review rules.

## Developer tests

Python backend integration tests use a **disposable** PostgreSQL database configured through `MERCHANTPAY_TEST_DSN` and dependencies in `backend/requirements-dev.txt`. GitHub Actions supplies a fresh PostgreSQL service.

Android uses Gradle 8.9, Android SDK 35 and JDK 17:

```sh
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

A Gradle wrapper is not yet checked in. The Android app intentionally has no `INTERNET` or `NFC` permission and performs no backend calls.

**Do not run the deliberately failing vulnerable tests as a generic green test suite.** The `Payment simulation regression` workflow checks the safe sample and intentionally failing negative control separately.

## Next gates

1. Verify the Android build and unit tests on GitHub Actions.
2. Design and independently review mobile OIDC/PKCE authentication and secure session handling.
3. Preserve idempotency keys durably before wiring mobile create-intent requests to FastAPI.
4. Design verified provider integrations, payment-state transitions, ledger, reconciliation and compliance separately.

See `docs/ARCHITECTURE.md` and `docs/SECURITY-BASELINE.md` before proposing live payment features.
