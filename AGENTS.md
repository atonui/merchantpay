# MerchantPay Agent Operating Rules

## Scope
Research/sandbox only. Never initiate or claim authorization for live card or M-Pesa payments. No real merchant/customer data in fixtures. Never request or commit payment credentials, real card data, PINs, CVVs or secrets.

## Skill routing
- Architecture boundaries and change proposals: `skills/merchantpay-architecture/SKILL.md`
- Payment lifecycle, retries, callbacks, concurrency: `skills/payment-state-machine/SKILL.md`
- Authentication, threats, secure storage, logging: `skills/payment-security/SKILL.md`
When multiple apply, read all. Skills guide implementation; verified provider contracts and security standards take precedence over assumptions.

## Mandatory development workflow
1. Document affected trust boundaries and transaction invariants before editing payment-critical logic.
2. Make the smallest change possible with tests that cover failure and concurrency scenarios.
3. Use deterministic minor-unit amounts (integer cents), never binary floats for financial values.
4. Avoid logging PII, tokens, account numbers, card data or authorization credentials.
5. Never treat an ambiguous provider outcome as a failure; move to reconciliation.
6. Run tests and scanners; report commands, results and limitations honestly.
7. Require a separate review for changes to payment state, authentication, settlement, refunds and financial records.
8. Do not merge payment-critical code or deploy live payments without human approval and applicable external assurance.

## PR evidence checklist
Summary; affected boundaries; invariants; tests and command output; security considerations; migration/rollback; known risks; external approvals required.
