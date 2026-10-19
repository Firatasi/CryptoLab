-- Kullanıcı bilgileri.
-- Parolanın kendisi değil, BCrypt hash'i saklanır.
CREATE TABLE users (
                       id uuid PRIMARY KEY,
                       normalized_email text NOT NULL UNIQUE,
                       display_name text NOT NULL,
                       password_hash text NOT NULL,
                       status text NOT NULL DEFAULT 'ACTIVE',
                       created_at timestamptz NOT NULL,
                       version bigint NOT NULL DEFAULT 1
);

-- Bir kullanıcı birden fazla role sahip olabilir.
CREATE TABLE user_roles (
                            user_id uuid NOT NULL REFERENCES users(id),
                            role text NOT NULL,

                            CHECK (role IN ('USER', 'TRAINER', 'ADMIN')),
                            PRIMARY KEY (user_id, role)
);

-- MODEL_PUBLISH gibi rollerden ayrı işlem yetkileri.
CREATE TABLE user_authorities (
                                  user_id uuid NOT NULL REFERENCES users(id),
                                  authority text NOT NULL,

                                  PRIMARY KEY (user_id, authority)
);

-- Refresh token'ın yalnız hash'i saklanır.
-- family_id aynı giriş oturumundaki token zincirini temsil eder.
CREATE TABLE refresh_sessions (
                                  id uuid PRIMARY KEY,
                                  user_id uuid NOT NULL REFERENCES users(id),
                                  token_hash text NOT NULL UNIQUE,
                                  family_id uuid NOT NULL,
                                  expires_at timestamptz NOT NULL,
                                  revoked_at timestamptz,
                                  replaced_by uuid,
                                  version bigint NOT NULL DEFAULT 1
);

CREATE INDEX refresh_family
    ON refresh_sessions(family_id);

-- Güvenlik olaylarının kayıt alanı.
CREATE TABLE security_audit (
                                id uuid PRIMARY KEY,
                                actor_id uuid,
                                action text NOT NULL,
                                result text NOT NULL,
                                trace_id text NOT NULL,
                                created_at timestamptz NOT NULL
);

-- Aynı kayıt isteğinin tekrar kullanıcı oluşturmasını önlemek için.
CREATE TABLE registration_requests (
                                       key text PRIMARY KEY,
                                       request_hash text NOT NULL,
                                       user_id uuid NOT NULL REFERENCES users(id)
);