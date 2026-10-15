# Payment race experiment — corrected simulation

## Scope
The vulnerable baseline in `experiments/payment_race/vulnerable.py` is deliberately left untouched.
The corrected `experiments/payment_race/corrected.py` provides *single-process,
in-memory* request deduplication using a lock and parameter fingerprint.

## What the design demonstrates
- A repeated identical request resolves to the same simulated result.
- A changed payload under the same merchant/key raises an error.
- Parallel calls in the same process cannot create multiple simulated attempts.
- Results are defensive copies so a caller cannot mutate the internal record.

## Run
```sh
python -m pip install -r experiments/payment_race/requirements.txt
python -m pytest -q experiments/payment_race/tests/test_corrected.py
```

The original tests in `test_idempotency.py` **must continue to fail** against the
intentionally vulnerable specimen. Do not include those tests in a green-only CI
target until separated as explicit expected failures.

## What remains unproven
This is **not a skill-vs-no-skill benchmark**; no controlled comparison has run.
It also does not demonstrate crash recovery, multiple workers, database isolation,
persistent idempotency, real provider authorization, exactly-once external side effects,
or settlement correctness. A production design will require a durable database
claim, transaction/outbox architecture, provider inquiry, and reconciliation.

## Next experiment
Create identical isolated task prompts for agents with and without the
`payment-state-machine` skill. Blind-score against common hidden tests and
compare quality, cost, and iterations before claiming the skill improves outcomes.
