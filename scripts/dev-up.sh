#!/usr/bin/env bash
# ============================================================
# 本地开发一键起（D78-B）
# 用法：
#   ./scripts/dev-up.sh             # 只起数据层
#   ./scripts/dev-up.sh --with-app  # 加起 agent-api / mcp-server
#   ./scripts/dev-up.sh --all       # 全量（含 Zipkin / TEI）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    cp .env.example .env
    echo "✅ 已创建 .env——请填入 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY"
fi

case "${1:-}" in
    --with-app) docker compose --profile app up -d --build ;;
    --all)      docker compose --profile observability --profile rerank --profile app up -d --build ;;
    *)          docker compose up -d ;;
esac

echo ""
echo "============================================================"
docker compose ps
echo ""
echo "访问入口："
echo "  MySQL       localhost:${MYSQL_PORT:-3306}"
echo "  Redis       localhost:${REDIS_PORT:-6379}"
echo "  Qdrant UI   http://localhost:${QDRANT_PORT:-6333}/dashboard"
echo "  RabbitMQ UI http://localhost:15672"
echo "  Zipkin UI   http://localhost:9411/zipkin/  (需 --all)"
echo "  agent-api   http://localhost:${AGENT_API_PORT:-8080}/fap  (需 --with-app)"
echo "============================================================"