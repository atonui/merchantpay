# MerchantPay Android sandbox

A **native, offline** Kotlin/Jetpack Compose demonstration. It does not accept payments, connect to a server, or access NFC.

## Run
Open the `android/` directory as a project in Android Studio with Android SDK 35 and JDK 17, or use Gradle 8.9:

```sh
gradle -p android :app:testDebugUnitTest :app:assembleDebug
```

The launcher shows a demo merchant and lets you create a simulated `created` intent. No funds move. The manifest has no `INTERNET` permission.

## Code structure
- `ui/PaymentScreen.kt`: Compose offline demo.
- `domain/KesAmount.kt`: exact KES minor-unit parsing.
- `data/PaymentRepository.kt`: repository contract and offline demo implementation.
- `data/PaymentApiClient.kt`: future HTTPS client for the authenticated FastAPI API, **not wired to the UI**.
- `auth/AuthSessionManager.kt`: memory-only access-token lifecycle, local logout, expiration and login-generation fencing; no real login or persistence.
- `auth/AuthCoordinator.kt`: interface for a future browser-based OIDC code/PKCE adapter, **not implemented or connected**.
- `src/test`: amount-validation and MockWebServer API-client unit tests.

## Known gaps
No real login or identity provider, remote token revocation, merchant provisioning, persisted idempotency keys, connectivity, NFC, M-Pesa, card acceptance or production signing. A Gradle wrapper has not yet been committed; GitHub Actions installs a pinned Gradle version.

See `docs/ANDROID-ADR.md` for trust boundaries and planned gates.

See `docs/MOBILE-IDENTITY-ADR.md` for the OIDC issuer/redirect and backend token-contract decisions that must be made before login can be enabled.
