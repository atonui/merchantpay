"""Merchant-scoped sandbox API. No real payment initiation or provider callbacks."""
from typing import Annotated, Literal
from uuid import UUID

import psycopg
from fastapi import Depends, FastAPI, Header, HTTPException, Path, Response, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, StrictInt

from backend.auth import Settings, verify_access_token
from backend.payment_commands import create_intent_with_command
from backend.payment_intents import IdempotencyConflict

bearer = HTTPBearer(auto_error=False)
MerchantId = Annotated[str, Path(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9_-]*$")]
IdempotencyKey = Annotated[str, Header(alias="Idempotency-Key", min_length=16, max_length=128,
                                       pattern=r"^[A-Za-z0-9._~-]+$")]


class IntentCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    amount_minor: StrictInt = Field(gt=0, le=9223372036854775807)
    currency: Literal["KES"] = "KES"


class IntentView(BaseModel):
    id: UUID
    merchant_id: str
    amount_minor: int
    currency: str
    status: str


def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings if settings is not None else Settings.from_env()
    app = FastAPI(title="MerchantPay Sandbox API", docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def no_store(request, call_next):
        result = await call_next(request)
        result.headers["Cache-Control"] = "no-store"
        result.headers["X-Content-Type-Options"] = "nosniff"
        return result

    def subject_from_token(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> str:
        return verify_access_token(credentials, config)

    def require_membership(merchant_id: str, subject: str, write: bool = False):
        # Do not authorize from client-supplied merchant_id claims or headers.
        # The signed token identifies a subject; the DB decides its merchant rights.
        with psycopg.connect(config.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT role FROM merchant_memberships
                       WHERE merchant_id=%s AND subject=%s AND is_active=TRUE""",
                    (merchant_id, subject),
                )
                row = cur.fetchone()
        if row is None or (write and row[0] not in ("owner", "manager", "cashier")):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Merchant access denied")

    def load_intent(merchant_id: str, intent_id: UUID) -> IntentView:
        with psycopg.connect(config.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT id, merchant_id, amount_minor, currency, status
                       FROM payment_intents WHERE id=%s AND merchant_id=%s""",
                    (intent_id, merchant_id),
                )
                row = cur.fetchone()
        if row is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Payment intent not found")
        return IntentView(id=row[0], merchant_id=row[1], amount_minor=row[2],
                          currency=row[3], status=row[4])

    @app.post("/v1/merchants/{merchant_id}/payment-intents",
              response_model=IntentView, status_code=status.HTTP_201_CREATED)
    def create_payment_intent(
        merchant_id: MerchantId,
        payload: IntentCreate,
        idempotency_key: IdempotencyKey,
        subject: Annotated[str, Depends(subject_from_token)],
    ):
        require_membership(merchant_id, subject, write=True)
        try:
            command = create_intent_with_command(
                config.dsn, merchant_id, idempotency_key, payload.amount_minor, payload.currency
            )
        except IdempotencyConflict:
            raise HTTPException(status.HTTP_409_CONFLICT, "Idempotency key conflicts with previous request") from None
        return load_intent(merchant_id, UUID(command.intent_id))

    @app.get("/v1/merchants/{merchant_id}/payment-intents/{intent_id}", response_model=IntentView)
    def get_payment_intent(
        merchant_id: MerchantId,
        intent_id: UUID,
        subject: Annotated[str, Depends(subject_from_token)],
    ):
        require_membership(merchant_id, subject)
        return load_intent(merchant_id, intent_id)

    return app
