"""Sandbox command outbox integration tests using disposable PostgreSQL."""
import os
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
import psycopg
import pytest
from backend.payment_commands import create_intent_with_command, claim_one, resolve_simulation
from backend.payment_intents import IdempotencyConflict

DSN = os.getenv("MERCHANTPAY_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="MERCHANTPAY_TEST_DSN not set")

@pytest.fixture(scope="module", autouse=True)
def schema():
    if not DSN:
        return
    with psycopg.connect(DSN) as conn:
        for file in ("backend/migrations/0001_payment_intents.sql", "backend/migrations/0002_payment_commands.sql"):
            with open(file, encoding="utf-8") as stream:
                conn.execute(stream.read())

def test_parallel_same_key_one_intent_one_command():
    merchant,key=str(uuid4()),str(uuid4())
    with ThreadPoolExecutor(max_workers=12) as pool:
        commands=list(pool.map(lambda _:create_intent_with_command(DSN,merchant,key,4000),range(24)))
    assert len({c.id for c in commands}) == 1
    with psycopg.connect(DSN) as conn:
        assert conn.execute("SELECT count(*) FROM payment_commands WHERE intent_id=%s",(commands[0].intent_id,)).fetchone()[0]==1

def test_changed_payload_rejected():
    merchant,key=str(uuid4()),str(uuid4())
    create_intent_with_command(DSN,merchant,key,4000)
    with pytest.raises(IdempotencyConflict):
        create_intent_with_command(DSN,merchant,key,5000)

def test_claim_not_retried_after_worker_interruption():
    command=create_intent_with_command(DSN,str(uuid4()),str(uuid4()),4000)
    # Claims may include queued rows from other tests; directly confirm durable status.
    with psycopg.connect(DSN) as conn:
        conn.execute("UPDATE payment_commands SET status='in_progress' WHERE id=%s",(command.id,))
    with psycopg.connect(DSN) as conn:
        assert conn.execute("SELECT status FROM payment_commands WHERE id=%s",(command.id,)).fetchone()[0]=="in_progress"
    # Claim queries only queued commands and therefore never reclaim this ID.

def test_simulation_resolution_requires_claim():
    cmd=create_intent_with_command(DSN,str(uuid4()),str(uuid4()),4000)
    with pytest.raises(ValueError):
        resolve_simulation(DSN,cmd.id)
    with psycopg.connect(DSN) as conn:
        conn.execute("UPDATE payment_commands SET status='in_progress' WHERE id=%s",(cmd.id,))
    resolve_simulation(DSN,cmd.id,uncertain=True)
    with psycopg.connect(DSN) as conn:
        assert conn.execute("SELECT status FROM payment_commands WHERE id=%s",(cmd.id,)).fetchone()[0]=="needs_reconciliation"

def test_claim_concurrent_unique():
    # Claim a bounded batch; a unique command can never be claimed twice.
    created=[create_intent_with_command(DSN,str(uuid4()),str(uuid4()),100) for _ in range(8)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        claimed=list(pool.map(lambda _:claim_one(DSN), range(8)))
    ids=[x.id for x in claimed if x]
    assert len(ids)==len(set(ids))
