# MerchantPay Security Baseline — Draft

## Principal threats
- Merchant account takeover and unauthorized refunds
- Request replay or duplicate initiation
- Forged, duplicate, delayed or out-of-order callbacks
- Transaction/merchant IDOR (cross-tenant reads/writes)
- Android device compromise, overlay attacks, token theft
- Secret exposure in CI, log streams and mobile app bundles
- Provider outage and inconsistent payment state
- Insider alteration of merchant settlement details or financial records

## Controls to verify
- Server-side authentication, authorization and per-tenant scoping on every request
- Step-up authentication and dual control for sensitive administrative actions
- Verified provider event provenance based on actual contract; do not invent signatures
- Idempotency, uniqueness, transition validation and lock/concurrency tests
- Short-lived credentials; server-side secrets management; Android Keystore as applicable
- Encrypt in transit; minimize stored PII; redact logs; use data retention limits
- Immutable event history with tamper-evidence appropriate to risk
- Alerting, reconciliation reports, incident response and tested backup restores
- SDK integrity and permitted-device requirements as mandated by certified provider

## External assurance required before live payments
Applicable acquiring and regulatory approvals, PCI applicability determination, validated SDK arrangement, independent penetration test and production readiness review.

## Sources to keep current
PCI SSC MPoC, OWASP MASVS/MASTG, OWASP ASVS, Kenya National Payment System framework, Kenya Data Protection Act, provider-specific official guides. Record versions and access dates during implementation.
