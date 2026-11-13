-- CryptoLab Neon PostgreSQL İlk Şema Kurulumu
-- Neon SQL Editor üzerinde bir kez çalıştırın (veya start-all.sh içinde Flyway create-schemas=true otomatik açılır).

CREATE SCHEMA IF NOT EXISTS identity_db;
CREATE SCHEMA IF NOT EXISTS market_db;
CREATE SCHEMA IF NOT EXISTS simulation_db;
CREATE SCHEMA IF NOT EXISTS strategy_db;
CREATE SCHEMA IF NOT EXISTS reporting_db;
