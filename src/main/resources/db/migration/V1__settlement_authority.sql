CREATE TABLE settlement_domain (
    domain_id       uuid PRIMARY KEY,
    currency        text NOT NULL DEFAULT 'SIM' CHECK (currency = 'SIM'),
    scale           smallint NOT NULL DEFAULT 0 CHECK (scale = 0),
    state_revision  bigint NOT NULL DEFAULT 0 CHECK (state_revision >= 0),
    next_queue_seq  bigint NOT NULL DEFAULT 1 CHECK (next_queue_seq > 0)
);

CREATE TABLE account (
    domain_id       uuid NOT NULL REFERENCES settlement_domain,
    account_id      uuid NOT NULL,
    opening_minor   bigint NOT NULL CHECK (opening_minor >= 0),
    balance_minor   bigint NOT NULL CHECK (balance_minor >= 0),
    PRIMARY KEY (domain_id, account_id)
);

CREATE TABLE batch (
    domain_id          uuid NOT NULL REFERENCES settlement_domain,
    batch_id           uuid NOT NULL,
    snapshot_revision  bigint NOT NULL CHECK (snapshot_revision >= 0),
    commit_revision    bigint NOT NULL CHECK (commit_revision > snapshot_revision),
    policy_version     text NOT NULL,
    PRIMARY KEY (domain_id, batch_id),
    UNIQUE (domain_id, commit_revision)
);

CREATE TABLE payment (
    domain_id    uuid NOT NULL,
    payment_id   uuid NOT NULL,
    from_account uuid NOT NULL,
    to_account   uuid NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    queue_seq    bigint NOT NULL CHECK (queue_seq > 0),
    status       text NOT NULL CHECK (status IN ('QUEUED', 'CANCELLED', 'SETTLED')),
    batch_id     uuid,
    PRIMARY KEY (domain_id, payment_id),
    UNIQUE (domain_id, queue_seq),
    FOREIGN KEY (domain_id, from_account) REFERENCES account,
    FOREIGN KEY (domain_id, to_account) REFERENCES account,
    FOREIGN KEY (domain_id, batch_id) REFERENCES batch,
    CHECK (from_account <> to_account),
    CHECK ((status = 'SETTLED') = (batch_id IS NOT NULL))
);

CREATE INDEX payment_queue ON payment (domain_id, queue_seq) WHERE status = 'QUEUED';
CREATE INDEX payment_membership ON payment (domain_id, batch_id) WHERE status = 'SETTLED';

CREATE TABLE command_receipt (
    domain_id      uuid NOT NULL REFERENCES settlement_domain,
    command_id     uuid NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('SUBMIT', 'CANCEL', 'SETTLE')),
    payload        text NOT NULL,
    outcome        text NOT NULL,
    detail         text NOT NULL,
    batch_id       uuid,
    state_revision bigint NOT NULL CHECK (state_revision >= 0),
    gross_minor    numeric NOT NULL CHECK (gross_minor >= 0 AND gross_minor = trunc(gross_minor)
                                          AND gross_minor < 'Infinity'::numeric),
    PRIMARY KEY (domain_id, command_id),
    FOREIGN KEY (domain_id, batch_id) REFERENCES batch
);

CREATE TABLE domain_event (
    domain_id      uuid NOT NULL,
    state_revision bigint NOT NULL CHECK (state_revision > 0),
    command_id     uuid NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('SUBMIT', 'CANCEL', 'SETTLE')),
    payment_ids    uuid[] NOT NULL,
    PRIMARY KEY (domain_id, state_revision),
    UNIQUE (domain_id, command_id),
    FOREIGN KEY (domain_id, command_id) REFERENCES command_receipt DEFERRABLE INITIALLY DEFERRED,
    CHECK (array_ndims(payment_ids) = 1 AND cardinality(payment_ids) BETWEEN 1 AND 128
           AND array_position(payment_ids, NULL) IS NULL)
);
