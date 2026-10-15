"""Pinned-key JWT verification for sandbox API; no login or token issuance.

Real deployment requires an approved identity provider, key rotation, audience
contract, provisioning controls, and independent security review.
"""
from dataclasses import dataclass
import os

import jwt
from jwt import InvalidTokenError
from fastapi import HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials


@dataclass(frozen=True)
class Settings:
    dsn: str
    jwt_public_key_pem: str
    jwt_issuer: str
    jwt_audience: str

    @classmethod
    def from_env(cls):
        required = {
            "dsn": "MERCHANTPAY_DATABASE_DSN",
            "jwt_public_key_pem": "MERCHANTPAY_JWT_PUBLIC_KEY_PEM",
            "jwt_issuer": "MERCHANTPAY_JWT_ISSUER",
            "jwt_audience": "MERCHANTPAY_JWT_AUDIENCE",
        }
        values = {field: os.getenv(env) for field, env in required.items()}
        if not all(values.values()):
            raise RuntimeError("MerchantPay API authentication/database configuration is incomplete")
        return cls(**values)


def verify_access_token(credentials: HTTPAuthorizationCredentials | None, settings: Settings) -> str:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Authentication required",
                            headers={"WWW-Authenticate": "Bearer"})
    token = credentials.credentials
    if not token or len(token) > 8192:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid access token",
                            headers={"WWW-Authenticate": "Bearer"})
    try:
        claims = jwt.decode(
            token,
            settings.jwt_public_key_pem,
            algorithms=["RS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["sub", "exp", "iat", "nbf", "iss", "aud"]},
            leeway=0,
        )
        subject = claims["sub"]
        if (claims.get("token_use") != "access"
                or not isinstance(subject, str)
                or not subject.strip()
                or len(subject) > 256):
            raise InvalidTokenError("unsupported token or subject")
        return subject
    except InvalidTokenError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid access token",
                            headers={"WWW-Authenticate": "Bearer"}) from None
