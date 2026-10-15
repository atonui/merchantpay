-- Sandbox-only server-side merchant authorization.
-- No public endpoint may provision memberships.
CREATE TABLE IF NOT EXISTS merchant_memberships (
    merchant_id TEXT NOT NULL CHECK (length(merchant_id) BETWEEN 1 AND 128),
    subject TEXT NOT NULL CHECK (length(subject) BETWEEN 1 AND 256),
    role TEXT NOT NULL CHECK (role IN ('owner', 'manager', 'cashier', 'viewer')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, subject)
);
CREATE INDEX IF NOT EXISTS merchant_memberships_subject_idx
    ON merchant_memberships (subject) WHERE is_active;
