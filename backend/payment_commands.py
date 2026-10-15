"""Sandbox-only PostgreSQL command outbox; no external payment side effects.

Claiming is durable: a crash leaves in_progress for reconciliation and NEVER
silently retries an ambiguous external operation.
"""
from dataclasses import dataclass
from uuid import uuid4
import psycopg
from backend.payment_intents import IdempotencyConflict

@dataclass(frozen=True)
class Command:
    id: str
    intent_id: str
    status: str

def create_intent_with_command(dsn: str, merchant_id: str, key: str, amount_minor: int, currency="KES"):
    if not isinstance(merchant_id, str) or not merchant_id.strip():
        raise ValueError("merchant required")
    if not isinstance(key, str) or not key.strip():
        raise ValueError("idempotency key required")
    if isinstance(amount_minor, bool) or not isinstance(amount_minor, int) or not (0 < amount_minor <= 9223372036854775807):
        raise ValueError("amount must be positive int64 minor units")
    if currency != "KES":
        raise ValueError("unsupported currency")
    intent_id = uuid4()
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO payment_intents
                    (id, merchant_id, operation, idempotency_key, amount_minor, currency)
                VALUES (%s, %s, 'charge', %s, %s, %s)
                ON CONFLICT (merchant_id, operation, idempotency_key) DO NOTHING
                RETURNING id
            """, (intent_id, merchant_id, key, amount_minor, currency))
            inserted = cur.fetchone()
            cur.execute("""
                SELECT id, amount_minor, currency FROM payment_intents
                WHERE merchant_id=%s AND operation='charge' AND idempotency_key=%s
            """, (merchant_id, key))
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("intent missing after insert")
            actual_id, existing_amount, existing_currency = row
            if existing_amount != amount_minor or existing_currency != currency:
                raise IdempotencyConflict("same key, different payment parameters")
            if inserted:
                cur.execute("""
                    INSERT INTO payment_commands (id,intent_id,command_type)
                    VALUES (%s,%s,'simulate_charge')
                """, (uuid4(), actual_id))
            cur.execute("SELECT id,intent_id,status FROM payment_commands WHERE intent_id=%s", (actual_id,))
            cmd = cur.fetchone()
            if cmd is None:
                # Intents made through older code may have no command.
                # Refuse to silently backfill: an external attempt might have been made.
                raise RuntimeError("preexisting intent without command: manual reconciliation required")
            return Command(str(cmd[0]), str(cmd[1]), cmd[2])

def claim_one(dsn: str):
    """Atomically claim a queued simulation command, with no lease/retry."""
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT id FROM payment_commands WHERE status='queued'
                ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """)
            row = cur.fetchone()
            if row is None:
                return None
            cur.execute("""
                UPDATE payment_commands SET status='in_progress',claimed_at=now()
                WHERE id=%s RETURNING id,intent_id,status
            """, (row[0],))
            c = cur.fetchone()
            return Command(str(c[0]), str(c[1]), c[2])

def resolve_simulation(dsn: str, command_id: str, *, uncertain: bool=False):
    """Test-only decision. Never infers actual provider success."""
    from uuid import UUID
    ident = UUID(command_id)
    target = "needs_reconciliation" if uncertain else "simulated_complete"
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("""
                UPDATE payment_commands SET status=%s WHERE id=%s AND status='in_progress'
                RETURNING id
            """, (target, ident))
            if cur.fetchone() is None:
                raise ValueError("command must be in_progress")
