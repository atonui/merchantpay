"""INTENTIONALLY INSECURE toy simulator. NEVER use with real payments."""
import threading
import time

class FakePaymentService:
    def __init__(self):
        self.completed = {}
        self.charge_count = 0
        self._counter_lock = threading.Lock()
        self.before_charge = lambda: None

    def charge(self, merchant_id: str, key: str, amount_minor: int):
        if amount_minor <= 0:
            raise ValueError("invalid amount")
        token = (merchant_id, key)
        if token in self.completed:  # TOCTOU: check-then-act is not atomic
            return self.completed[token]
        self.before_charge()  # hook synchronizes threads in the test
        with self._counter_lock:
            self.charge_count += 1
            number = self.charge_count
        receipt = {"receipt_id": f"SIM-{number}", "amount_minor": amount_minor}
        self.completed[token] = receipt
        return receipt
