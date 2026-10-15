# ADR-0002 — Android sandbox foundation

**Status:** Proposed. Review the Android CI build before merge.

## Scope
Native Android app using Kotlin and Jetpack Compose. The launcher displays an **offline simulated intent creator**, not a payment terminal. It does not connect to FastAPI, call M-Pesa, use NFC, or handle any card credentials. The manifest deliberately has **no INTERNET or NFC permission**. The app's package/application ID is `com.merchantpay.app.sandbox` to avoid confusion with future approved production applications.

The `PaymentRepository` interface separates the demo UI from a separately implemented `PaymentApiClient`. The latter is exercised with `MockWebServer` in JVM tests and is **not instantiated by the app**. It maps only the backend's current `created` state, never displays paid/authorized/settled based on device response, and does not automatically retry requests. HTTPS is required except for an explicitly opted-in loopback test mode.

## Financial invariants
- Amounts are parsed using exact `BigDecimal` into integer KES cents, never floating point.
- A caller-supplied idempotency key is retained unchanged for a logical request. The API client does not generate a new key on a network error.
- Unknown network outcomes remain unknown; never assume failure or retry with a new key.
- The UI creates a random UUID for each **offline demo tap only**. Before wiring the real API, use durable request state so configuration changes and process death do not create accidental new attempts.
- The app never treats a created intent as proof of money movement.

## Security boundaries
- No embedded merchant token, secret, private key or login form.
- The future OIDC flow must use an approved external authorization agent/PKCE, short-lived access tokens, secure session storage, revocation and verified issuer/audience/key rotation.
- No cleartext network transport; redirects and OkHttp automatic retries are disabled.
- No logging of bearer tokens or payment details.
- Merchant scoping remains enforced server-side regardless of mobile parameters.
- Later production build requires device integrity considerations, runtime protections, and SoftPOS vendor SDK certification; this scaffold does not satisfy any such requirements.

## Reproducibility
The Android project uses Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Android SDK 35 and JDK 17. GitHub Actions installs Gradle and the Android SDK and runs `gradle -p android :app:testDebugUnitTest :app:assembleDebug`. A standard Gradle wrapper has not yet been generated; add and verify one before preparing a developer release. No app-store artifact or signed production APK is produced.

## Next gate
1. Confirm CI compilation and JVM tests.
2. Implement a proper mobile identity-provider integration and stable idempotency lifecycle.
3. Only then connect the UI to the authenticated backend in a dedicated reviewed PR.
