-- Yalnız model_db'ye uygulanır. Diğer servis tablolarına foreign key/join yok.
CREATE TABLE IF NOT EXISTS training_runs(
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL,
 task text NOT NULL,
 request jsonb NOT NULL,
 status text NOT NULL CHECK(status IN('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 stage text NOT NULL,
 version bigint NOT NULL DEFAULT 1,
 cancel_requested boolean NOT NULL DEFAULT false,
 worker_token uuid,
 lease_until timestamptz,
 result_ref text,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS idempotency_records(
 actor_id uuid,
 route text,
 scope text,
 key text,
 request_hash text NOT NULL,
 result_id uuid NOT NULL,
 PRIMARY KEY(actor_id,route,scope,key)
);

CREATE TABLE IF NOT EXISTS outbox(
 event_id uuid PRIMARY KEY,
 topic text NOT NULL,
 event_key text NOT NULL,
 aggregate_version bigint NOT NULL,
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz
);

CREATE TABLE IF NOT EXISTS inbox(
 consumer text,
 event_id uuid,
 PRIMARY KEY(consumer,event_id)
);

CREATE TABLE IF NOT EXISTS training_sources(
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL,
 title text NOT NULL,
 rights text NOT NULL,
 state text NOT NULL,
 version bigint NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS source_versions(
 id uuid PRIMARY KEY,
 source_id uuid NOT NULL REFERENCES training_sources(id),
 content_hash text NOT NULL,
 parser_version text NOT NULL,
 artifact_ref text NOT NULL,
 deleted boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS extracted_pages(
 source_version_id uuid REFERENCES source_versions(id),
 page int CHECK(page>0),
 content jsonb NOT NULL,
 PRIMARY KEY(source_version_id,page)
);

CREATE TABLE IF NOT EXISTS training_examples(
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL,
 body jsonb NOT NULL,
 review_status text NOT NULL,
 version bigint NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS dataset_manifests(
 id uuid PRIMARY KEY,
 owner_id uuid NOT NULL,
 kind text NOT NULL,
 manifest jsonb NOT NULL,
 hash text NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS dataset_splits(
 id uuid PRIMARY KEY,
 dataset_id uuid NOT NULL REFERENCES dataset_manifests(id),
 manifest jsonb NOT NULL,
 hash text NOT NULL
);

CREATE TABLE IF NOT EXISTS model_versions(
 id uuid PRIMARY KEY,
 task text NOT NULL,
 status text NOT NULL,
 artifact_hash text NOT NULL,
 card jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS model_bundles(
 id uuid PRIMARY KEY,
 status text NOT NULL,
 manifest jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS model_aliases(
 scope text,
 name text,
 bundle_id uuid NOT NULL REFERENCES model_bundles(id),
 version bigint NOT NULL,
 PRIMARY KEY(scope,name)
);

CREATE TABLE IF NOT EXISTS evaluation_results(
 id uuid PRIMARY KEY,
 bundle_id uuid NOT NULL REFERENCES model_bundles(id),
 passed boolean NOT NULL,
 report jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS promotion_audit(
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL,
 old_bundle uuid,
 new_bundle uuid NOT NULL,
 evaluation_id uuid,
 reason text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS inference_logs(
 request_id uuid PRIMARY KEY,
 bundle_id uuid NOT NULL,
 snapshot_id uuid,
 quality text,
 duration_ms bigint,
 error_code text,
 created_at timestamptz NOT NULL DEFAULT now()
);

