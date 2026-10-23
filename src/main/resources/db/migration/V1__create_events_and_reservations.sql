CREATE TABLE events
(
    id                  UUID PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    starts_at           TIMESTAMPTZ  NOT NULL,
    capacity            INTEGER      NOT NULL,
    available           INTEGER      NOT NULL,
    idempotency_key     VARCHAR(100),
    request_fingerprint VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT events_capacity_positive CHECK (capacity > 0),
    -- Last line of defense against oversell: even a buggy code path cannot
    -- commit a negative stock or more seats than the venue holds.
    CONSTRAINT events_available_within_capacity CHECK (available >= 0 AND available <= capacity),
    CONSTRAINT events_idempotency_key_unique UNIQUE (idempotency_key)
);

CREATE TABLE reservations
(
    id                  UUID PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES events (id),
    customer_id         VARCHAR(100) NOT NULL,
    quantity            INTEGER      NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    idempotency_key     VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservations_status_valid CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT reservations_idempotency_key_unique UNIQUE (idempotency_key)
);

CREATE INDEX reservations_event_id_idx ON reservations (event_id);

-- Partial index keeps the expiration sweep cheap: only PENDING rows are indexed,
-- so the index stays small no matter how many historical reservations exist.
CREATE INDEX reservations_pending_expires_at_idx ON reservations (expires_at) WHERE status = 'PENDING';
