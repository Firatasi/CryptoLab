-- Son görülme zamanı (canlı aktif kullanıcı ölçümü için)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;

-- Sistemde aynı anda birden fazla ADMIN hesabının oluşmasını DB düzeyinde engelle
CREATE UNIQUE INDEX IF NOT EXISTS one_admin_role_only
    ON user_roles (role)
    WHERE role = 'ADMIN';

-- Kayıt ekranı tek kullanımlık sunucu CAPTCHA doğrulamaları
CREATE TABLE IF NOT EXISTS captcha_challenges (
    id uuid PRIMARY KEY,
    answer_hash text NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS captcha_expires_idx
    ON captcha_challenges (expires_at);

-- İletişim formu kayıtları (support@cryptolab.dev / Admin paneli)
CREATE TABLE IF NOT EXISTS contact_messages (
    id uuid PRIMARY KEY,
    recipient_email text NOT NULL DEFAULT 'support@cryptolab.dev',
    sender_name text NOT NULL,
    sender_email text NOT NULL,
    subject text NOT NULL,
    message text NOT NULL,
    status text NOT NULL DEFAULT 'NEW',
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    CHECK (status IN ('NEW', 'READ', 'RESOLVED'))
);

CREATE INDEX IF NOT EXISTS contact_messages_created_idx
    ON contact_messages (created_at DESC);
