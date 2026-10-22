CREATE TABLE backfill_requests (
    instrument_id uuid NOT NULL,
    horizon_seconds integer NOT NULL,
    as_of timestamptz NOT NULL,
    backfill_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (instrument_id, horizon_seconds, as_of)
);

CREATE TABLE scan_candidates (
    scan_id uuid NOT NULL REFERENCES scan_jobs(id),
    position_index integer NOT NULL,
    instrument_id uuid NOT NULL,
    eligible boolean NOT NULL,
    skipped_reason text,
    analysis_id uuid,
    evaluated_at timestamptz NOT NULL,
    PRIMARY KEY (scan_id, position_index),
    UNIQUE (scan_id, instrument_id)
);

