# ============================================================
# agent-mcp-server 生产镜像（D78-B）
# ------------------------------------------------------------
# 与 agent-api 同构——两个独立部署单元
# ============================================================

FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml ./
COPY agent-common/pom.xml           agent-common/
COPY agent-tool-registry/pom.xml    agent-tool-registry/
COPY agent-tools/pom.xml            agent-tools/
COPY agent-mcp-server/pom.xml       agent-mcp-server/

RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY agent-common/src           agent-common/src
COPY agent-tool-registry/src    agent-tool-registry/src
COPY agent-tools/src            agent-tools/src
COPY agent-mcp-server/src       agent-mcp-server/src

RUN mvn -B -q -pl agent-mcp-server -am package -DskipTests

# ============================================================
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget tini
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D -s /bin/sh appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appgroup /build/agent-mcp-server/target/*.jar /app/app.jar
RUN mkdir -p /app/data /app/logs && chown -R appuser:appgroup /app

USER appuser

HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=10 \
    CMD wget -q -O- http://127.0.0.1:8086/actuator/health | grep UP || exit 1

EXPOSE 8086
ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/app.jar"]