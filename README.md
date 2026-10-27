# Prd2Api

企业内部 PRD 智能解析中台｜SpringBoot 3 + 传统 SSM 分层（controller / service / service.impl / mapper / entity / dto / vo）

上传 PRD、设计文档，经 RAG 检索 + 固定 4 阶段串行 Agent 流水线，自动输出标准化需求评审报告：
业务梳理、需求疑点清单、MySQL DDL、REST 接口契约、研发细分开发任务清单。

> 严格约束：**不生成业务功能代码**，只输出评审产出。无 DDD、无 Manager 层、无聚合根，无 LangChain4j / SpringAI 等 Agent 框架，4 轮串行逻辑由普通 service 编排。

## 技术栈

SpringBoot 3.2.5 | MyBatis-Plus 3.5.5 | MySQL 8 | Redis 6.2+ | Redisson | Sentinel | Micrometer + Prometheus | Chroma（HTTP API）| OkHttp4 | POI + PDFBox | Hutool | SpringDoc OpenAPI3 | Lombok

## 模块说明

| 模块 | 能力 |
| --- | --- |
| 文件接入 | md/txt/docx/pdf；后缀 + 魔数双校验；病毒扫描钩子；本地/OSS 双存储（OSS 为预留适配点）；元信息入库；md5 去重；相似度查重 |
| 配额与租户 | 租户 ID 全链路贯穿；数据库/Redis/Chroma 全租户隔离；每日上传次数、token 上限、并发任务上限；超配额拦截告警；USER/ADMIN 角色 |
| 文档预处理 | HTML/Markdown 清洗、乱码与空白收敛；固定长度 / 标点语义两种分块；分块缓存；超长上下文首尾压缩 |
| RAG | 单任务独立 Chroma collection（租户 + 任务双重隔离）；Embedding 多模型切换；topK 可配；chunk md5 向量缓存；召回失败自动降级本地相似度 |
| Agent 流水线 | 4 阶段串行：业务理解 → 风险扫描 → 结构生成 → 汇总格式化；LLM 超时/指数退避重试/Sentinel 熔断；单阶段失败任务标记 FAIL；支持人工重试与追加追问 |
| 任务与导出 | 全生命周期异步；进度追踪；版本快照；md/html/docx 导出 + 一次性临时凭证 |
| 治理与可观测 | Sentinel 限流、Redis 滑动窗口限流、Redisson 分布式锁、IP/接口黑名单、敏感词过滤；MDC traceId；Micrometer 指标；K8s 健康探针；配置热加载；优雅停机 |

## 包结构

```
com.dbappsecurity.prd2api
├── controller      TaskController / FileController / RagController / AdminController
├── service(.impl)  Task / File / Rag / Agent / LlmInvoke / TenantQuota
├── mapper          PrdTask / PrdTaskCache / TenantQuota / FileMeta（+ resources/mapper/*.xml）
├── entity          PrdTask / PrdTaskCache / TenantQuota / FileMeta
├── dto / vo        请求参数与返回视图
├── config          线程池、异常、WebMvc、Redisson、Sentinel、OpenAPI、OkHttp、刷新、定时清理
├── util            TraceId / TenantContext / File / Chunk / DocParse / Similarity / Sensitive / Metric / ContextCompress / Export / RedisLock / RateLimit
├── constant        提示词、任务状态、Redis Key、模型、错误码
├── rag             EmbeddingService / ChromaVectorService / RagRetrieveService
├── agent           dto.AgentContext + 4 个 Stage service
└── event           TaskSuccessEvent / TaskFailEvent / TaskEventListener
```

## 快速开始

### 1. 依赖服务

1. MySQL 8，执行 [schema.sql](src/main/resources/sql/schema.sql)
2. Redis 6.2+
3. Chroma 服务：`docker run -p 8000:8000 chromadb/chroma`（不可用时自动降级本地召回）
4. OpenAI 兼容的 LLM / Embedding 接口

### 2. 环境变量

