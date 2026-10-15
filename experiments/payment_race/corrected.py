"""Safe *in-memory simulation* of idempotency; NOT a real payment processor.

The lock serializes access within one process only. A distributed production system
needs durable unique constraints, transactional state and provider-side reconciliation.
"""
import threading


class IdempotencyConflict(ValueError):
    """A key was reused with different payment parameters."""


class SimulatedPaymentService:
    def __init__(self):
        self._lock = threading.Lock()
        self._completed = {}
        self.charge_count = 0

    def charge(self, merchant_id: str, key: str, amount_minor: int, currency="KES"):
        if not merchant_id or not key:
            raise ValueError("merchant and idempotency key are required")
        if isinstance(amount_minor, bool) or not isinstance(amount_minor, int) or amount_minor <= 0:
            raise ValueError("amount_minor must be a positive integer")
        if currency != "KES":
            raise ValueError("unsupported currency in this experiment")
        token = (merchant_id, "charge", key)
        fingerprint = (amount_minor, currency)
        with self._lock:
            previous = self._completed.get(token)
            if previous is not None:
                recorded_fingerprint, result = previous
                if recorded_fingerprint != fingerprint:
                    raise IdempotencyConflict("same idempotency key, different request")
                return result.copy()
            # A number here is only a simulated attempt, not an external charge.
            self.charge_count += 1
            result = {
                "receipt_id": f"SIM-{self.charge_count}",
                "amount_minor": amount_minor,
                "currency": currency,
                "status": "simulated_success",
            }
            self._completed[token] = (fingerprint, result)
            return result.copy()
