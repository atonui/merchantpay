# Evaluating the MerchantPay Skills

## Objective
Test whether reading a skill improves *verified code quality*, not perceived explanation quality.

## Protocol
1. Freeze task prompt and baseline vulnerable code.
2. Give two isolated agents identical tasks and budgets; one gets relevant skill(s), the other does not.
3. Do not reveal hidden tests to either agent during implementation.
4. Run identical public and hidden tests; assess semantics, security, regression, maintainability and number of iterations.
5. Record model versions, input/output tokens, execution costs, hashes of prompts, and reviewer decisions.
6. Repeat on multiple seeded bugs before asserting an improvement. Change one variable at a time.

## Starter bug
`experiments/payment_race/vulnerable.py` models concurrent requests with the same idempotency key. Tests assert only one *external charge attempt*. The intentionally unsafe sample has a check-then-act race. This sample is deliberately not production payment code and contains no payment provider integration.

## Acceptance gate
Pass safety tests, pass review of transaction invariants, no undisclosed regressions. A green unit test does not constitute an external payment or security certification.
