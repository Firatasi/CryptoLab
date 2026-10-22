CREATE TABLE training_job_projection (
    job_id uuid PRIMARY KEY,
    status text NOT NULL,
    stage text NOT NULL,
    progress jsonb,
    aggregate_version bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL
);

