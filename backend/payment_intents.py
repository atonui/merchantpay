"""Durable, sandbox-only payment intent creation using PostgreSQL.

No provider call is made here. A created intent is NOT a successful payment.
"""
from dataclasses import dataclass
from uuid import uuid4

import psycopg


class IdempotencyConflict(ValueError):
    pass


@dataclass(frozen=True)
class PaymentIntent:
    id: str
    merchant_id: str
    amount_minor: int
    currency: str
    status: str


def create_intent(dsn: str, merchant_id: str, key: str, amount_minor: int, currency: str = "KES") -> PaymentIntent:
    if not isinstance(merchant_id, str) or not merchant_id.strip():
        raise ValueError("merchant_id required")
    if not isinstance(key, str) or not key.strip():
        raise ValueError("idempotency key required")
    if isinstance(amount_minor, bool) or not isinstance(amount_minor, int) or amount_minor <= 0 or amount_minor > 9223372036854775807:
        raise ValueError("amount_minor must be positive int64")
    if currency != "KES":
        raise ValueError("unsupported currency")
    # The uniqueness constraint, not a Python lock, serializes competing inserts.
    # PostgreSQL READ COMMITTED gives a fresh snapshot for the subsequent SELECT
    # after waiting for a conflicting transaction.
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO payment_intents
                    (id, merchant_id, operation, idempotency_key, amount_minor, currency)
                VALUES (%s, %s, 'charge', %s, %s, %s)
                ON CONFLICT (merchant_id, operation, idempotency_key) DO NOTHING
                """,
                (uuid4(), merchant_id, key, amount_minor, currency),
            )
            cur.execute(
                """
                SELECT id, merchant_id, amount_minor, currency, status
                FROM payment_intents
                WHERE merchant_id = %s AND operation = 'charge' AND idempotency_key = %s
                """,
                (merchant_id, key),
            )
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("intent not found after upsert")
            ident, owner, existing_amount, existing_currency, status = row
            if existing_amount != amount_minor or existing_currency != currency:
                raise IdempotencyConflict("idempotency key reused with different payment parameters")
            return PaymentIntent(str(ident), owner, existing_amount, existing_currency, status)
