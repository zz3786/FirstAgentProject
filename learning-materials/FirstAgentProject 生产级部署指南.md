# FirstAgentProject 生产级部署指南

本项目采用 Maven 多模块架构，基于 Spring Boot 3.5.x + Spring AI 1.1.8 构建。为了保证环境一致性，全栈依赖（MySQL、Redis、Qdrant、RabbitMQ、MCP Server、Agent API）均采用 Docker Compose 进行统一编排。

## 一、 基础环境要求

在目标服务器（Linux / Windows）上，请确保已安装以下基础软件：

| 软件 | 最低版本要求 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | 项目重度依赖 Java 21 虚拟线程（Virtual Threads） |
| Maven | 3.9+ | 用于本地或 CI 编译打包（部署机可不用） |
| Docker | 20.10+ | 容器运行时 |
| Docker Compose | v2.20+ | 必须支持 --profile 语法 |
| Git | 最新版 | 用于拉取代码 |

## 二、 核心密钥与安全配置 (.env)

所有敏感信息（大模型 Key、数据库密码）严禁写死在代码或 Dockerfile 中。系统启动前，必须在根目录配置 .env 文件。

1. 在项目根目录下复制配置模板：
   cp .env.example .env

2. 打开 .env 文件，填入真实的业务参数（模板如下，请务必修改为你自己的强密码）：

   # --- 大模型 API Key (必填) ---
   DEEPSEEK_API_KEY=sk-你的真实deepseek密钥
   DASHSCOPE_API_KEY=sk-你的真实阿里云百炼密钥

   # --- 基础设施密码 ---
   MYSQL_ROOT_PASSWORD=Fap@Root123
   MYSQL_DATABASE=rag
   MYSQL_USER=rag
   MYSQL_PASSWORD=Fap@Db123

   REDIS_PASSWORD=Fap@Redis123
   QDRANT__SERVICE__API_KEY=Fap@Qdrant123

   RABBITMQ_USER=fap
   RABBITMQ_PASSWORD=Fap@Mq123
   RABBITMQ_VHOST=/fap

> ⚠️ **安全提示**：.env 文件已加入 .gitignore，请勿将其提交至 Git 仓库。如果服务器暴露在公网，请务必开启防火墙（UFW / 安全组），仅放行 8080 等对外端口，**严禁放行 3306、6379、6333 等中间件端口**。

## 三、 服务端口与架构规划

系统由 7 个容器组成，通过内部虚拟网络（fap_default）通信，并依据 profiles 实现按需启动。

| 服务名称 | 容器名称 | 端口映射 (宿主机:容器) | 依赖前置服务 | 启动 Profile |
| :--- | :--- | :--- | :--- | :--- |
| MySQL | fap-mysql | 3306:3306 | 无 | 默认 |
| Redis | fap-redis | 6379:6379 | 无 | 默认 |
| Qdrant | fap-qdrant | 6333:6333 (HTTP)<br>6334:6334 (gRPC) | 无 | 默认 |
| RabbitMQ | fap-rabbitmq | 5672:5672<br>15672:15672 (UI) | 无 | 默认 |
| MCP Server | fap-agent-mcp-server | 8086:8086 | 无 | app |
| Agent API | fap-agent-api | 8080:8080 | MySQL, Redis, Qdrant, MQ, MCP | app |
| Zipkin | fap-zipkin | 9411:9411 | MySQL | observability |

## 四、 生产环境一键部署

1. 拉取代码并进入目录：
   git clone <你的仓库地址>
   cd FirstAgentProject

2. 全量启动核心应用及链路追踪（首次部署必须加 --build）：
   docker compose --profile app --profile observability up -d --build

   *(注：--profile app 会启动 Agent API 和 MCP Server。--profile observability 会启动 Zipkin。如果服务器内存较小，可以去掉 observability 以减少内存占用。)*

3. 日常更新（代码更新后重新发版）：
   git pull origin main
   docker compose --profile app --profile observability up -d --build

## 五、 系统验证与监控入口

部署完成后，等待约 1-2 分钟让 Spring Boot 完成初始化，使用以下命令验证：

1. 检查所有容器健康状态：
   docker compose ps
   *(正常情况：STATUS 列应全部显示 Up (healthy)。)*

2. 验证 API 健康检查：
   curl http://localhost:8080/fap/actuator/health
   *(正常情况：返回 {"status":"UP"})*

3. 查看启动日志（确认工具链是否加载成功）：
   docker compose logs -f agent-api
   *(观察控制台是否有“🚀 FirstAgentProject 已启动！”以及工具链自检报告：12个本地工具+2个MCP工具。)*

## 六、 管理面板说明

系统提供了丰富的可视化管理面板，部署后可在浏览器中访问：

| 面板名称 | 访问地址 | 账号/密码 | 用途 |
| :--- | :--- | :--- | :--- |
| API 接口文档 | http://IP:8080/fap/swagger-ui/index.html | 无 | 测试及查看所有 API 接口 |
| RabbitMQ 控制台 | http://IP:15672 | 见 .env 配置 | 查看多 Agent 通信队列、死信队列 |
| Zipkin 追踪 | http://IP:9411/zipkin/ | 无 | 查看全链路调用 Span 树 |
| Qdrant 面板 | http://IP:6333/dashboard | 无 (内网) | 查看向量库集合与向量数据 |

## 七、 常见问题与运维排查

1. 内存不足导致 OOM / 容器反复重启
    - 原因：GitHub 或低配服务器（<8GB 内存）运行全套服务容易 OOM。
    - 解决：在 .env 中限制 JVM 内存：JAVA_TOOL_OPTIONS=-Xmx512m。若仍不够，请移除 observability 和 rerank profile。

2. 数据清理与重置（慎用）
   如果你修改了初始化 SQL 脚本 (./docker/mysql/init/01-schema.sql)，希望重新建表：
   docker compose --profile app down -v
   docker compose --profile app up -d --build

3. 无法连接大模型 (401 Unauthorized)
    - 原因：.env 中的 API Key 错误或已欠费。
    - 排查：docker compose logs agent-api | grep "401"

4. 磁盘空间告警
   Docker 在长时间运行后会积累大量悬空镜像和构建缓存，可通过以下命令清理：
   docker system prune -f

## 八、 生产环境进阶建议 (Nginx 代理)

生产环境中，建议在 agent-api 前挂载一层 Nginx，处理 HTTPS 卸载和静态资源代理。核心配置参考：

server {
listen 443 ssl;
server_name your-domain.com;

    ssl_certificate /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;

    # 代理 Spring Boot 核心接口
    location /fap/ {
        proxy_pass http://127.0.0.1:8080/fap/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        
        # 针对 SSE 流式对话的关键配置：关闭缓冲
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}

> **重点**：因为系统包含 SSE 流式对话接口，Nginx 必须配置 proxy_buffering off;，否则会导致前端打字机效果失效（消息会积压在一起返回）。