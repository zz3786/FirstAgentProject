# Java 后端转 Agent 开发 · 实战项目

> Spring AI 驱动的企业级 Agent 工程底座 —— 从 RAG 到多租户、从六层记忆到多智能体协作与全链路可观测。

---

## 一、项目简介

基于 **Spring Boot 3.5 + Spring AI 1.1.8** 的完整 Agent 工程化项目。目标不是“跑通 demo”，而是构建一套**可运维、可观测、可降级、可多租户、可防御攻击**的中小型企业生产级 Agent 底座。

**已实现核心能力**：

- ✅ RAG 全链路（解析 → 切片 → 向量化 → 混合检索 → TEI 重排 → 注入）
- ✅ 多智能体协作 (Multi-Agent)：主管拆解任务，财务/技术/法务 Worker 并行执行与冲突仲裁
- ✅ MCP 协议原生支持：独立部署的 MCP Server，实现跨语言、热插拔的远程工具接入
- ✅ 六层记忆体系（会话 / 长期 / 偏好 / 兴趣 / 对话历史向量 / 检索画像）
- ✅ 工具调用 + 安全包装（超时 / Fail-Fast 降级 / RBAC 鉴权 / 审计）
- ✅ 主动推荐与澄清判定（检索模糊时反问）
- ✅ 全局安全防护：双重 Prompt 注入拦截（Web 层 + SafeGuardAdvisor）
- ✅ 全链路可观测：基于 Micrometer + Zipkin 的跨线程/SSE 追踪
- ✅ 工程化部署：Docker Compose 多环境编排 + GitHub Actions CI/CD
- ✅ 语义缓存与异步索引优化

---

## 二、技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 21 | 启用虚拟线程处理 IO 密集任务 |
| 核心框架 | Spring Boot / Spring AI | 3.5.15 / 1.1.8 | 业务底座与大模型接入 |
| 向量库 | Qdrant | 1.13.0 | 向量存储与元数据过滤 |
| 缓存/记忆 | Redis Stack | 7.4.0 | 含 RediSearch，用于记忆、语义缓存 |
| 关系库 | MySQL | 5.7.44 | 文档 Chunk 存储与全文检索 (ngram) |
| 消息队列 | RabbitMQ | 3.13 | 多 Agent 异步通信与解耦 |
| 链路追踪 | Zipkin / Brave | 3.6.1 | 跨线程 TraceId 传播与 Span 树 |
| 接口文档 | SpringDoc OpenAPI | 2.8.9 | 自动生成 Swagger UI |
| 嵌入模型 | 阿里云 text-embedding-v4 | 1024 维 | 向量化 |
| 重排模型 | BAAI/bge-reranker (TEI) | CPU 版 | 检索重排降级 |

---

## 三、模块架构

### 3.1 模块依赖（共 11 个 Maven 模块）

项目采用 DDD 领域驱动设计，严格遵循单向依赖原则，无循环依赖：

agent-api (启动模块/Controller) ──> agent-core (核心业务编排)
├── agent-tools (本地 @Tool)
├── agent-memory (六层记忆体系)
├── agent-rag (检索与摄取)
│     └── agent-cache (语义缓存)
├── agent-observability (链路追踪)
├── agent-tool-registry (工具注册)
├── agent-mcp-server (MCP 独立部署)
└── agent-multi-agent (多智能体协作)

| 模块 | 职责 | 依赖内部模块 |
|------|------|:---:|
| agent-common | 共享工具（PromptUtils / 租户 / 审计） | 无 |
| agent-tools | 可被 LLM 调用的本地工具 | 无 |
| agent-cache | 语义缓存 + Embedding 缓存 | 无 |
| agent-memory | 六层记忆体系 | agent-tools |
| agent-rag | RAG 检索 + 文档摄取 | agent-cache, agent-common |
| agent-observability | 链路追踪与上下文传播 | 无 |
| agent-tool-registry | 工具治理与动态刷新 | 无 |
| agent-multi-agent | 多智能体通信与结果聚合 | agent-core 等 |
| agent-core | 业务编排 + Advisor 装配 | 上述全部 |
| agent-api | 接口层与启动类 | agent-core, agent-rag, agent-multi-agent |
| agent-mcp-server | MCP 服务端（独立部署单元） | agent-tools |

---

## 四、核心机制

### 4.1 六层记忆体系

记忆分两类：内容层（记住什么）和模式层（怎么问 / 关心什么）。
1. **会话记忆** (Redis List)：解决“刚才聊了啥”（最近 200 条，超出触发摘要压缩）。
2. **长期记忆** (Redis Hash)：解决“用户告诉过我的重要事实”。
3. **用户偏好** (Redis Hash)：解决“稳定偏好（城市 / 语言）”。
4. **兴趣标签** (Redis Hash)：解决“用户关注什么技术领域”。
5. **对话历史向量** (Redis Vector)：解决“上次类似问题怎么答的”。
6. **检索画像** (Redis Hash)：解决“用户喜欢精确条款还是语义摘要”。

