"""Regression tests for the in-memory safety experiment."""
from concurrent.futures import ThreadPoolExecutor
import pytest

from experiments.payment_race.corrected import (
    IdempotencyConflict,
    SimulatedPaymentService,
)


def test_concurrent_duplicate_requests_share_one_result():
    service = SimulatedPaymentService()
    with ThreadPoolExecutor(max_workers=24) as pool:
        results = list(pool.map(lambda _: service.charge("m1", "k1", 50000), range(100)))
    assert service.charge_count == 1
    assert len({r["receipt_id"] for r in results}) == 1


def test_same_key_changed_amount_rejected_without_charge():
    service = SimulatedPaymentService()
    first = service.charge("m1", "k1", 50000)
    with pytest.raises(IdempotencyConflict):
        service.charge("m1", "k1", 90000)
    assert service.charge_count == 1
    assert service.charge("m1", "k1", 50000) == first


def test_same_key_is_scoped_by_merchant():
    service = SimulatedPaymentService()
    a = service.charge("m1", "same", 50000)
    b = service.charge("m2", "same", 50000)
    assert a["receipt_id"] != b["receipt_id"]
    assert service.charge_count == 2


@pytest.mark.parametrize("amount", [0, -1, 1.5, True, "100"])
def test_invalid_amount_rejected(amount):
    service = SimulatedPaymentService()
    with pytest.raises(ValueError):
        service.charge("m1", "k1", amount)
    assert service.charge_count == 0


def test_returned_receipt_mutation_does_not_change_record():
    service = SimulatedPaymentService()
    receipt = service.charge("m1", "k1", 50000)
    receipt["amount_minor"] = 999
    assert service.charge("m1", "k1", 50000)["amount_minor"] == 50000


def test_different_keys_produce_distinct_simulated_attempts():
    service = SimulatedPaymentService()
    a = service.charge("m1", "k1", 50000)
    b = service.charge("m1", "k2", 50000)
    assert a["receipt_id"] != b["receipt_id"]
    assert service.charge_count == 2
