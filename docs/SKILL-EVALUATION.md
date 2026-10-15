# Payment-State-Machine Skill Evaluation — Protocol v0.1

## Purpose
Determine whether the MerchantPay skill improves verified payment-correctness fixes rather than simply improving the explanation.

## Before running
This is an experiment protocol, **not** evidence that agent skills work.

## Paired assignment
Give the same intentionally vulnerable program (from `experiments/payment_race/vulnerable.py`) and task to two isolated runs of the same model, using identical resource/tool budgets. A receives the payment-state-machine SKILL.md; B does not. Do not expose hidden evaluations or the corrected reference to either run. Randomize order across repetitions.

## Fixed task
"Repair the in-memory simulation so concurrent requests with the same merchant/key/payload produce at most one simulated attempt; key reuse with different payment parameters is rejected. Preserve merchant isolation, return defensive data, add tests. Do not suggest this is production safe."

## Scoring (0–10)
- 3: concurrent duplicate protection under randomized scheduling
- 2: changed amount/currency fingerprint rejected
- 1: merchant scoping
- 1: input validation
- 1: returned mutable data cannot corrupt internal record
- 1: code clarity and evidence of constraints
- 1: explicit real-world limitations (multi-worker, persistence, provider uncertainty)

## Review evidence
Record model/version, prompt hash, skill version, input/output tokens, number of calls, elapsed time, test output, and blinded human assessment. Minimum 5 independent runs per condition before drawing even tentative conclusions.

## Hidden tests
Build independent tests with randomized request interleavings, merchant combinations and parameter conflicts. Hidden tests should be maintained outside the agent's visible task context and should not depend on coding style or function-name tricks.

## Risks
The vulnerable fixture has a synchronization hook that requires care during refactoring. Do not treat passing a particular canned test as evidence of production-level payment correctness.
