-- Aynı oturum ve aynı enstrümanda eşzamanlı birden fazla açık pozisyona izin ver;
-- yeni emir gerçekleştiğinde mevcut açık pozisyon otomatik kapatılmaz.
DROP INDEX IF EXISTS one_open_position;

CREATE INDEX IF NOT EXISTS open_positions_by_session_instrument
    ON positions(session_id, instrument_id)
    WHERE state IN ('OPEN', 'LIQUIDATING');
