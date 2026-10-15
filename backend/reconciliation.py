"""Simulated provider evidence journal. This module is NOT a public webhook handler.

Callers are test harnesses, never unverified clients. An event is evidence for
assessment, NOT a verified payment authorization.
"""
from dataclasses import dataclass
from uuid import uuid4, UUID
import psycopg

@dataclass(frozen=True)
class Assessment:
    intent_id: str
    classification: str
    evidence_count: int

def record_simulated_event(dsn: str, intent_id: str, event_id: str, outcome: str) -> bool:
    """Returns True for newly appended event; False for exact duplicate.
    Reuse of provider event ID with conflicting details raises ValueError.
    """
    UUID(intent_id)
    if not isinstance(event_id, str) or not event_id.strip():
        raise ValueError("event_id required")
    if outcome not in ("authorized", "declined"):
        raise ValueError("unexpected simulated outcome")
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO simulated_provider_events
                    (id,intent_id,provider_name,provider_event_id,reported_outcome)
                VALUES (%s,%s,'sandbox',%s,%s)
                ON CONFLICT (provider_name,provider_event_id) DO NOTHING
                RETURNING id
            """, (uuid4(), UUID(intent_id), event_id, outcome))
            inserted = cur.fetchone() is not None
            if not inserted:
                cur.execute("""
                    SELECT intent_id,reported_outcome FROM simulated_provider_events
                    WHERE provider_name='sandbox' AND provider_event_id=%s
                """, (event_id,))
                row = cur.fetchone()
                if row is None or str(row[0]) != intent_id or row[1] != outcome:
                    raise ValueError("provider event identifier reused with different payload")
            return inserted

def assess_simulated_intent(dsn: str, intent_id: str) -> Assessment:
    """Creates an assessment snapshot; never alters payment or settlement status."""
    ident = UUID(intent_id)
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT 1 FROM payment_intents WHERE id=%s FOR SHARE", (ident,))
            if cur.fetchone() is None:
                raise ValueError("unknown payment intent")
            cur.execute("""
                SELECT reported_outcome, count(*)
                FROM simulated_provider_events
                WHERE intent_id=%s
                GROUP BY reported_outcome
            """, (ident,))
            counts = dict(cur.fetchall())
            total = sum(counts.values())
            if not total:
                classification = "no_evidence"
            elif len(counts) > 1:
                classification = "conflicting_evidence"
            elif "authorized" in counts:
                classification = "consistent_authorized"
            else:
                classification = "consistent_declined"
            cur.execute("""
                INSERT INTO reconciliation_observations (id,intent_id,classification)
                VALUES (%s,%s,%s)
            """, (uuid4(), ident, classification))
            return Assessment(intent_id, classification, total)
