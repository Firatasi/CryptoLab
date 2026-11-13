# Multi-stage Dockerfile for CryptoLab Backend (8 Spring Boot Services) + AI (2 FastAPI Services)
# Target platform: Hugging Face Spaces (CPU Basic 16GB RAM, single public port 7860)

FROM maven:3.9-eclipse-temurin-21 AS java-builder
WORKDIR /build

COPY backend ./backend

# Compile and repackage all Spring Boot fat JARs (tests are verified locally / CI)
RUN mvn -B -T 2C -f backend/pom.xml package -DskipTests && \
    mkdir -p /build/jars && \
    cp backend/discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar /build/jars/discovery-server.jar && \
    cp backend/identity-service/target/identity-service-0.0.1-SNAPSHOT.jar /build/jars/identity-service.jar && \
    cp backend/market-data-service/target/market-data-service-0.0.1-SNAPSHOT.jar /build/jars/market-data-service.jar && \
    cp backend/simulation-service/target/simulation-service-0.0.1-SNAPSHOT.jar /build/jars/simulation-service.jar && \
    cp backend/strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar /build/jars/strategy-service.jar && \
    cp backend/reporting-service/target/reporting-service-0.0.1-SNAPSHOT.jar /build/jars/reporting-service.jar && \
    cp backend/account-service/target/account-service-0.0.1-SNAPSHOT.jar /build/jars/account-service.jar && \
    cp backend/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar /build/jars/api-gateway.jar

FROM eclipse-temurin:21-jre AS jre-base

FROM python:3.12-slim AS runtime

# Bring official Java 21 JRE from Temurin image
COPY --from=jre-base /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl openssl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Create Hugging Face standard non-root UID 1000 user
RUN useradd -m -u 1000 user

WORKDIR /app

# Install Python runtime dependencies (covers both serving 8091 and training/PDF 8092)
COPY ai/requirements-training.lock.txt /app/ai/requirements-training.lock.txt
RUN python -m venv /app/.venv && \
    /app/.venv/bin/pip install --no-cache-dir -r /app/ai/requirements-training.lock.txt

# Copy backend JARs and AI code + quantized serving artifacts
COPY --from=java-builder /build/jars /app/jars
COPY ai/app /app/ai/app
COPY ai/artifacts /app/ai/artifacts
COPY ai/configs /app/ai/configs
COPY start-all.sh /app/start-all.sh

RUN mkdir -p /app/logs /tmp/cryptolab-keys && \
    sed -i 's/\r$//' /app/start-all.sh && \
    chmod +x /app/start-all.sh && \
    chown -R user:user /app /tmp/cryptolab-keys

USER user
ENV HOME=/home/user

EXPOSE 7860

CMD ["/app/start-all.sh"]
