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
| 登录与 VIP | 账号密码登录（BCrypt + Redis 不透明令牌，12h 滑动续期）；连续 5 次失败锁定 15 分钟；用户级 VIP 套餐（FREE/BASIC/PRO）；兑换码自助兑换；VIP 生效用用户额度，否则回退租户配额；每日定时降级 |
| 文档预处理 | HTML/Markdown 清洗、乱码与空白收敛；固定长度 / 标点语义两种分块；分块缓存；超长上下文首尾压缩 |
| RAG | 单任务独立 Chroma collection（租户 + 任务双重隔离）；Embedding 多模型切换；topK 可配；chunk md5 向量缓存；召回失败自动降级本地相似度 |
| Agent 流水线 | 4 阶段串行：业务理解 → 风险扫描 → 结构生成 → 汇总格式化；LLM 超时/指数退避重试/Sentinel 熔断；单阶段失败任务标记 FAIL；支持人工重试与追加追问 |
| 任务与导出 | 全生命周期异步；进度追踪；版本快照；md/html/docx 导出 + 一次性临时凭证 |
| 任务协作 | SSE 实时进度推送；任务取消（阶段边界中断）；同文档多版本报告 diff；待确认清单结构化抽取 |
| 管理与审计 | 统计大盘（总览 / 日趋势 / token Top 租户）；操作审计（AOP 注解 + 租户隔离查询 + 过期清理） |
| 治理与可观测 | Sentinel 限流、Redis 滑动窗口限流、Redisson 分布式锁、IP/接口黑名单、敏感词过滤；MDC traceId；Micrometer 指标；K8s 健康探针；配置热加载；优雅停机 |

## 包结构

```
com.dbappsecurity.prd2api
├── controller      TaskController / FileController / RagController / AdminController / AuthController / VipController / AdminVipController
├── service(.impl)  Task / File / Rag / Agent / LlmInvoke / TenantQuota / QuotaResolver / Auth / Vip / TaskStream / Stats / AuditLog
├── mapper          PrdTask / PrdTaskCache / TenantQuota / FileMeta / AuditLog / SysUser / VipPlan / UserVip / RedeemCode / UserDailyUsage（+ resources/mapper/*.xml）
├── entity          PrdTask / PrdTaskCache / TenantQuota / FileMeta / AuditLog / SysUser / VipPlan / UserVip / RedeemCode / UserDailyUsage
├── dto / vo        请求参数与返回视图
├── annotation      @Audit 操作审计注解
├── aspect          AuditLogAspect 审计切面
├── config          线程池、异常、WebMvc、Redisson、Sentinel、OpenAPI、OkHttp、刷新、定时清理
├── util            TraceId / TenantContext / File / Chunk / DocParse / Similarity / Sensitive / Metric / ContextCompress / Export / RedisLock / RateLimit / Diff / ReportParse
├── constant        提示词、任务状态、Redis Key、模型、错误码
├── rag             EmbeddingService / ChromaVectorService / RagRetrieveService
├── agent           dto.AgentContext + 4 个 Stage service
└── event           TaskSuccessEvent / TaskFailEvent / TaskEventListener
```

## 快速开始

### 1. 依赖服务

1. MySQL 8，执行 [schema.sql](src/main/resources/sql/schema.sql)。老库升级时除 `audit_log` 建表外，还需补以下唯一索引（用于版本号与报告缓存的并发一致性）：

```sql
ALTER TABLE prd_task ADD UNIQUE KEY uk_tenant_file_version (tenant_id, file_meta_id, version_no);
ALTER TABLE prd_task_cache ADD UNIQUE KEY uk_cache_key (tenant_id, file_md5, model_code, chunk_strategy);
```

2. Redis 6.2+
3. Chroma 服务：`docker run -p 8000:8000 chromadb/chroma`（不可用时自动降级本地召回）
4. OpenAI 兼容的 LLM / Embedding 接口