### 4.2 RAG 与多智能体全链路

**文档入库链路**：
目录扫描 → 解析（Tika/PDFBox/POI/OCR） → 切片 → 元数据注入 → 向量化（Qdrant 每批 ≤10 条） → 关键词索引（MySQL） → 指纹记录。

**检索链路**：
用户提问 → 语义缓存命中直接返回 → 未命中则混合检索（向量+关键词） → 加权/RRF融合 → TEI 重排 → 分数过低触发澄清反问 → 注入 Prompt → 回答后追加推荐文档。

**多智能体协作**：
主管 Agent 拆解任务 → 虚拟线程并行分发给财务/技术/法务 Worker → 冲突处理（投票/仲裁） → 结果汇总聚合。

### 4.3 Advisor 链设计 (洋葱模型)

按 order 从小到大执行请求，从大到小执行响应（读 → 模型 → 写）：

| Advisor | order | 职责 |
|---------|:---:|------|
| SafeGuardAdvisor | 0 | 敏感词拦截，短路保护，节省 Token |
| TracingAdvisor | 极小 | 开启全链路 Span |
| MessageChatMemoryAdvisor | 极小 | 读写 CHAT，注入会话历史 |
| CompactingChatMemoryAdvisor | 50 | 超限消息压缩为摘要 |
| PreferenceAdvisor | 100 | 注入用户偏好 |
| UserInterestAdvisor | 120 | 注入兴趣标签 |
| RagAdvisor | 150 | 注入知识库资料 |
| ConversationRetrievalAdvisor | 210 | 检索对话历史向量注入 |
| ConversationMemoryAdvisor | 250 | 本轮对话写入向量库 |

### 4.4 生产级安全与高可用
1. **输入防御**：自研 SecurityInterceptor 拦截恶意 Prompt 注入，SafeGuardAdvisor 二次兜底。
2. **失败降级**：关闭 Spring AI 默认重试，设置 max-attempts=1 实现 Fail-Fast；第三方模型接口超时直接走空结果降级，防止拖垮 Tomcat 线程池。
3. **多租户隔离**：tenantId 贯穿 Redis Key、Qdrant payload、MySQL 列以及所有的检索过滤条件（硬过滤优先级最高）。

---

## 五、主要接口入口

| 接口模块 | 方法/路径 | 说明 |
|---|---|---|
| 基础对话 | GET /chat/streamR | SSE 流式对话（含记忆、工具、RAG、推荐） |
| 多 Agent | POST /multiagent/execute | 触发主管与财务/技术/法务的协同处理 |
| MCP 工具 | POST /mcp/tools/refresh | 手动触发远程 MCP 工具清单刷新 |
| 工作流 | POST /workflow/order/process/{id} | 触发订单条件工作流 |
| 人机协同 | GET /hitl/pending | 查询挂起的审批任务 |
| 系统管理 | POST /config/refresh | 热刷新系统配置 |

---

## 六、快速启动 (Docker Compose)

### 1. 准备环境
确保服务器已安装 Docker 20.10+ 和 Docker Compose v2。

### 2. 配置环境变量
复制模板并填入真实的 API Key 及数据库密码：
cp .env.example .env
vi .env

### 3. 一键启动全套服务
包含中间件、API 服务、MCP 服务及链路追踪：
docker compose --profile app --profile observability up -d --build

### 4. 验证部署
- 健康检查：curl http://localhost:8080/fap/actuator/health (返回 {"status":"UP"})
- Swagger 接口文档：http://localhost:8080/fap/swagger-ui/index.html
- Zipkin 追踪面板：http://localhost:9411/zipkin/

---

## 七、性能压测报告

在本地开发环境对核心接口进行了压力测试：

| 测试目标 | 并发数 | 吞吐量 (RPS) | P95 延迟 | 错误率 |
|---|---|---|---|---|
| 纯 Tomcat (health) | 50 | 268 | 602ms | 0.00% |
| 纯业务查 Redis (sessions) | 20 | 713 | 42ms | 0.00% |
| 大模型流式生成 (streamR) | 3 | 2.2 | 1.2s | 0.00% |

**结论**：框架吞吐量极佳，引入 RAG 和 LLM 后瓶颈转移至第三方大模型接口。通过引入语义缓存与 Fail-Fast 策略，确保了系统在高并发下的稳定性。

---

## 八、演进路线
- ✅ 阶段一：工具调用 + 流式对话 + 三层记忆
- ✅ 阶段二：RAG 混合检索 + 六层记忆 + 多租户隔离 + 异步索引
- ✅ 阶段三：多 Agent 协作 + 工作流编排 + MCP 协议 + 全链路可观测
- ✅ 阶段四：Docker 容器化 + GitHub Actions CI/CD + 性能压测 + 接口文档化
- ⏳ 未来展望：接入 Elasticsearch 提升大规模 RAG 检索性能，引入 K8s 进行集群化部署。

---

## 九、许可
个人学习与生产级实践项目，可自由参考与二次开发。