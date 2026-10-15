# MerchantPay — Engineering Foundation v0.1

**Status:** research and simulation only. No real-money transactions. No production-grade card capture.

MerchantPay is a proposed Android merchant payment application supporting M-Pesa and certified SoftPOS integrations through independently approved providers.

## Included
- `AGENTS.md` — agent rules and mandatory review gates
- `docs/ARCHITECTURE.md` — system boundaries and trust model
- `docs/SECURITY-BASELINE.md` — threat model and validation requirements
- `docs/EXPERIMENTS.md` — skill evaluation protocol
- `skills/` — three foundational skills
- `experiments/payment_race/` — intentionally flawed sample and safety tests

## Run the adversarial experiment
```sh
python -m pip install -r experiments/payment_race/requirements.txt
pytest -q experiments/payment_race/tests
```
Tests intentionally **fail** against `vulnerable.py` to demonstrate an unsafe duplicate-payment race. No network or payment access is used.

## Next milestone
Implement a correct payment state-machine sample and evaluate blind skill-assisted repair against the same hidden tests before building any real provider integration.
