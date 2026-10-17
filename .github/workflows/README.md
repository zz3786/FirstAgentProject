# CI 使用说明（D78-C）

本目录下的 `ci.yml` 定义了 GitHub Actions 自动化流水线。每次提交代码，GitHub 都会在云端自动帮你进行编译、构建镜像和冒烟测试。

## 一、必需配置的 Secrets（关键！）

因为云端的机器没有你的真实大模型 API Key，但 Spring AI 启动时会校验 Key 不能为空，所以必须在 GitHub 配置“假密钥”骗过启动检查。

**配置位置**：GitHub 仓库页面 → `Settings` → `Secrets and variables` → `Actions` → `New repository secret`

| Name | 值 | 说明 |
|---|---|---|
| `CI_DEEPSEEK_API_KEY` | 任意非空字符串，如 `sk-ci-fake` | Spring AI OpenAI 启动校验用，CI 不真调 LLM |
| `CI_DASHSCOPE_API_KEY` | 任意非空字符串，如 `sk-ci-fake` | 同上（Embedding 模型启动校验） |

> **注意**：如果不配置这两个 Secret，CI 脚本里也写了默认的假值（`sk-ci-fake-key-for-smoke-test`），但强烈建议手动配置，避免以后框架升级导致默认值失效。

## 二、触发机制

| 事件 | 跑的 Job |
|---|---|
| 发起 `Pull Request` 到 main | `build-test` → `docker-build` |
| 提交（push）到 `main` | `build-test` → `docker-build` → `smoke-test` |
| 打标签（tag `v*`） | 全量 + `publish` 推送到 ghcr.io |
| 手动 | Actions 页面 → CI → Run workflow |

## 三、本地复现 CI

如果你想在本地验证 CI 能不能过，可以执行以下命令：

```bash
# 1. 等价于 build-test job
mvn -B -ntp clean verify

# 2. 等价于 docker-build job
docker build -f agent-api/Dockerfile -t fap/agent-api:local .
docker build -f agent-mcp-server/Dockerfile -t fap/agent-mcp-server:local .

# 3. 等价于 smoke-test job
cp .env.example .env
# 填入假 Key（随便填个非空字符串）
docker compose --profile app up -d --build
docker compose ps
curl http://localhost:8080/fap/actuator/health
docker compose --profile app down -v