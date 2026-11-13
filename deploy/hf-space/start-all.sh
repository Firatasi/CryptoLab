#!/usr/bin/env bash
set -euo pipefail

echo "[boot] CryptoLab Hugging Face Spaces Runtime starting..."

PYTHON_BIN="/app/.venv/bin/python"
UVICORN_BIN="/app/.venv/bin/uvicorn"

# 1. Validate required Hugging Face Secrets (never print secret values)
require_env() {
  local name="$1"
  local min_len="${2:-1}"
  local val="${!name:-}"
  if [[ -z "$val" || "${#val}" -lt "$min_len" ]]; then
    echo "[ERROR] Missing or too-short secret '$name' (minimum length: $min_len)." >&2
    exit 1
  fi
}

require_env "WEB_ORIGIN" 8
WEB_ORIGIN="${WEB_ORIGIN%/}"
if [[ "$WEB_ORIGIN" != https://* ]]; then
  echo "[ERROR] WEB_ORIGIN must start with https:// (got non-https origin)." >&2
  exit 1
fi

require_env "ADMIN_BOOTSTRAP_PASSWORD" 14
require_env "REGISTRATION_HASH_KEY" 32
require_env "MODEL_SERVICE_SECRET" 32
require_env "TRAINING_SERVICE_SECRET" 32
require_env "REPORTING_CURSOR_SECRET" 32

if [[ -z "${DATABASE_URL:-}" && ( -z "${NEON_BASE_JDBC_URL:-}" || -z "${DB_USERNAME:-}" || -z "${DB_PASSWORD:-}" ) ]]; then
  echo "[ERROR] Provide DATABASE_URL (Neon postgresql://...) or NEON_BASE_JDBC_URL + DB_USERNAME + DB_PASSWORD." >&2
  exit 1
fi

# 2. Parse Neon connection string cleanly via Python and write protected env file
DB_ENV_FILE="/tmp/cryptolab-db.env"
umask 077
"$PYTHON_BIN" - <<'PY' > "$DB_ENV_FILE"
import os, shlex, urllib.parse

raw_url = (os.environ.get("DATABASE_URL") or "").strip()
if raw_url:
    if raw_url.startswith("jdbc:postgresql://"):
        raw_url = "postgresql://" + raw_url[len("jdbc:postgresql://"):]
    elif raw_url.startswith("postgres://"):
        raw_url = "postgresql://" + raw_url[len("postgres://"):]
    parsed = urllib.parse.urlparse(raw_url)
    host = parsed.hostname or ""
    port = parsed.port or 5432
    dbname = (parsed.path or "/neondb").lstrip("/") or "neondb"
    user = urllib.parse.unquote(parsed.username or "") or os.environ.get("DB_USERNAME", "")
    password = urllib.parse.unquote(parsed.password or "") or os.environ.get("DB_PASSWORD", "")
    q = urllib.parse.parse_qs(parsed.query, keep_blank_values=False)
    sslmode = (q.get("sslmode") or ["require"])[0]
    if not host or not user or not password:
        raise SystemExit("[ERROR] DATABASE_URL must include host, username, and password.")
    base_jdbc = f"jdbc:postgresql://{host}:{port}/{dbname}?sslmode={urllib.parse.quote(sslmode)}"
else:
    base_jdbc = os.environ["NEON_BASE_JDBC_URL"].strip()
    user = os.environ["DB_USERNAME"]
    password = os.environ["DB_PASSWORD"]

print(f"export DB_USERNAME={shlex.quote(user)}")
print(f"export DB_PASSWORD={shlex.quote(password)}")
print(f"export NEON_BASE_JDBC_URL={shlex.quote(base_jdbc)}")
PY
source "$DB_ENV_FILE"

schema_jdbc() {
  local schema="$1"
  if [[ "$NEON_BASE_JDBC_URL" == *"?"* ]]; then
    echo "${NEON_BASE_JDBC_URL}&currentSchema=${schema}"
  else
    echo "${NEON_BASE_JDBC_URL}?currentSchema=${schema}"
  fi
}

# 3. Prepare RSA-2048 JWT keys (prefer persistent PEM secrets; fallback to ephemeral PKCS#8 generation)
KEY_DIR="/tmp/cryptolab-keys"
mkdir -p "$KEY_DIR"
PRIVATE_PEM="${KEY_DIR}/private.pem"
PUBLIC_PEM="${KEY_DIR}/public.pem"

if [[ -n "${JWT_PRIVATE_KEY_PEM:-}" && -n "${JWT_PUBLIC_KEY_PEM:-}" ]]; then
  "$PYTHON_BIN" - <<'PY'
import os
from pathlib import Path

priv = os.environ["JWT_PRIVATE_KEY_PEM"].replace("\\n", "\n").strip() + "\n"
pub = os.environ["JWT_PUBLIC_KEY_PEM"].replace("\\n", "\n").strip() + "\n"
p_priv = Path("/tmp/cryptolab-keys/private.pem")
p_pub = Path("/tmp/cryptolab-keys/public.pem")
p_priv.write_text(priv, encoding="utf-8")
p_pub.write_text(pub, encoding="utf-8")
os.chmod(p_priv, 0o600)
os.chmod(p_pub, 0o644)
PY
  echo "[boot] Loaded persistent RSA JWT keys from secrets."
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_PEM" 2>/dev/null
  openssl pkey -in "$PRIVATE_PEM" -pubout -out "$PUBLIC_PEM" 2>/dev/null
  chmod 600 "$PRIVATE_PEM"
  chmod 644 "$PUBLIC_PEM"
  echo "[WARN] Ephemeral RSA JWT keypair generated on boot. Set JWT_PRIVATE_KEY_PEM & JWT_PUBLIC_KEY_PEM for cross-restart token persistence."
fi

# 4. Shared production environment exports
export SPRING_PROFILES_ACTIVE="prod"
export APP_ENV="production"
export COOKIE_SECURE="true"
export TRUST_FORWARDED_HEADERS="true"
export APP_WEB_ORIGIN="$WEB_ORIGIN"
export JWT_ISSUER="${JWT_ISSUER:-$WEB_ORIGIN}"
export JWT_AUDIENCE="${JWT_AUDIENCE:-cryptolab-api}"
export JWT_JWKS_URL="http://127.0.0.1:8081/.well-known/jwks.json"
export JWT_PRIVATE_KEY_PATH="$PRIVATE_PEM"
export JWT_PUBLIC_KEY_PATH="$PUBLIC_PEM"
export ADMIN_BOOTSTRAP_EMAIL="${ADMIN_BOOTSTRAP_EMAIL:-admin@cryptolab.dev}"
export SHOW_SQL="false"
export FORMAT_SQL="false"

# Neon serverless Hikari & Flyway settings
export SPRING_FLYWAY_CREATE_SCHEMAS="true"
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="4"
export SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE="0"
export SPRING_DATASOURCE_HIKARI_MAX_LIFETIME="300000"
export SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT="15000"

# Single-container runtime without Kafka/Redis brokers
export MANAGEMENT_HEALTH_REDIS_ENABLED="false"
export MANAGEMENT_HEALTH_KAFKA_ENABLED="false"
export SPRING_KAFKA_LISTENER_AUTO_STARTUP="false"
export MARKET_SCHEDULING_ENABLED="false"

# Fast internal Eureka service discovery
export EUREKA_SERVER_RESPONSE_CACHE_UPDATE_INTERVAL_MS="3000"
export EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS="5"
export EUREKA_INSTANCE_LEASE_RENEWAL_INTERVAL_IN_SECONDS="10"

# Internal service mesh addresses
export MARKET_DATA_URL="http://127.0.0.1:8082"
export SIMULATION_SERVICE_URL="http://127.0.0.1:8083"
export MODEL_BASE_URL="http://127.0.0.1:8091"
export TRAINING_BASE_URL="http://127.0.0.1:8092"
export ARTIFACTS_DIR="/app/ai/artifacts"

COMMON_JVM_OPTS="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dserver.error.include-stacktrace=never"

PIDS=()
cleanup() {
  echo "[shutdown] Stopping CryptoLab services..."
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup TERM INT EXIT

wait_http() {
  local name="$1"
  local url="$2"
  local pid="$3"
  local max_sec="${4:-120}"
  local elapsed=0
  while (( elapsed < max_sec )); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[ERROR] Process '$name' exited prematurely." >&2
      return 1
    fi
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "[ready] $name"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "[ERROR] Timed out waiting for '$name' at $url" >&2
  return 1
}

wait_tcp() {
  local name="$1"
  local port="$2"
  local pid="$3"
  local max_sec="${4:-60}"
  local elapsed=0
  while (( elapsed < max_sec )); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[ERROR] Process '$name' exited prematurely." >&2
      return 1
    fi
    if "$PYTHON_BIN" -c "import socket; s=socket.create_connection(('127.0.0.1', ${port}), 1); s.close()" 2>/dev/null; then
      echo "[ready] $name"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "[ERROR] Timed out waiting for '$name' on port $port" >&2
  return 1
}

start_java() {
  local name="$1"
  local jar="$2"
  local mem="$3"
  shift 3
  env "$@" java $mem $COMMON_JVM_OPTS -jar "/app/jars/${jar}" > >(stdbuf -oL sed -u "s/^/[${name}] /") 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  LAST_PID="$pid"
}

# Stage 1: Eureka Discovery Server
start_java "discovery" "discovery-server.jar" "-Xms128m -Xmx256m"
DISCOVERY_PID="$LAST_PID"
wait_http "discovery-server" "http://127.0.0.1:8761/actuator/health" "$DISCOVERY_PID" 120

# Stage 2: Identity Service (JWKS provider must be up before downstream resource servers)
start_java "identity" "identity-service.jar" "-Xms192m -Xmx512m" \
  DB_URL="$(schema_jdbc identity_db)"
IDENTITY_PID="$LAST_PID"
wait_http "identity-service" "http://127.0.0.1:8081/.well-known/jwks.json" "$IDENTITY_PID" 120

# Stage 3: Market Data + Python AI Serving (8091) & AI Training (8092)
start_java "market-data" "market-data-service.jar" "-Xms192m -Xmx512m" \
  DB_URL="$(schema_jdbc market_db)"
MARKET_PID="$LAST_PID"

(cd /app/ai && exec "$UVICORN_BIN" app.main:app --host 127.0.0.1 --port 8091 --no-access-log) > >(stdbuf -oL sed -u "s/^/[ai-model] /") 2>&1 &
AI_MODEL_PID=$!
PIDS+=("$AI_MODEL_PID")

(cd /app/ai && exec "$UVICORN_BIN" app.api.training_api:create_training_app --host 127.0.0.1 --port 8092 --factory --no-access-log) > >(stdbuf -oL sed -u "s/^/[ai-training] /") 2>&1 &
AI_TRAIN_PID=$!
PIDS+=("$AI_TRAIN_PID")

wait_http "market-data-service" "http://127.0.0.1:8082/actuator/health" "$MARKET_PID" 120
wait_tcp "ai-model" 8091 "$AI_MODEL_PID" 60
wait_tcp "ai-training" 8092 "$AI_TRAIN_PID" 60

# Stage 4: Core Domain & Financial Services
start_java "simulation" "simulation-service.jar" "-Xms256m -Xmx768m" \
  DB_URL="$(schema_jdbc simulation_db)"
SIM_PID="$LAST_PID"

start_java "reporting" "reporting-service.jar" "-Xms192m -Xmx512m" \
  DB_URL="$(schema_jdbc reporting_db)"
REPORT_PID="$LAST_PID"

start_java "strategy" "strategy-service.jar" "-Xms192m -Xmx512m" \
  SPRING_PROFILES_ACTIVE="prod,training-http" \
  DB_URL="$(schema_jdbc strategy_db)"
STRATEGY_PID="$LAST_PID"

start_java "account" "account-service.jar" "-Xms128m -Xmx320m"
ACCOUNT_PID="$LAST_PID"

wait_http "simulation-service" "http://127.0.0.1:8083/actuator/health" "$SIM_PID" 120
wait_http "reporting-service" "http://127.0.0.1:8085/actuator/health" "$REPORT_PID" 120
wait_http "strategy-service" "http://127.0.0.1:8084/actuator/health" "$STRATEGY_PID" 120
wait_http "account-service" "http://127.0.0.1:8086/actuator/health" "$ACCOUNT_PID" 120

# Allow Eureka registry cache (3s) + client fetch (5s) to converge before gateway traffic arrives
sleep 6

# Stage 5: Public API Gateway on Hugging Face Port 7860
export SERVER_ADDRESS="0.0.0.0"
export SERVER_PORT="${PORT:-7860}"
start_java "api-gateway" "api-gateway.jar" "-Xms192m -Xmx512m"
GATEWAY_PID="$LAST_PID"
wait_http "api-gateway" "http://127.0.0.1:${SERVER_PORT}/actuator/health" "$GATEWAY_PID" 120

echo "[ready] CryptoLab unified stack listening on 0.0.0.0:${SERVER_PORT}"

# Exit immediately if any core child process crashes so HF Spaces restarts cleanly
wait -n
echo "[ERROR] One of CryptoLab services exited unexpectedly; terminating container." >&2
exit 1
