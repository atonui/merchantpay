# ADR-0003 — Mobile session boundary and identity-provider selection

**Status:** Partial implementation. No real mobile login. Sandbox only.

## Decision

Introduce a narrow, fail-closed Android session manager and a future external OIDC authorization adapter. The manager holds an access token **in memory only**, requires an expiry more than 60 seconds away, and clears it on local logout, process death, failed sign-in or near-expiry. A generation fence rejects late sign-in results after logout or a newer sign-in attempt. A token's value is excluded from object string representations.

This PR does **not** add an identity provider, authorization screen, refresh-token flow, secure persistent storage, app-link callback receiver, or INTERNET permission. The offline demo remains isolated. The Android API client can consume this manager via its existing `AccessTokenProvider` contract in a later PR; no API requests are enabled here.

## Required identity-provider decision

Select a trusted OIDC provider with:
- Public Android client registration and **Authorization Code + PKCE (S256)** via an external browser or system authorization agent; no embedded WebView or client secret.
- Issuer metadata and signing key rotation, correct audience for the MerchantPay API, short-lived access tokens and a documented logout/revocation policy.
- Verified HTTPS Android App Links redirect on a MerchantPay-controlled domain, including a reviewed `assetlinks.json` and package signing certificate association.
- Backend support for the issuer's real access-token format and claims. The current FastAPI verifier expects RS256 with `token_use=access`, specific `iss` and `aud`. **Do not assume** any third-party OIDC provider issues tokens matching that contract.
- Administrative merchant membership provisioning with audit controls and tenant isolation.

Before wiring login, choose the provider and decide whether to adapt the backend verifier to its actual JWT claims or use a backend token-exchange layer. Do not weaken audience, issuer or signature checks to make a provider appear compatible.

## Threats and mitigations

| Threat | Current mitigation | Remaining work |
| --- | --- | --- |
| Token theft from app bundle | No embedded tokens or client secret | Runtime device compromise, secure storage, attestation |
| Expired token sent to API | Local 60-second expiry margin | Backend must still verify token exp and revocation |
| Login response arrives after logout | Generation fence | Real OIDC adapter lifecycle/cancellation testing |
| Process death and token recovery | Fail closed: in-memory session disappears | Secure session restore/refresh if required |
| Browser redirect injection | No redirect handler enabled | Verified App Links, state/nonce/PKCE validation |
| Stolen access token | Short lifetime intended | Provider token policy, refresh rotation, logout/revocation |
| Forged merchant role | Server-side memberships remain authoritative | Provisioning and RLS defense in depth |

## Financial boundary

No payment intents are created from the mobile network client in this PR. No card, PIN, CVV, M-Pesa request or payment authorization is possible. Session status is **never** financial evidence.

## Verification

Android JVM tests cover initial signed-out state, expiry boundary, invalid tokens, token-redacted diagnostics, local logout, failed sign-in, and late concurrent sign-in completion. Existing API-client tests continue to exercise fake tokens with a mock server. CI builds an offline sandbox APK; it does not demonstrate OIDC interoperability or production security.

## Next gates

1. Agree on OIDC issuer, public client ID, verified redirect domain and the backend token contract.
2. Implement and test AppAuth (or an equivalent vetted OAuth library) using system browser, S256 PKCE, state and nonce validation.
3. Implement secure session renewal/revocation and lifecycle-aware UI, then connect the API client behind authenticated merchant selection.
4. Add durable mobile idempotency state **before** enabling network payment-intent creation.
