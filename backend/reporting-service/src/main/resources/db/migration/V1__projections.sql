CREATE TABLE session_owners(session_id uuid PRIMARY KEY,owner_id uuid NOT NULL);
CREATE TABLE account_metadata(account_id uuid PRIMARY KEY,session_id uuid NOT NULL,account_key text NOT NULL,kind text NOT NULL,position_id uuid);
CREATE TABLE ledger_projection(transaction_id uuid PRIMARY KEY,session_id uuid NOT NULL,event_id uuid NOT NULL UNIQUE,
 engine_sequence bigint NOT NULL,effective_at timestamptz NOT NULL,payload jsonb NOT NULL);
CREATE TABLE account_projection(account_id uuid PRIMARY KEY,balance numeric(38,18) NOT NULL DEFAULT 0);
CREATE TABLE projection_checkpoints(session_id uuid PRIMARY KEY,last_sequence bigint NOT NULL DEFAULT 0,as_of timestamptz NOT NULL);
CREATE TABLE inbox(consumer text NOT NULL,event_id uuid NOT NULL,PRIMARY KEY(consumer,event_id));
CREATE TABLE equity_samples(session_id uuid NOT NULL,engine_sequence bigint NOT NULL,as_of timestamptz NOT NULL,
 equity numeric(38,18) NOT NULL,unrealized_pnl numeric(38,18) NOT NULL,quality text NOT NULL,PRIMARY KEY(session_id,engine_sequence));
CREATE TABLE trade_summaries(position_id uuid PRIMARY KEY,session_id uuid NOT NULL,gross numeric(38,18) NOT NULL DEFAULT 0,
 fees numeric(38,18) NOT NULL DEFAULT 0,funding numeric(38,18) NOT NULL DEFAULT 0,
 liquidation_fees numeric(38,18) NOT NULL DEFAULT 0,insurance numeric(38,18) NOT NULL DEFAULT 0,closed_at timestamptz);
CREATE TABLE notifications(id uuid PRIMARY KEY,owner_id uuid NOT NULL,source_event_id uuid NOT NULL UNIQUE,
 type text NOT NULL,payload jsonb NOT NULL,read_at timestamptz,created_at timestamptz NOT NULL);
CREATE TABLE report_jobs(id uuid PRIMARY KEY,owner_id uuid NOT NULL,session_id uuid NOT NULL,status text NOT NULL,
 artifact_id uuid,created_at timestamptz NOT NULL);

