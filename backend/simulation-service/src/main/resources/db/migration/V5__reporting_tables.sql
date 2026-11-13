CREATE TABLE IF NOT EXISTS session_metadata (
    session_id uuid PRIMARY KEY REFERENCES simulation_sessions(id),
    name text NOT NULL DEFAULT '',
    deleted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS position_trade_outcomes (
    position_id uuid PRIMARY KEY REFERENCES positions(id),
    close_price numeric(38,18),
    open_fee numeric(38,18) NOT NULL DEFAULT 0,
    close_fee numeric(38,18) NOT NULL DEFAULT 0,
    gross_pnl numeric(38,18) NOT NULL DEFAULT 0,
    funding numeric(38,18) NOT NULL DEFAULT 0,
    liquidation_fee numeric(38,18) NOT NULL DEFAULT 0,
    insurance numeric(38,18) NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);
