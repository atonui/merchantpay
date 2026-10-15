---
name: payment-security
description: Use for threat modeling, secure Android or backend changes, authN/authZ, secrets, payment callbacks, data access, or production security checks.
---
# MerchantPay Security Engineering

Read `../../docs/SECURITY-BASELINE.md` first.

## Workflow
1. Classify the data and identify trust boundaries and attackers.
2. Assess threats using STRIDE or equivalent, with impact and likelihood.
3. Link each high-risk threat to controls and executable evidence.
4. Verify provider documentation for event authentication and SDK integrity.
5. Review mobile, API, database, CI/CD and admin boundaries.
6. Run relevant automated scans and targeted adversarial tests; log limitations.
7. Escalate unresolved high/critical findings before release.

## Must test
IDOR/tenant isolation, privilege escalation, replay, forged events, session expiry, secret leakage, tampered app response, compromised device assumptions, denial of service and dependency compromise.

## Rules
Never put provider secret keys in Android code, commit real credentials, log sensitive payment data, treat model review as PCI certification, or bypass provider-approved card acceptance components.
