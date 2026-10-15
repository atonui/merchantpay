"""These tests are EXPECTED TO FAIL against the vulnerable specimen."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from experiments.payment_race.vulnerable import FakePaymentService

def test_same_key_must_not_duplicate_external_charge():
    svc = FakePaymentService()
    barrier = Barrier(2)
    svc.before_charge = lambda: barrier.wait(timeout=3)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(svc.charge, "merchant-1", "same-key", 50000)
        second = pool.submit(svc.charge, "merchant-1", "same-key", 50000)
        a, b = first.result(timeout=4), second.result(timeout=4)
    assert svc.charge_count == 1, "CRITICAL: duplicate external charge attempt"
    assert a == b

def test_same_key_with_different_amount_must_be_rejected():
    svc = FakePaymentService()
    svc.charge("merchant-1", "key", 50000)
    try:
        svc.charge("merchant-1", "key", 90000)
    except ValueError:
        return
    assert False, "CRITICAL: idempotency key reused with mismatched parameters"
