-- Sandbox-only payment intents; NO card data, charging, or settlement.
CREATE TABLE IF NOT EXISTS payment_intents (
    id UUID PRIMARY KEY,
    merchant_id TEXT NOT NULL CHECK (length(merchant_id) > 0),
    operation TEXT NOT NULL CHECK (operation = 'charge'),
    idempotency_key TEXT NOT NULL CHECK (length(idempotency_key) > 0),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'KES'),
    status TEXT NOT NULL DEFAULT 'created' CHECK (status = 'created'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, operation, idempotency_key)
);
CREATE INDEX IF NOT EXISTS payment_intents_merchant_created
ON payment_intents (merchant_id, created_at DESC);
