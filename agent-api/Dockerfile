# ============================================================
# agent-api 生产镜像（D78-B）
# ------------------------------------------------------------
# 多阶段：builder（Maven 构建）→ runtime（JRE 21 运行）
# 安全：非 root + tini + healthcheck
# ============================================================

FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

# ★ 先 copy 所有 pom.xml——利用 Docker layer cache
COPY pom.xml ./
COPY agent-common/pom.xml           agent-common/
COPY agent-tool-registry/pom.xml    agent-tool-registry/
COPY agent-tools/pom.xml            agent-tools/
COPY agent-cache/pom.xml            agent-cache/
COPY agent-memory/pom.xml           agent-memory/
COPY agent-rag/pom.xml              agent-rag/
COPY agent-core/pom.xml             agent-core/
COPY agent-observability/pom.xml    agent-observability/
COPY agent-multi-agent/pom.xml      agent-multi-agent/
COPY agent-api/pom.xml              agent-api/
COPY agent-mcp-server/pom.xml       agent-mcp-server/

RUN mvn -B -q dependency:go-offline -DskipTests || true

# 再 copy 源码
COPY agent-common/src           agent-common/src
COPY agent-tool-registry/src    agent-tool-registry/src
COPY agent-tools/src            agent-tools/src
COPY agent-cache/src            agent-cache/src
COPY agent-memory/src           agent-memory/src
COPY agent-rag/src              agent-rag/src
COPY agent-core/src             agent-core/src
COPY agent-observability/src    agent-observability/src
COPY agent-multi-agent/src      agent-multi-agent/src
COPY agent-api/src              agent-api/src
COPY agent-mcp-server/src       agent-mcp-server/src

RUN mvn -B -q -pl agent-api -am package -DskipTests

# ============================================================
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget tini

# ★ 非 root
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D -s /bin/sh appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /build/agent-api/target/*.jar /app/app.jar
RUN mkdir -p /app/data /app/logs && chown -R appuser:appgroup /app

USER appuser

HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=10 \
    CMD wget -q -O- http://127.0.0.1:8080/fap/actuator/health | grep UP || exit 1

EXPOSE 8080
ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/app.jar"]