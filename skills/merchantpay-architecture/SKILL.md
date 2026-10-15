---
name: merchantpay-architecture
description: Use to plan or review MerchantPay subsystem boundaries, payment-provider adapters, data flows, deployment design, and architecture decision records.
---
# MerchantPay Architecture

Read `../../docs/ARCHITECTURE.md` first.

## Workflow
1. Identify users, assets, trust boundaries, regulatory assumptions and providers.
2. State invariants and external dependencies explicitly.
3. Draft data flow and one or more alternatives; describe trade-offs and failure modes.
4. Record decisions as ADRs with security, operations, reversibility and commercial implications.
5. Verify API and provider assumptions against current official documents, not memory.

## Guardrails
- Keep raw card data and PIN outside MerchantPay systems.
- No assumption that MPoC compliance transfers automatically from SDK to host app.
- Do not select a provider or claim authorization without written confirmation.
- Do not optimize away reconciliation, audit trails or tenant isolation.

## Output
Architecture change description, ADR, risks, acceptance criteria and specific tests.
