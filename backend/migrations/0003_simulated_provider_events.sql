-- Research/sandbox events only. These are NOT authenticated provider callbacks.
CREATE TABLE IF NOT EXISTS simulated_provider_events (
    id UUID PRIMARY KEY,
    intent_id UUID NOT NULL REFERENCES payment_intents(id),
    provider_name TEXT NOT NULL CHECK (provider_name = 'sandbox'),
    provider_event_id TEXT NOT NULL CHECK (length(provider_event_id) > 0),
    reported_outcome TEXT NOT NULL CHECK (reported_outcome IN ('authorized', 'declined')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_name, provider_event_id)
);
CREATE INDEX IF NOT EXISTS simulated_provider_events_intent_idx
ON simulated_provider_events (intent_id, received_at);

-- This table records immutable evaluation observations without changing money status.
CREATE TABLE IF NOT EXISTS reconciliation_observations (
    id UUID PRIMARY KEY,
    intent_id UUID NOT NULL REFERENCES payment_intents(id),
    classification TEXT NOT NULL CHECK (classification IN (
        'no_evidence', 'consistent_authorized', 'consistent_declined', 'conflicting_evidence'
    )),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