```bash
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=prd2api
DB_USER=root
DB_PWD=******
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PWD=
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-xxxx
CHROMA_URL=http://127.0.0.1:8000
UPLOAD_PATH=./data/upload
```

密钥一律通过环境变量注入，禁止提交到仓库。

### 3. 启动

```bash
mvn clean package -DskipTests
java -jar target/prd2api.jar --spring.profiles.active=dev
```

- Swagger：http://127.0.0.1:8080/swagger-ui/index.html
- 健康探针：`/actuator/health`（含 liveness/readiness）
- Prometheus 指标：`/actuator/prometheus`

## 鉴权约定

网关统一鉴权后向下游注入 Header（预留对接企业 SSO，只需替换 `AuthInterceptor` 的解析逻辑）：

| Header | 说明 |
| --- | --- |
| `X-Tenant-Id` | 租户 ID，缺省 `default` |
| `X-User-Key` | 用户标识，缺省 `anonymous` |
| `X-User-Role` | `USER` / `ADMIN`，管理端接口强制 `ADMIN` |
| `X-Trace-Id` | 可选，透传链路 ID，缺省自动生成 |

下载导出文件使用一次性 `exportToken`，有效期默认 300 秒，不依赖上述 Header。

## 接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/file/upload` | 上传文件（multipart，字段 `file`） |
| DELETE | `/api/file/{fileMetaId}` | 删除文件 |
| POST | `/api/task/create` | 创建解析任务（异步） |
| GET | `/api/task/{taskId}` | 任务详情 + 报告 |
| GET | `/api/task/page` | 任务分页（按状态 / fileMetaId 回溯多版本） |
| POST | `/api/task/retry` | 重试失败任务（可换模型） |
| POST | `/api/task/chat` | 追加追问并回写报告 |
| GET | `/api/task/{taskId}/export?format=md\|html\|docx` | 生成导出文件，返回临时凭证 |
| GET | `/api/task/export/download?token=xxx` | 下载导出文件 |
| DELETE | `/api/task/{taskId}` | 删除任务并清理向量 |
| GET | `/api/rag/search?taskId=&query=&topK=` | 召回调试 |
| GET | `/api/rag/status` | Chroma 连通性与配置 |
| GET | `/api/admin/task/page` | 全租户任务分页（ADMIN） |
| GET / POST | `/api/admin/quota/{tenantId}` / `/api/admin/quota` | 查询 / 配置租户配额（ADMIN） |
| DELETE | `/api/admin/tenant/{tenantId}` | 清理租户任务、向量、文件（ADMIN） |
| POST | `/api/admin/clean` | 手工触发过期数据清理（ADMIN） |

## Redis Key 规范

```
prd2api:task:lock:{taskId}
prd2api:task:context:{taskId}
prd2api:rag:embedding:{chunkMd5}
prd2api:rag:collection:{taskId}
prd2api:rag:chunks:{taskId}
prd2api:doc:text:{fileMd5}
prd2api:limit:upload:{tenant}:{user}
prd2api:limit:llm:{tenant}:{user}
prd2api:quota:daily:{tenantId}
prd2api:export:token:{exportToken}
```

## 运维说明

- 定时清理：每日 03:30 清理过期文件、过期报告缓存、超过 24 小时的导出临时文件（`prd2api.clean.cron` 可改）
- 配置热加载：`Prd2ApiProperties` 标注 `@RefreshScope`，接入 Nacos/Apollo 后无需改代码
- 优雅停机：`server.shutdown=graceful`，线程池等待 30 秒
- 指标：任务总数/成功/失败、LLM 调用耗时、token 消耗、RAG 召回耗时
- 告警钩子：`TaskEventListener` 预留消息通知与告警平台对接点

## 已知边界

1. 对象存储模式当前仅预留适配点，需接入 OSS/S3 SDK 后启用（默认 `local`）
2. Chroma 通过 HTTP API 调用（未引入第三方 jar），接口版本差异请按实际服务调整 `/api/v1`
3. 未配置 `LLM_API_KEY` 时任务会明确失败并提示，不影响上传、配额等其它能力
