"""Requires a disposable Postgres database via MERCHANTPAY_TEST_DSN."""
import os
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import psycopg
import pytest

from backend.payment_intents import IdempotencyConflict, create_intent


DSN = os.getenv("MERCHANTPAY_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="MERCHANTPAY_TEST_DSN not set")


@pytest.fixture(scope="module", autouse=True)
def create_schema():
    if not DSN:
        return
    with psycopg.connect(DSN) as conn:
        with conn.cursor() as cur:
            with open("backend/migrations/0001_payment_intents.sql", encoding="utf-8") as f:
                cur.execute(f.read())


def test_parallel_same_key_creates_one_durable_intent():
    merchant, key = str(uuid4()), str(uuid4())
    with ThreadPoolExecutor(max_workers=16) as pool:
        rows = list(pool.map(lambda _: create_intent(DSN, merchant, key, 50000), range(40)))
    assert len({r.id for r in rows}) == 1
    assert all(r.status == "created" for r in rows)
    with psycopg.connect(DSN) as conn:
        n = conn.execute(
            "SELECT count(*) FROM payment_intents WHERE merchant_id=%s AND idempotency_key=%s",
            (merchant, key),
        ).fetchone()[0]
    assert n == 1


def test_conflicting_payload_rejected():
    merchant, key = str(uuid4()), str(uuid4())
    first = create_intent(DSN, merchant, key, 50000)
    with pytest.raises(IdempotencyConflict):
        create_intent(DSN, merchant, key, 90000)
    assert create_intent(DSN, merchant, key, 50000).id == first.id


def test_key_scoped_to_merchant():
    key = str(uuid4())
    a = create_intent(DSN, str(uuid4()), key, 50000)
    b = create_intent(DSN, str(uuid4()), key, 50000)
    assert a.id != b.id


@pytest.mark.parametrize("amount", [0, -10, 1.5, True, "100"])
def test_invalid_amount_rejected(amount):
    with pytest.raises(ValueError):
        create_intent(DSN, str(uuid4()), str(uuid4()), amount)
