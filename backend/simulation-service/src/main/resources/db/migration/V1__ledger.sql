-- Her simülasyon oturumunun sahibi, başlangıç sermayesi ve durumu.
-- Finans işlemlerinde ortak kilit noktası bu satır olacak.
CREATE TABLE simulation_sessions (
                                     id uuid PRIMARY KEY,
                                     owner_id uuid NOT NULL,

                                     mode text NOT NULL
                                         CHECK (mode IN ('LIVE_PAPER', 'HISTORICAL_REPLAY')),

                                     initial_deposit numeric(38,18) NOT NULL
                                         CHECK (initial_deposit > 0),

                                     rule_profile_id uuid NOT NULL,
                                     risk_policy_id uuid NOT NULL,
                                     risk_policy_version bigint NOT NULL DEFAULT 1,

                                     status text NOT NULL
                                         CHECK (status IN ('RUNNING', 'HALTED', 'COMPLETED')),

                                     engine_sequence bigint NOT NULL DEFAULT 0,
                                     version bigint NOT NULL DEFAULT 1,
                                     created_at timestamptz NOT NULL
);

-- Session içindeki hesaplar.
-- AVAILABLE: kullanılabilir para.
-- RESERVED: bekleyen işlemler için ayrılan para.
-- ISOLATED: pozisyona ayrılan teminat.
-- SYSTEM: sermaye ve diğer karşı kayıt hesapları.
CREATE TABLE wallet_accounts (
                                 id uuid PRIMARY KEY,
                                 session_id uuid NOT NULL REFERENCES simulation_sessions(id),
                                 account_key text NOT NULL,

                                 kind text NOT NULL
                                     CHECK (kind IN ('AVAILABLE', 'RESERVED', 'ISOLATED', 'SYSTEM')),

                                 position_id uuid,
                                 balance numeric(38,18) NOT NULL DEFAULT 0,

                                 UNIQUE (session_id, account_key),

                                 CHECK (
                                     kind NOT IN ('AVAILABLE', 'RESERVED')
                                         OR balance >= 0
                                     )
);

-- Finans hareketinin üst kaydı.
-- business_key aynı hareketin ikinci kez kaydedilmesini engeller.
CREATE TABLE journal_transactions (
                                      id uuid PRIMARY KEY,
                                      session_id uuid NOT NULL REFERENCES simulation_sessions(id),
                                      business_key text NOT NULL UNIQUE,
                                      request_hash text NOT NULL,
                                      effective_at timestamptz NOT NULL,
                                      recorded_at timestamptz NOT NULL,
                                      reason text NOT NULL
);

-- Finans hareketinin hesaplara yansıyan artı/eksi kayıtları.
CREATE TABLE postings (
                          id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          transaction_id uuid NOT NULL REFERENCES journal_transactions(id),
                          account_id uuid NOT NULL REFERENCES wallet_accounts(id),
                          amount numeric(38,18) NOT NULL,
                          currency text NOT NULL CHECK (currency = 'USDT')
);

-- Her journal en az iki posting içermeli ve toplam sıfır olmalı.
CREATE FUNCTION assert_balanced_journal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
jid uuid;
    total numeric;
    cnt bigint;
BEGIN
    IF TG_TABLE_NAME = 'journal_transactions' THEN
        jid := NEW.id;
ELSE
        jid := NEW.transaction_id;
END IF;

SELECT coalesce(sum(amount), 0), count(*)
INTO total, cnt
FROM postings
WHERE transaction_id = jid;

IF total <> 0 OR cnt < 2 THEN
        RAISE EXCEPTION 'UNBALANCED_JOURNAL';
END IF;

RETURN NULL;
END;
$$;

-- Kontrol transaction sonunda yapılır:
-- journal yazıldığında posting'ler henüz eklenmemiş olabilir.
CREATE CONSTRAINT TRIGGER journal_complete
AFTER INSERT ON journal_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION assert_balanced_journal();

CREATE CONSTRAINT TRIGGER posting_balanced
AFTER INSERT ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION assert_balanced_journal();

-- Finans geçmişi değiştirilmez veya silinmez.
-- Düzeltme gerektiğinde ters kayıt ve yeni kayıt oluşturulur.
CREATE FUNCTION immutable_financial_row()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'APPEND_ONLY';
END;
$$;

CREATE TRIGGER no_journal_rewrite
    BEFORE UPDATE OR DELETE ON journal_transactions
FOR EACH ROW
EXECUTE FUNCTION immutable_financial_row();

CREATE TRIGGER no_posting_rewrite
    BEFORE UPDATE OR DELETE ON postings
FOR EACH ROW
EXECUTE FUNCTION immutable_financial_row();

-- Aynı olayın aynı tüketici tarafından tekrar işlenmesini önler.
CREATE TABLE inbox (
                       consumer text NOT NULL,
                       event_id uuid NOT NULL,
                       received_at timestamptz NOT NULL,

                       PRIMARY KEY (consumer, event_id)
);

-- Yayınlanacak olaylar finans işlemiyle aynı transaction'da saklanabilir.
-- Kafka yayıncısı B06 kapsamında bağlanacak.
CREATE TABLE outbox (
                        event_id uuid PRIMARY KEY,
                        topic text NOT NULL,
                        event_key text NOT NULL,
                        payload jsonb NOT NULL,
                        created_at timestamptz NOT NULL,
                        published_at timestamptz,
                        lease_until timestamptz,
                        attempts integer NOT NULL DEFAULT 0
);

-- Tekrarlanan isteklerin önceki sonucunu bulmak için.
CREATE TABLE idempotency_records (
                                     subject uuid NOT NULL,
                                     route text NOT NULL,
                                     scope text NOT NULL,
                                     key text NOT NULL,
                                     request_hash text NOT NULL,
                                     result_ref uuid NOT NULL,
                                     response_json jsonb,

                                     PRIMARY KEY (subject, route, scope, key)
);

-- Alınan piyasa verileri.
CREATE TABLE market_inputs (
                               id uuid PRIMARY KEY,
                               payload jsonb NOT NULL,
                               received_at timestamptz NOT NULL
);

-- Session tarafından işlenmeyi bekleyen piyasa girdileri.
CREATE TABLE pending_engine_inputs (
                                       session_id uuid NOT NULL REFERENCES simulation_sessions(id),
                                       input_id uuid NOT NULL REFERENCES market_inputs(id),
                                       state text NOT NULL DEFAULT 'PENDING',

                                       PRIMARY KEY (session_id, input_id)
);

-- Motorun kabul ettiği girdilerin sıralı geçmişi.
CREATE TABLE engine_input_journal (
                                      session_id uuid NOT NULL REFERENCES simulation_sessions(id),
                                      accepted_sequence bigint NOT NULL,
                                      input_type text NOT NULL,
                                      source_ref text NOT NULL,
                                      payload jsonb NOT NULL,
                                      payload_hash text NOT NULL,

                                      PRIMARY KEY (session_id, accepted_sequence),
                                      UNIQUE (session_id, source_ref)
);