登录与 VIP 相关的 `sys_user`、`vip_plan`、`user_vip`、`redeem_code`、`user_daily_usage` 均由脚本中 `CREATE TABLE IF NOT EXISTS` 与 `INSERT IGNORE` 幂等创建并写入 FREE/BASIC/PRO 三档种子套餐，重复执行安全。

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
# 生产环境强制携带身份 Header（缺失返回 401）
REQUIRE_IDENTITY=false
# 生产环境强制只认登录令牌（开启后 Header 身份透传失效）
REQUIRE_LOGIN=false
# 登录令牌有效期（秒），默认 12 小时
AUTH_TOKEN_TTL=43200
# 允许自助注册的租户白名单，逗号分隔；其他租户账号只能由管理员创建
SELF_REGISTER_TENANTS=default
# 内置管理员初始化（首次启动创建账号 + 最高等级 VIP，已存在账号不覆盖密码）
ADMIN_INIT_ENABLED=true
ADMIN_INIT_USERNAME=admin
ADMIN_INIT_PASSWORD=********
ADMIN_INIT_VIP_DAYS=3650
```

密钥一律通过环境变量注入，禁止提交到仓库。

### 3. 启动

```bash
mvn clean package -DskipTests
java -jar target/prd2api.jar --spring.profiles.active=dev
```

- 单元测试：`mvn test`（离线环境可用 `mvn -o test`）
- Swagger：http://127.0.0.1:8080/swagger-ui/index.html
- 健康探针：`/actuator/health`（含 liveness/readiness）
- Prometheus 指标：`/actuator/prometheus`

### 4. 前端控制台

[frontend/index.html](frontend/index.html) 是零依赖单页控制台，直接用浏览器打开或由任意静态服务器托管：

- 右上「接入设置」配置服务地址与未登录时的 Header 透传身份
- 右上「登录 / 注册」跳转独立登录页 [frontend/login.html](frontend/login.html)，登录后回到控制台，令牌保存在 localStorage，刷新后自动恢复；已登录时该按钮变为「登出」
- 左侧「Account」卡片用「额度 / 优惠券」两个 Tab 展示当前额度来源、上传/token/并发用量、兑换码入口与优惠券领取/我的券

## 鉴权约定

优先使用登录令牌（推荐），未携带令牌时回退网关 Header（便于内部脚本与运维调用）：

| 方式 | 说明 |
| --- | --- |
| `Authorization: Bearer <token>` | 登录接口签发，Redis 存储不透明令牌并滑动续期 12 小时；登出、改密、封号立即失效 |
| `X-Tenant-Id` | 租户 ID，缺省 `default`；仅允许字母、数字、`_`、`-`，长度 1~64（参与存储路径拼接，非法值直接 400） |
| `X-User-Key` | 用户标识，缺省 `anonymous`；仅允许字母、数字及 `_ . @ : -` |
| `X-User-Role` | `USER` / `ADMIN`，仅 `ADMIN` 生效，其他值自动降级为 `USER`；`/api/admin/**` 强制 `ADMIN` |
| `X-Trace-Id` | 可选，透传链路 ID，缺省自动生成 |

- `prd2api.security.require-login=true` 时只接受登录令牌，Header 透传返回 401（生产建议开启）
- `prd2api.security.require-identity=true` 时缺失 `X-Tenant-Id` / `X-User-Key` 直接 401
- 角色仅 ADMIN / USER 两级；管理员账号可通过 `POST /api/admin/vip/user` 创建
- 跨域来源由 `prd2api.security.allowed-origins` 控制，默认 `*` 仅供开发，生产务必配置具体域名
- 下载导出文件使用一次性 `exportToken`，有效期默认 300 秒，不依赖上述身份

首个管理员：启动时由 [AdminAccountInitializer.java](src/main/java/com/dbappsecurity/prd2api/config/AdminAccountInitializer.java) 自动创建，默认账号 `admin`（租户 `default`），初始密码取 `ADMIN_INIT_PASSWORD` 配置，并自动开通等级最高的 `PRO` 套餐；账号已存在时只补齐 ADMIN 角色与最高套餐，不覆盖已修改的密码。也可用 Header 模式（`X-User-Role: ADMIN`）调用 `POST /api/admin/vip/user` 创建其他管理员。

## VIP 体系

| 表 | 作用 |
| --- | --- |
| `sys_user` | 账号、BCrypt 密码、角色、账号状态、当前套餐与到期时间、失败次数与锁定时间 |
| `vip_plan` | 套餐配置：额度、并发、单文件上限、模型档位、参考价格、上下架 |
| `user_vip` | 开通流水：来源（ADMIN / REDEEM / COUPON）、生效与到期时间、操作人 |
| `redeem_code` | 兑换码：套餐、状态（UNUSED/USED/EXPIRED/VOID）、批次、使用人 |
| `coupon` | 优惠券发放批次：总量、每人限领、已领数量、领取起止时间、领取后有效天数 |
| `user_coupon` | 用户领取的券：唯一键 `(coupon_id, user_id, claim_no)`，状态 UNUSED/USED/EXPIRED |
| `user_daily_usage` | VIP 用户按天的上传次数与 token 消耗，唯一键 `(user_id, stat_date)` |

额度解析统一走 `QuotaResolver`：

1. 登录用户 VIP 生效（未过期、账号正常、套餐上架）→ 使用套餐额度，计数落 `user_daily_usage`（SQL 条件更新，防并发超卖）
2. 未登录、未开通 VIP、已过期 → 回退所属租户 `tenant_quota`
3. 单文件上限同样由额度决定：FREE 20MB，套餐按 `max_file_size_mb` 校验（Servlet 层放宽到 60MB，实际由业务层拦截）
4. 兑换码使用 `UPDATE ... WHERE status='UNUSED'` 原子抢占，并发下不会重复兑换
5. 同套餐再次开通/兑换按原到期时间累加，切换套餐从当前时间重新计算
6. 优惠券领取走 Redis Lua 原子脚本（库存扣减 + 每人计数同一脚本内完成），数据库 `UPDATE ... WHERE claimed_count < total_stock` 与唯一键二次兜底，落库失败自动回滚 Redis 计数，任何情况下都不会超领
7. 优惠券核销使用 `UPDATE ... WHERE status='UNUSED'` 原子抢占，成功后立即开通（或续期）对应套餐，并发重复使用只会有一次生效

## 接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 自助注册（固定 USER 角色，仅白名单租户），成功直接返回登录令牌 |
| POST | `/api/auth/login` | 账号密码登录，返回 `token` 与用户信息 |
| POST | `/api/auth/logout` | 登出当前令牌 |
| GET | `/api/auth/me` | 当前登录用户信息与 VIP 状态 |
| POST | `/api/auth/password` | 修改密码，成功后该用户全部令牌失效 |
| GET | `/api/vip/plans` | 套餐列表（用户端，仅上架） |
| GET | `/api/vip/my` | 我的 VIP：套餐、到期时间、剩余天数、今日用量 |
| POST | `/api/vip/redeem` | 兑换码兑换（原子抢占，同套餐自动续期） |
| GET | `/api/vip/quota` | 当前生效额度（VIP 套餐或租户配额） |
| GET | `/api/coupon/available` | 可领取优惠券（含当前用户已领数量） |
| POST | `/api/coupon/{couponId}/claim` | 领取优惠券（Lua 原子扣库存，防超领） |
| GET | `/api/coupon/my` | 我的优惠券 |
| POST | `/api/coupon/{userCouponId}/use` | 核销优惠券并开通 / 续期 VIP |
| POST | `/api/file/upload` | 上传文件（multipart，字段 `file`） |
| GET | `/api/file/page` | 文件分页（按租户 / 文件名） |
| DELETE | `/api/file/{fileMetaId}` | 删除文件 |
| POST | `/api/task/create` | 创建解析任务（异步） |
| GET | `/api/task/{taskId}` | 任务详情 + 报告 |
| GET | `/api/task/page` | 任务分页（按状态 / fileMetaId 回溯多版本） |
| POST | `/api/task/retry` | 重试失败任务（可换模型） |
| POST | `/api/task/{taskId}/cancel` | 取消任务（阶段边界中断，状态置 CANCELED，可重试） |
| GET | `/api/task/{taskId}/stream` | SSE 实时进度（每 1s 推送，终态自动关闭） |
| GET | `/api/task/{taskId}/diff?otherTaskId=` | 同一文档两版本报告 diff |
| GET | `/api/task/{taskId}/issues` | 结构化待确认清单（✅ / ❓ / ⚠️） |
| POST | `/api/task/chat` | 追加追问并回写报告 |
| GET | `/api/task/{taskId}/export?format=md\|html\|docx` | 生成导出文件，返回临时凭证 |
| GET | `/api/task/export/download?token=xxx` | 下载导出文件 |
| DELETE | `/api/task/{taskId}` | 删除任务并清理向量 |
| GET | `/api/rag/search?taskId=&query=&topK=` | 召回调试 |
| GET | `/api/rag/status` | Chroma 连通性与配置 |
| GET | `/api/admin/task/page` | 全租户任务分页（ADMIN） |
| GET | `/api/admin/stats?tenantId=&days=7` | 统计大盘：总览 + 日趋势 + token Top10 租户（ADMIN） |
| GET | `/api/admin/audit/page` | 操作审计分页（ADMIN） |
| GET / POST | `/api/admin/quota/{tenantId}` / `/api/admin/quota` | 查询 / 配置租户配额（ADMIN） |
| DELETE | `/api/admin/tenant/{tenantId}` | 清理租户任务、向量、文件（ADMIN） |
| POST | `/api/admin/clean` | 手工触发过期数据清理（ADMIN） |
| POST | `/api/admin/vip/user` | 创建用户（可指定 ADMIN，ADMIN） |
| GET | `/api/admin/vip/user/page` | 用户分页，按租户/账号/套餐/VIP 状态过滤（ADMIN） |
| POST | `/api/admin/vip/grant` | 手动开通 VIP，同套餐自动续期（ADMIN） |
| GET | `/api/admin/vip/plan/all` | 全部套餐含下架（ADMIN） |
| POST | `/api/admin/vip/plan` | 新增或更新套餐（ADMIN） |
| DELETE | `/api/admin/vip/plan/{planCode}` | 套餐下架（ADMIN，FREE 不可下架） |
| POST | `/api/admin/vip/redeem-code/batch` | 批量生成兑换码，单次最多 500 个（ADMIN） |
| GET | `/api/admin/vip/redeem-code/page` | 兑换码分页（ADMIN） |
| POST | `/api/admin/coupon` | 发放限量优惠券（ADMIN） |
| GET | `/api/admin/coupon/page` | 优惠券分页，按状态 / 套餐 / 名称过滤（ADMIN） |
| DELETE | `/api/admin/coupon/{couponId}` | 优惠券下架，仅停止新领取（ADMIN） |
| GET | `/api/admin/coupon/{couponId}/claims` | 某批优惠券的领取记录（ADMIN） |

## Redis Key 规范

```
prd2api:task:lock:{taskId}
prd2api:task:context:{taskId}
prd2api:task:cancel:{taskId}
prd2api:rag:embedding:{chunkMd5}
prd2api:rag:collection:{taskId}
prd2api:rag:chunks:{taskId}
prd2api:doc:text:{fileMd5}
prd2api:limit:upload:{tenant}:{user}
prd2api:limit:llm:{tenant}:{user}
prd2api:quota:daily:{tenantId}
prd2api:export:token:{exportToken}
prd2api:auth:token:{token}
prd2api:auth:user:tokens:{userId}
prd2api:coupon:{couponId}:stock
prd2api:coupon:{couponId}:user:{userId}
```

> 优惠券的两个 Key 用 `{couponId}` 作 hash tag，保证 Lua 脚本内多个 Key 落在同一 Redis Cluster 槽位；TTL 跟随领取截止时间，长期券不过期。

## 运维说明

- 定时清理：每日 03:30 清理过期文件、过期报告缓存、超过 24 小时的导出临时文件（`prd2api.clean.cron` 可改）
- VIP 降级：每日 04:00 清理已过期 VIP 标记、过期兑换码与过期优惠券并写审计（`prd2api.vip.expire-cron` 可改）；请求时也会按到期时间惰性判定，不依赖定时任务
- 配额优先级：VIP 生效（未过期且账号正常）用套餐额度与 `user_daily_usage` 计数，否则回退 `tenant_quota`
- 配置热加载：`Prd2ApiProperties` 标注 `@RefreshScope`，接入 Nacos/Apollo 后无需改代码
- 优雅停机：`server.shutdown=graceful`，线程池等待 30 秒
- 指标：任务总数/成功/失败、LLM 调用耗时、token 消耗、RAG 召回耗时
- 告警钩子：`TaskEventListener` 预留消息通知与告警平台对接点
- 大文本保护：解析文本与分块缓存的 Redis 单值上限为 `prd2api.chunk.max-cache-chars`（默认 20 万字符），超限跳过缓存不影响解析
- SSE：推送线程数与单实例最大连接数分别由 `prd2api.async.stream-threads`、`stream-max-connections` 控制
- 生产 profile 仅暴露 `health` 与 `prometheus` 两个 actuator 端点

## 已知边界

1. 对象存储模式当前仅预留适配点，需接入 OSS/S3 SDK 后启用（默认 `local`）
2. Chroma 通过 HTTP API 调用（未引入第三方 jar），接口版本差异请按实际服务调整 `/api/v1`
3. 未配置 `LLM_API_KEY` 时任务会明确失败并提示，不影响上传、配额等其它能力
