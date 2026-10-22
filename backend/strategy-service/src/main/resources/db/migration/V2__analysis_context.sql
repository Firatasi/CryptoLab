ALTER TABLE analysis_jobs
    ADD COLUMN session_generation bigint,
    ADD COLUMN market_dataset_id uuid,
    ADD COLUMN market_snapshot_id uuid,
    ADD COLUMN feature_version text,
    ADD COLUMN feature_schema_hash text,
    ADD COLUMN bundle_release_status text;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT analysis_context_complete CHECK (
        (
            model_bundle_id IS NULL
                AND session_generation IS NULL
                AND market_dataset_id IS NULL
                AND market_snapshot_id IS NULL
                AND feature_version IS NULL
                AND feature_schema_hash IS NULL
                AND bundle_release_status IS NULL
            )
            OR
        (
            model_bundle_id IS NOT NULL
                AND session_generation IS NOT NULL
                AND session_generation >= 0
                AND market_dataset_id IS NOT NULL
                AND market_snapshot_id IS NOT NULL
                AND feature_version IS NOT NULL
                AND feature_schema_hash IS NOT NULL
                AND bundle_release_status IS NOT NULL
            )
        );