CREATE TABLE funding_boundaries(position_id uuid NOT NULL REFERENCES positions(id),funding_time timestamptz NOT NULL,
 q_at_boundary numeric(38,18) NOT NULL,side text NOT NULL,instrument_id uuid NOT NULL,
 PRIMARY KEY(position_id,funding_time));
CREATE TABLE funding_settlements(position_id uuid NOT NULL REFERENCES positions(id),funding_time timestamptz NOT NULL,
 q_at_boundary numeric(38,18) NOT NULL,rate numeric(38,18) NOT NULL,mark numeric(38,18) NOT NULL,
 amount numeric(38,18) NOT NULL,from_a numeric(38,18) NOT NULL,from_b numeric(38,18) NOT NULL,
 late boolean NOT NULL,journal_id uuid NOT NULL REFERENCES journal_transactions(id),PRIMARY KEY(position_id,funding_time));
CREATE TABLE due_actions(position_id uuid NOT NULL REFERENCES positions(id),generation bigint NOT NULL,
 deadline_at timestamptz NOT NULL,kind text NOT NULL,status text NOT NULL,command_id uuid,
 PRIMARY KEY(position_id,generation,kind));
CREATE TABLE daily_risk(session_id uuid NOT NULL REFERENCES simulation_sessions(id),utc_day date NOT NULL,
 start_equity numeric(38,18) NOT NULL,net_flows numeric(38,18) NOT NULL DEFAULT 0,
 PRIMARY KEY(session_id,utc_day));
CREATE TABLE halt_reasons(session_id uuid NOT NULL REFERENCES simulation_sessions(id),reason text NOT NULL,
 created_at timestamptz NOT NULL,PRIMARY KEY(session_id,reason));