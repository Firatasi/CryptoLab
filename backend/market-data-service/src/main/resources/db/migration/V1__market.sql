CREATE TABLE instruments(id uuid PRIMARY KEY,exchange_id text NOT NULL,exchange_symbol text NOT NULL,
                         market_type text NOT NULL,contract_identity text NOT NULL,base_asset text NOT NULL,quote_asset text NOT NULL,
                         settlement_asset text,status text NOT NULL,onboard_at timestamptz,observed_at timestamptz NOT NULL,
                         UNIQUE(exchange_id,exchange_symbol,market_type,contract_identity));
CREATE TABLE instrument_versions(instrument_id uuid NOT NULL REFERENCES instruments(id),version bigint NOT NULL,
                                 metadata jsonb NOT NULL,checksum text NOT NULL,observed_at timestamptz NOT NULL,effective_at timestamptz,
                                 PRIMARY KEY(instrument_id,version));
CREATE TABLE candles(instrument_id uuid NOT NULL REFERENCES instruments(id),interval text NOT NULL,price_kind text NOT NULL,
                     open_time timestamptz NOT NULL,close_time timestamptz NOT NULL,available_at timestamptz NOT NULL,
                     open numeric(38,18) NOT NULL,high numeric(38,18) NOT NULL,low numeric(38,18) NOT NULL,close numeric(38,18) NOT NULL,
                     volume numeric(38,18),finalized boolean NOT NULL,
                     PRIMARY KEY(instrument_id,interval,price_kind,open_time));
CREATE TABLE funding_rates(instrument_id uuid NOT NULL,settlement_time timestamptz NOT NULL,
                           rate numeric(38,18) NOT NULL,settlement_mark numeric(38,18) NOT NULL,source_id text NOT NULL,
                           PRIMARY KEY(instrument_id,settlement_time));
CREATE TABLE data_gaps(id uuid PRIMARY KEY,instrument_id uuid NOT NULL,stream text NOT NULL,
                       start_at timestamptz NOT NULL,end_at timestamptz,reason text NOT NULL,resolution_status text NOT NULL);
CREATE TABLE coverage_ranges(instrument_id uuid NOT NULL,price_kind text NOT NULL,interval text NOT NULL,
                             covered_from timestamptz NOT NULL,covered_to timestamptz NOT NULL,completeness text NOT NULL,
                             PRIMARY KEY(instrument_id,price_kind,interval,covered_from));
CREATE TABLE backfill_jobs(id uuid PRIMARY KEY,range_hash text NOT NULL UNIQUE,request jsonb NOT NULL,
                           status text NOT NULL,lease_until timestamptz);
CREATE TABLE rule_profiles(id uuid PRIMARY KEY,version bigint NOT NULL,profile jsonb NOT NULL,
                           source_type text NOT NULL,checksum text NOT NULL,fetched_at timestamptz NOT NULL);
CREATE TABLE export_jobs(id uuid PRIMARY KEY,request jsonb NOT NULL,status text NOT NULL,
                         artifact_id uuid,sha256 text,manifest jsonb,created_at timestamptz NOT NULL);
CREATE TABLE outbox(event_id uuid PRIMARY KEY,topic text NOT NULL,event_key text NOT NULL,
                    payload jsonb NOT NULL,created_at timestamptz NOT NULL,published_at timestamptz,lease_until timestamptz,attempts int NOT NULL DEFAULT 0);