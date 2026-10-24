-- Varsayılan mod/para birimi atamak yerine audit manifestinden doğrula ve doldur.
ALTER TABLE session_owners ADD COLUMN mode text;
ALTER TABLE session_owners ADD COLUMN currency text;
ALTER TABLE trade_summaries ADD COLUMN instrument_id uuid;
ALTER TABLE trade_summaries ADD COLUMN symbol text;
ALTER TABLE trade_summaries ADD COLUMN side text;
ALTER TABLE trade_summaries ADD COLUMN status text;
ALTER TABLE trade_summaries ADD COLUMN opened_at timestamptz;
CREATE INDEX owner_session_lookup ON session_owners(owner_id,mode,currency,session_id);
CREATE INDEX trade_history_seek ON trade_summaries(opened_at DESC,position_id DESC);
-- Bu migration tek başına coverage=READY yapmaz; bootstrap/metadata consumer tamamlanmalıdır.

