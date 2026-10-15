"""Tests require an ephemeral PostgreSQL database, not a live payment provider."""
import os
from uuid import uuid4
import psycopg
import pytest
from backend.payment_commands import create_intent_with_command
from backend.reconciliation import record_simulated_event, assess_simulated_intent

DSN = os.getenv("MERCHANTPAY_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="MERCHANTPAY_TEST_DSN unset")

@pytest.fixture(scope="module", autouse=True)
def schema():
    if not DSN:
        return
    with psycopg.connect(DSN) as conn:
        for migration in ("0001_payment_intents.sql","0002_payment_commands.sql","0003_simulated_provider_events.sql"):
            with open("backend/migrations/" + migration, encoding="utf-8") as f:
                conn.execute(f.read())

def fresh_intent():
    return create_intent_with_command(DSN,str(uuid4()),str(uuid4()),1000).intent_id

def test_no_evidence_is_never_success():
    intent = fresh_intent()
    result = assess_simulated_intent(DSN,intent)
    assert result.classification == "no_evidence" and result.evidence_count == 0

def test_duplicate_event_does_not_duplicate_evidence():
    intent, event = fresh_intent(), str(uuid4())
    assert record_simulated_event(DSN,intent,event,"authorized")
    assert not record_simulated_event(DSN,intent,event,"authorized")
    result = assess_simulated_intent(DSN,intent)
    assert result.classification == "consistent_authorized"
    assert result.evidence_count == 1

def test_reused_event_identifier_with_changed_outcome_is_rejected():
    intent, event = fresh_intent(), str(uuid4())
    record_simulated_event(DSN,intent,event,"authorized")
    with pytest.raises(ValueError):
        record_simulated_event(DSN,intent,event,"declined")

def test_reused_event_identifier_with_other_intent_is_rejected():
    first, second, event = fresh_intent(), fresh_intent(), str(uuid4())
    record_simulated_event(DSN,first,event,"authorized")
    with pytest.raises(ValueError):
        record_simulated_event(DSN,second,event,"authorized")

def test_conflicting_distinct_events_are_escalated():
    intent=fresh_intent()
    record_simulated_event(DSN,intent,str(uuid4()),"authorized")
    record_simulated_event(DSN,intent,str(uuid4()),"declined")
    result=assess_simulated_intent(DSN,intent)
    assert result.classification == "conflicting_evidence"
    with psycopg.connect(DSN) as conn:
        status=conn.execute("SELECT status FROM payment_intents WHERE id=%s",(intent,)).fetchone()[0]
    assert status == "created", "Unverified test events must not mark payments successful"
