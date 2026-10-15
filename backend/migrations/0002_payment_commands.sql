-- Sandbox-only durable commands. Apply after 0001_payment_intents.sql.
CREATE TABLE IF NOT EXISTS payment_commands (
    id UUID PRIMARY KEY,
    intent_id UUID NOT NULL UNIQUE REFERENCES payment_intents(id),
    command_type TEXT NOT NULL CHECK (command_type = 'simulate_charge'),
    status TEXT NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'in_progress', 'simulated_complete', 'needs_reconciliation')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS payment_commands_queue_idx
    ON payment_commands (created_at, id) WHERE status = 'queued';
