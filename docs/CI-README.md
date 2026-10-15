# CI intent

The corrected sample and deliberately unsafe sample are **different artifacts**.

- `corrected-simulation` must pass.
- `vulnerable-specimen` must execute the two expected failing assertions. It succeeds only when pytest exits with test-failure status and output indicates two failed tests.
- A collection error or unexpected zero-failure result fails CI.

CI is educational proof of targeted regression behavior, not real-money readiness, security certification or verification of provider correctness.

For future production code, create separate pipelines with stricter branch protection, tests, security scanners and independent approval.
