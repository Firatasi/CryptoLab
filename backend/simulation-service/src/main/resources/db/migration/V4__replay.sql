CREATE TABLE replay_jobs (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    status text NOT NULL,
    session_id uuid NOT NULL REFERENCES simulation_sessions(id),
    dataset_id uuid NOT NULL,
    rule_profile_id uuid NOT NULL,
    policy_artifact_id uuid NOT NULL,
    seed bigint NOT NULL,
    dataset_hash text NOT NULL,
    rule_hash text NOT NULL,
    policy_hash text NOT NULL,
    tie_policy text NOT NULL,
    data_layer text NOT NULL,
    fidelity text NOT NULL,
    ambiguous_bar_count bigint NOT NULL DEFAULT 0,
    result_artifact_id uuid,
    cancelled boolean NOT NULL DEFAULT false,
    lease_until timestamptz,
    worker_token uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE replay_results (
    artifact_id uuid PRIMARY KEY,
    replay_id uuid NOT NULL UNIQUE REFERENCES replay_jobs(id),
    owner_id uuid NOT NULL,
    session_id uuid NOT NULL REFERENCES simulation_sessions(id),
    gross_pnl numeric(38,18) NOT NULL,
    net_pnl numeric(38,18) NOT NULL,
    total_fees numeric(38,18) NOT NULL,
    signed_funding numeric(38,18) NOT NULL,
    liquidation_fees numeric(38,18) NOT NULL,
    insurance_adjustment numeric(38,18) NOT NULL,
    final_available numeric(38,18) NOT NULL,
    economic_checksum text NOT NULL,
    csv_payload text NOT NULL,
    created_at timestamptz NOT NULL
);

