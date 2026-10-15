# ADR-0001: Merchant-scoped sandbox API authentication

**Status:** Proposed, subject to independent review. No production authorization.

## Decision
Expose only payment-intent create/read operations through FastAPI. The caller sends an RS256-signed JWT **access token** with an API-specific audience, pinned trusted issuer, expiration, not-before and issued-at timestamps. The API does not mint tokens or implement login. Public-key configuration is required and the service fails closed if absent. A later identity provider and JWKS key rotation remain undecided.

The signed token's `sub` identifies a person or service, **not a merchant**. Server-side `merchant_memberships` establishes the allowed merchant and role on every request. Merchant IDs supplied in URLs are untrusted selectors. Only owner/manager/cashier can create intents; viewer can read. No API is provided for granting memberships.

## Endpoints
- `POST /v1/merchants/{merchant_id}/payment-intents` — body `{ "amount_minor": 50000, "currency": "KES" }`, required `Idempotency-Key` header (16–128 allowed ASCII chars); creates a **sandbox-only** intent and queued simulation command.
- `GET /v1/merchants/{merchant_id}/payment-intents/{uuid}` — retrieves a single merchant-scoped intent.

Returns `created` only. No authorization, charge, settlement, refund or card data is involved. Reusing the same idempotency key with a changed amount returns 409. No provider callbacks are exposed.

## Security boundaries and risks
- Prevent IDOR via database membership check and `WHERE merchant_id = ...` on reads.
- Reject expired, unsigned, tampered, wrong issuer, wrong audience and wrong token-use JWTs.
- Never trust a client-supplied role or merchant claim.
- Membership revocation is checked at request time.
- The JWT public key is configured server-side; **never** ship signing private keys in the mobile app.
- No public enrollment/login flow; membership provisioning is administrative and out of scope.
- Fixed single-key verifier is a **sandbox simplification**. Production needs issuer-managed JWKS/key rotation, OAuth2/OIDC security analysis, replay/session protection, rate limiting, incident handling and audited provisioning.
- API process database credentials can access all tenants; application checks are not a replacement for defense-in-depth database RLS and independent penetration testing.
- Return `Cache-Control: no-store`; do not log Authorization headers or payment credentials.
- No guarantee of production security or PCI compliance.

## Run locally
Apply migrations 0001–0004 to a **disposable** PostgreSQL database, then configure:
`MERCHANTPAY_DATABASE_DSN`, `MERCHANTPAY_JWT_PUBLIC_KEY_PEM`, `MERCHANTPAY_JWT_ISSUER`, `MERCHANTPAY_JWT_AUDIENCE`.

A trusted external issuer must mint valid RS256 access tokens with `sub, iss, aud, exp, nbf, iat, token_use=access`. For local tests, the test suite generates a throwaway RSA keypair. Memberships must be provisioned directly by an authorized administrator, not by the merchant API.

Start with `uvicorn backend.api:create_app --factory`. This is **not** a public-deployment instruction.
