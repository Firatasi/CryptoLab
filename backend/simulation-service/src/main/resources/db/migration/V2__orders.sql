CREATE TABLE instrument_generations(session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 instrument_id uuid NOT NULL,generation bigint NOT NULL DEFAULT 1,PRIMARY KEY(session_id,instrument_id));
CREATE TABLE execution_plans(plan_id uuid NOT NULL,revision integer NOT NULL CHECK(revision>0),
 session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 canonical_json jsonb NOT NULL,hash text NOT NULL,position_generation bigint NOT NULL,
 valid_until timestamptz NOT NULL,state text NOT NULL,PRIMARY KEY(plan_id,revision));
CREATE TABLE commands(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 type text NOT NULL,payload jsonb NOT NULL,status text NOT NULL,reason text,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL);
CREATE TABLE approvals(id uuid PRIMARY KEY,owner_id uuid NOT NULL,plan_id uuid NOT NULL,
 revision integer NOT NULL,hash text NOT NULL,consumed_command_id uuid NOT NULL REFERENCES commands(id),
 UNIQUE(plan_id,revision),FOREIGN KEY(plan_id,revision) REFERENCES execution_plans(plan_id,revision));
CREATE TABLE positions(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 side text NOT NULL,q numeric(38,18) NOT NULL,entry_price numeric(38,18) NOT NULL,
 original_margin numeric(38,18) NOT NULL,initial_margin_remaining numeric(38,18) NOT NULL,
 state text NOT NULL,generation bigint NOT NULL,version bigint NOT NULL DEFAULT 1,
 stop_loss numeric(38,18),take_profit numeric(38,18),deadline_at timestamptz NOT NULL,
 expiry_policy text NOT NULL,horizon_seconds integer NOT NULL,
 close_requested boolean NOT NULL DEFAULT false,opened_at timestamptz NOT NULL,closed_at timestamptz);
CREATE UNIQUE INDEX one_open_position ON positions(session_id,instrument_id) WHERE state IN ('OPEN','LIQUIDATING');
CREATE TABLE orders(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 side text NOT NULL,type text NOT NULL,q numeric(38,18) NOT NULL CHECK(q>0),status text NOT NULL,
 reserved_amount numeric(38,18) NOT NULL DEFAULT 0,position_id uuid REFERENCES positions(id),reduce_only boolean NOT NULL,
 limit_price numeric(38,18),reason text,version bigint NOT NULL DEFAULT 1,
 command_id uuid NOT NULL UNIQUE REFERENCES commands(id),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL);
CREATE TABLE fills(id uuid PRIMARY KEY,order_id uuid NOT NULL UNIQUE REFERENCES orders(id),q numeric(38,18) NOT NULL,
 price numeric(38,18) NOT NULL,fee numeric(38,18) NOT NULL,quote_id uuid NOT NULL,
 execution_model_version text NOT NULL,engine_sequence bigint NOT NULL,time timestamptz NOT NULL);

