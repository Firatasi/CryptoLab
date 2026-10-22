CREATE TABLE analysis_jobs (
                               id uuid PRIMARY KEY,
                               owner_id uuid NOT NULL,
                               session_id uuid NOT NULL,
                               instrument_id uuid NOT NULL,
                               horizon_seconds integer NOT NULL,
                               as_of timestamptz NOT NULL,
                               model_bundle_id uuid,
                               feature_snapshot_id uuid,
                               status text NOT NULL,
                               action text,
                               error text,
                               reason_codes jsonb NOT NULL DEFAULT '[]',
                               proposal_id uuid,
                               explanation_status text NOT NULL DEFAULT 'PENDING',
                               explanation text,
                               created_at timestamptz NOT NULL,
                               updated_at timestamptz NOT NULL,
                               lease_until timestamptz,
                               worker_token uuid
);

CREATE TABLE proposals (
                           id uuid NOT NULL,
                           revision integer NOT NULL,
                           owner_id uuid NOT NULL,
                           session_id uuid NOT NULL,
                           instrument_id uuid NOT NULL,
                           generation bigint NOT NULL,
                           policy_version text NOT NULL,
                           model_bundle_id uuid NOT NULL,
                           feature_snapshot_id uuid NOT NULL,
                           plan_intent jsonb NOT NULL,
                           valid_until timestamptz NOT NULL,
                           state text NOT NULL,
                           PRIMARY KEY (id, revision)
);

CREATE TABLE scan_jobs (
                           id uuid PRIMARY KEY,
                           owner_id uuid NOT NULL,
                           session_id uuid NOT NULL,
                           horizon integer NOT NULL,
                           universe_version text NOT NULL,
                           cursor text,
                           status text NOT NULL,
                           total_known bigint NOT NULL,
                           evaluated_count bigint NOT NULL DEFAULT 0,
                           skipped_count bigint NOT NULL DEFAULT 0,
                           reason_counts jsonb NOT NULL DEFAULT '{}',
                           created_at timestamptz NOT NULL,
                           updated_at timestamptz NOT NULL,
                           lease_until timestamptz,
                           worker_token uuid
);

CREATE TABLE policy_artifacts (
                                  id uuid PRIMARY KEY,
                                  version text NOT NULL UNIQUE,
                                  artifact jsonb NOT NULL,
                                  hash text NOT NULL
);

CREATE TABLE inbox (
                       consumer text NOT NULL,
                       event_id uuid NOT NULL,
                       PRIMARY KEY (consumer, event_id)
);

CREATE TABLE outbox (
                        event_id uuid PRIMARY KEY,
                        topic text NOT NULL,
                        event_key text NOT NULL,
                        payload jsonb NOT NULL,
                        created_at timestamptz NOT NULL,
                        published_at timestamptz,
                        lease_until timestamptz,
                        attempts integer NOT NULL DEFAULT 0
);

CREATE TABLE idempotency_records (
                                     subject uuid NOT NULL,
                                     route text NOT NULL,
                                     scope text NOT NULL,
                                     key text NOT NULL,
                                     request_hash text NOT NULL,
                                     result_ref uuid NOT NULL,
                                     PRIMARY KEY (subject, route, scope, key)
);