ALTER TABLE analysis_jobs
    ADD COLUMN IF NOT EXISTS risk_level text NOT NULL DEFAULT 'AUTO';

ALTER TABLE analysis_jobs
    DROP CONSTRAINT IF EXISTS analysis_jobs_risk_level_check;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT analysis_jobs_risk_level_check
        CHECK (risk_level IN ('AUTO', 'LOW', 'MEDIUM', 'HIGH'));
