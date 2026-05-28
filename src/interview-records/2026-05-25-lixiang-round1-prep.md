# 理想汽车 Java 高级开发工程师 — 面试准备

> 基于 JD + 简历（杨卫）的个性化面试题库，共 33 题，按模块分类。
> 每题标注了对应知识文档，可跳转复习。

---

## 一、项目深挖（必问，占 40-50%）

### 1.1 开放平台 API 网关

#### Q1：AK/SK 签名认证的具体流程？每一步为什么这么设计？（重点深挖）

> 详细学习文档 → [aksk-signature.md](aksk-signature.md)

**完整流程（4 步，在脑子里有这张图）：**

```
客户端                                    服务端（网关）
  │                                          │
  ├─ 1. 构造规范请求串（Canonical Request）    │
  │   Method + URI + QueryString             │
  │   + 关键Headers + Body摘要                │
  │                                          │
  ├─ 2. SHA256(规范请求串) → 摘要             │
  │   再 HMAC-SHA1(SK, 摘要) → 签名           │
  │                                          │
  ├─ 3. 发送请求（Header 带 AK + Signature）   │
  │ ─────────────────────────────────────→   │
  │                                          ├─ 4. 服务端重算签名并比对
  │                                          │   ① 检查 Timestamp（±5分钟窗口）
  │                                          │   ② 检查 Nonce（Guava Cache 去重）
  │                                          │   ③ 用相同算法重算签名 → 比对
  │                                          │   ④ 一致则放行，不一致返回 401
```

**两步哈希的设计动机（面试核心考点）：**

很多人以为签名就是"对请求内容做个 HMAC"，但实际上我们拆成了两步，两个哈希职责不同：

| 步骤 | 算法 | 输入 | 输出 | 职责 |
|------|------|------|------|------|
| 第一步 | SHA256 | 规范请求串（可能很长） | 32字节定长摘要 | **压缩** |
| 第二步 | HMAC-SHA1 | SK + 摘要 | Base64 签名 | **认证** |

**为什么需要第一步 SHA256 压缩？** 规范请求串可能很长（query 参数多的接口几百字节），直接对原文做 HMAC 性能差。先 SHA256 压缩成固定 32 字节，后续 HMAC 的输入永远是定长的，签名计算 O(1)。

**为什么第二步用 HMAC 而不是直接用 SHA256 当签名？** SHA256 是无密钥哈希，任何人都能算。攻击者篡改请求后重新算 SHA256 就能伪造签名。HMAC 把 SK 作为密钥参与计算，攻击者没有 SK 就算不出合法签名。本质就是"SK 不出门"——SK 只在 HMAC 密钥层参与运算，本身不出现请求中。

**防重放攻击 — 双因子校验：**

```
Timestamp（时间窗口）  +  Nonce（一次性随机数）
      │                        │
  防"过期重放"             防"窗口内重放"
  │t_client - t_server│     Guava Cache 本地缓存
  > 5 分钟 → 拒绝        已存在的 Nonce → 拒绝
                        缓存 5 分钟自动过期
```

**关键设计决策：Nonce 为什么用本地 Guava Cache 而不是 Redis？**

100% 防重放需要集中式缓存（Redis），但代价是每次请求多一次 Redis 调用。百万级 QPS 下这个开销不可忽略。我们的取舍是：Guava Cache 本地去重，只能防止打到同一网关节点的重放。攻击者重复发请求大概率打到同一个节点（负载均衡的会话保持），如果分散到不同节点，Timeststamp 窗口校验也会让重放窗口只有 5 分钟。这是一个**有意识的 tradeoff**——用微小的安全妥协换取了零额外延迟。

**其他核心设计点：**

- **QueryString 按 key 升序排序**：客户端和网关的参数顺序可能不同（`?a=1&b=2` vs `?b=2&a=1`），不排序签名就不一致
- **只签关键 Header（x-dip-* + Content-Type）**：通用 Header（User-Agent、Accept-Encoding）经过代理时会被改写，签了反而验不过
- **Body 签 SHA256 摘要而非原文**：大 Body 直接拼进规范请求串性能差，SHA256 后定长 32 字节。服务端也只需要比对 Body 的 SHA256，不需要存原文
- **SK 不进签名字符串**：SK 作为 HMAC 密钥层的输入，不出现在签名数据中，即使签名字符串泄露也不会暴露 SK

#### Q2：Token 和 AK/SK 两种鉴权模式分别在什么场景？

| | AK/SK | Token |
|------|------|------|
| 适用方 | 服务端程序（后端调后端） | 浏览器 / 移动端 |
| 签名计算 | 离线计算，无需网络 | 需要先请求 Token |
| 过期控制 | SK 长期有效，通过 AK 管理 | Token 可设短期过期 + Refresh |
| 权限粒度 | 应用级别 | 可做到用户级别 |
| 延迟 | 零额外网络请求 | 需验 Token 有效性 |

**为什么网关支持双模式？** 不同接入方场景不同：内部微服务用 AK/SK 最轻量，外部浏览器应用用 Token 更灵活。双模式给了接入方最大选择权，这是 API 网关作为平台的定位。

#### Q3：令牌桶 vs 漏桶？为什么选令牌桶？Redis + Lua 怎么保证原子性？

**令牌桶 vs 漏桶 — 不只是背定义，要讲出选型逻辑：**

| | 令牌桶 | 漏桶 |
|------|------|------|
| 原理 | 固定速率放令牌，有令牌才能通过 | 请求进桶，固定速率漏出 |
| 突发流量 | **允许**（桶里积攒的令牌可一次性消费） | 不允许（严格匀速排队） |
| 适用场景 | API 限流（允许合理突发） | 流量整形（严格平滑） |

**为什么 API 网关选令牌桶？** 一个租户平时 QPS 10，偶尔瞬时到 50 也是合理的（用户在后台刷新页面、批量导出等）。漏桶会把这些请求全部排队延迟，用户感知为"系统卡"。令牌桶允许消耗积攒的令牌应对瞬时突发，但桶容量有上限，不会无限放大流量。**允许突发但限制总量**——这恰好匹配 API 调用的实际模式。

**Redis + Lua 实现分布式令牌桶：**

限流的"检查 + 扣减"是两步操作，分开执行有竞态条件。Lua 脚本在 Redis 单线程模型中原子执行，天然解决并发问题。

```
-- 实际的核心逻辑（基于 Spring Cloud Gateway RedisRateLimiter）
-- KEYS[1]=tokens key, KEYS[2]=timestamp key
-- ARGV[1]=replenishRate, ARGV[2]=burstCapacity, ARGV[4]=requestedTokens

local tokens_key = KEYS[1]
local timestamp_key = KEYS[2]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local requested = tonumber(ARGV[4])

-- 1. 计算时间差，补充令牌
local last_refresh = tonumber(redis.call('GET', timestamp_key) or "0")
local now = redis.call('TIME')[1]
local delta = math.max(0, now - last_refresh)
local filled_tokens = math.min(capacity, (redis.call('GET', tokens_key) or capacity) + delta * rate)

-- 2. 判断令牌是否足够
local allowed = filled_tokens >= requested
if allowed then
    redis.call('SET', tokens_key, filled_tokens - requested)
end
redis.call('SET', timestamp_key, now)

return { allowed and 1 or 0, math.floor(filled_tokens) }
```

**三个面试亮点（体现框架源码阅读深度）：**

**亮点1 — Redis Cluster 的 hash tag：**

令牌桶需要两个 key（tokens + timestamp），Lua 脚本要求操作的 key 在同一个 Redis slot，否则报 `CROSSSLOT` 错误。

Redis Cluster 将 key 通过 CRC16 哈希分配到 16384 个 slot，不同的 key 会落到不同节点。但 Redis 有个规则：**如果 key 中包含 `{}`，只对花括号内的内容做哈希**，这就是 hash tag。

我们的 key 设计利用了这一点：
```
request_rate_limiter.{routeId.id}.tokens
request_rate_limiter.{routeId.id}.timestamp
                            ↑
              CRC16 只算花括号里的内容
              → 两个 key 必然落到同一个 slot
              → Lua 脚本可以原子操作 ✅
```

**亮点2 — Lua 脚本为什么用 `redis.call('TIME')` 而不是 `os.time()`：**

限流的 Lua 脚本是 Spring Cloud Gateway `RedisRateLimiter` 内置的，不需要自己写。但我读它的脚本源码时注意到：取时间用的是 `redis.call('TIME')` 而不是 `os.time()`。查了才知道，这和 Redis 主从复制的确定性要求有关。

Redis 主节点执行 Lua 脚本后，把脚本重放到从节点。`os.time()` 读的是操作系统时钟，主从机器的系统时间可能有偏差，脚本重放时算出的结果不一致 → 主从数据不一致。`redis.call('TIME')` 产生的写命令里已经包含了确定的时间值（如 `SET timestamp "1716912000"`），从节点直接拿最终结果，不依赖重新执行时的环境。

这个细节让我意识到：用了框架不代表可以不懂原理，如果将来需要自定义限流脚本，这个知识点就用上了。

**坑3 — Redis 故障降级：** Lua 脚本执行异常时（Redis 不可达），我们的策略是**直接放行**而不是拒绝。原因：网关可用性优先于限流准确性；限流是保护下游的手段，但不能因为限流系统自身故障而阻断所有流量。代码里用 `onErrorResume` 兜底返回 `allowed=true`。

#### Q4：路由配置热加载怎么实现？为什么不用锁？

**完整链路：**

```
管理后台 ─→ 修改路由 → 写入 MySQL
                            │
                    发布时触发 reload()
                            │
                            ▼
                    RouteConfigLoader.reload()
                    从 MySQL 拉取全量配置
                    构建新的 Map<路径, RouteDefinition>
                            │
                            ▼
                    CustomRouteDefinitionRepository
                    routeHub.set(newMap)  ← AtomicReference 原子替换
                            │
                            ▼
                    RefreshRoutesResultEvent 事件
                            │
                            ▼
                    CachingRoutePredicateHandlerMapping
                    重建 PathTrie（前缀树）
                    路由匹配 O(n) → O(路径深度)
```

**核心难点不是"读到新配置"，而是"更新时不阻塞正在处理的请求"。**

**方案对比 — 为什么用 AtomicReference 而不是 ReadWriteLock？**

| | ReadWriteLock | AtomicReference + Copy-on-Write |
|------|------|------|
| 读操作 | 需获取读锁（有开销） | 无锁，直接读引用 |
| 写操作 | 需获取写锁（阻塞所有读） | 构建新对象 → 一次原子替换 |
| WebFlux 兼容 | 锁会阻塞事件循环线程 | 天然非阻塞 |
| 写时复制开销 | 无 | 需要构建全新 Map（内存开销） |
| 适用场景 | 读写频繁，数据量大 | 读多写少，数据量适中 |

**为什么选后者？** 网关路由配置变更频率极低（一天几次），但读取频率极高（每个请求都要匹配路由）。读多写少的场景，Copy-on-Write 是最优解。用 `AtomicReference` 保证：① 写操作不阻塞读（构建新 Map 期间旧 Map 正常服务）；② 读操作零加锁（WebFlux 事件循环不受影响）。

**路由匹配优化 — 自定义 PathTrie：**

Spring Cloud Gateway 默认遍历所有 RouteDefinition 逐个 Predicate 匹配，O(n)。200+ 路由时性能退化。我实现了前缀树路由匹配：

```
路径匹配优先级（PathTrie 实现）：
  /api/v1/users → 精确匹配 /api/v1/users
                  ↓ 未命中
                → 单段通配 /api/v1/*
                  ↓ 未命中
                → 多段通配 /api/**
```

**追问预案："前缀树在路由发布时怎么更新？"** `RefreshRoutesResultEvent` 事件触发时，遍历所有 Route，逐个插入新的 PathTrie 实例，构建完成后用 `AtomicReference.set()` 原子替换。旧 Tree 继续服务，新请求自动路由到新 Tree。

**动态路由（DipDynamicHost）— 比热加载更快的"秒级切流"：**

热加载解决的是"改路由不重启"，但它需要 reload 全量路由，至少几秒。还有一种更快的场景：某个 API 的后端需要**秒级切换**。

DipDynamicHost 做的事：
```
Apollo 配置 DYNAMIC_ROUTE_MAPPING：
  {"100": "http://10.0.1.50:8080", "200": "http://10.0.1.60:9090"}
                     │
  请求 API 100 → 过滤器读到 Apollo → 替换 GATEWAY_REQUEST_URL_ATTR
              → 转发到新地址，不经过热加载流程
```

和热加载的区别：

| | 热加载（reload） | 动态路由（DipDynamicHost） |
|------|------|------|
| 生效速度 | 几秒 | 毫秒（Apollo 实时推送） |
| 影响范围 | 全量路由重建 | 单个 API |
| 适用场景 | 新增/修改路由配置 | 灰度发布、紧急摘流、服务迁移 |

实际用途：
- **灰度发布**：API 100 切到新版本机器验证，有问题立刻切回
- **紧急摘流**：后端服务挂了，Apollo 改一行配置，瞬间切到备机
- **服务迁移**：API 从老服务迁到新服务，先改 Apollo 验证，再改 MySQL 固化

#### Q5：过滤器链顺序怎么设计？短路和兜底怎么平衡？

**完整过滤器链（基于实际代码 CustomRouteDefinitionRepository.loadDynamicRouteDefinition）：**

```
请求 ─→ [CacheRequestBody] 缓存请求体（后续过滤器复用，避免重复读Body）
   ─→ [DipCheckSign]    签名校验 ── 失败 → 401
   ─→ [DipCheckAuth]    授权校验 ── 失败 → 401
   ─→ [DipCheckParam]   参数校验 ── 失败 → 400
   ─→ [DipWhiteList]    IP白名单 ── 不在白名单 → 403
   ─→ [DipApiRateLimiter]   API级限流 ── 触发 → 429
   ─→ [DipAuthedRateLimiter] 授权级限流 ── 触发 → 429
   ─→ [DipRequestTimeout] 超时控制
   ─→ [DipRewriteRequest] 请求重写
   ─→ [DipCheckRequest]   请求校验
   ─→ [DipDynamicHost]    动态路由
   ─→ [插件过滤器...]      可插拔插件（按 executeOrder 排序）
   ─→ [DipCacheResponse]  响应缓存（Redis）
   ─→ [RewritePath]       路径重写
   ─→ 转发到业务服务
```

**顺序设计的四个原则：**

1. **安全优先**：鉴权（Sign + Auth）在最前面，非法请求尽早拦截，不浪费后续资源
2. **保护下游**：限流在鉴权之后、转发之前 —— 先确认身份才能按租户限流，在到达业务服务前截流
3. **短路不丢日志**：签名失败返回 401，但日志采集通过单独的 GlobalFilter 实现（不依赖路由级过滤器链），确保所有请求（包括被拒绝的）都被记录
4. **缓存最后一道防线**：响应缓存在转发之后，缓存的是真实业务响应而非错误响应

**可插拔插件系统：** 每个 API 可绑定多个 Plugin，按 `executeOrder` 字段排序后插入过滤器链。

```
完整链路：

开发侧（你写代码）：
  实现 GatewayFilterFactory → 注册为 Spring Bean
  比如 "DipSensitiveDataMask"（数据脱敏插件）

管理后台（用户配置）：
  API 100（订单查询）：
    ├─ 开启"数据脱敏"插件
    ├─ 配置：{"fields": ["phone", "id_card"], "mode": "mask"}
    └─ 执行顺序：50

存入数据库：
  open_platform_api_plugin 表：
    api_id=100, plugin_name="DipSensitiveDataMask",
    plugin_content='{"fields":[...]}', execute_order=50

路由加载时（CustomRouteDefinitionRepository）：
  查询 apiPluginMap → 按 executeOrder 排序 → 动态插入过滤器链
```

插件和固定过滤器的关系：
```
API-1 → [固定: 鉴权→限流] → [插件: 脱敏(a)] → [固定: 转发]
API-2 → [固定: 鉴权→限流] → [插件: 埋点(b)] → [固定: 转发]
API-3 → [固定: 鉴权→限流] ──────────────→ [固定: 转发]  ← 没开插件
```

**能支持用户自己写插件吗？**

当前不支持，这是一个有意识的技术边界判断。三个硬问题：

| 问题 | 说明 |
|------|------|
| **动态加载** | Java 不支持像 Lua/Wasm 那样天然热加载代码段，需要自定义 ClassLoader |
| **安全沙箱** | 用户代码跑在网关 JVM 里，能 `System.exit(0)` 关掉网关，Java 做沙箱隔离代价极高 |
| **故障隔离** | 用户插件死循环 → 拖死 EventLoop 线程 → 整个网关超时 |

业界对比：Kong/APISIX 用 Lua 或 Wasm 天然沙箱，天然支持用户编写插件。Java 网关要做同样的事，成本指数级增长。

**面试话术：**
> 当前插件系统支持的是**可配置插件**——开发实现 GatewayFilterFactory，用户选开关 + 填配置。这是 Java 网关务实的选择。要做到 Kong/APISIX 那样用户动态编写插件，需要引入 Wasm 或脚本引擎 + 沙箱隔离，对当前 200+ API 的规模 ROI 为负。但我们对插件接入做了标准化规范，如果有新的定制需求，走正常的代码合入流程即可。
**追问预案："签名为啥在缓存 Body 之后？"** 签名校验需要读取 RequestBody 计算 SHA256 摘要，而 WebFlux 的 Body 只能读一次。先放 CacheRequestBody 把 Body 缓存到 exchange attributes，后续 SignFilter 和业务转发都能从缓存中读取，避免 Body 被消费后无法重读。

#### Q6：Kafka 日志采集 — 百万级调用下的设计考量？

**QPS 估算：**
```
日均百万级 ÷ 86400秒 ≈ 12 QPS 平均，峰值 100-200 QPS
Kafka 单 partition 吞吐 = 几万 TPS → 远未到瓶颈
```

**关键设计不是"Kafka 扛不扛得住"，而是"日志采集会不会影响网关主链路"：**

```
网关处理请求（主链路，必须快）
    │
    ├─ 同步：鉴权、限流、转发 → 必须毫秒级返回
    │
    └─ 异步：日志写入 Kafka → 不阻塞主链路
         │
         └─ Kafka Producer 配置：
              - acks=1（Leader 确认即可，不等待所有副本）
              - linger.ms=5（攒 5ms 批量发送，减少网络往返）
              - 异步发送 + 回调处理失败
```

**为什么 acks=1 而不是 acks=all？** 日志场景对丢一条日志的容忍度远高于对延迟的容忍度。acks=all 需要等所有 ISR 副本确认，增加数倍延迟，ROI 不划算。如果日志绝对不可丢（如计费日志），单独建一个 Kafka topic 配置 acks=all。

**追问预案："如果 Kafka 完全挂了怎么办？"** Producer 配置了本地缓冲（buffer.memory），短时间故障消息缓存在进程内存中。Kafka 长时间不可用 → 日志写入本地文件兜底 → 恢复后批量回放。同时监控 Kafka 写入失败率告警。

#### Q7：Redis 限流在百万级下有没有性能瓶颈？

**性能估算：**
```
Redis 单机：10万+ QPS（简单 GET/SET）
每次限流：1次 Lua 脚本调用（含 TIME + GET + SET 等若干命令）
200 QPS 峰值 × 2（API级 + 授权级两级限流）= 400 次 Redis 调用/秒
Redis 负载 < 1%（400 / 100000）
```

**真正的瓶颈不是 Redis QPS，而是网络延迟。** 每个请求查两次 Redis（API级 + 授权级限流），如果跨机房 Redis RTT = 1ms，仅限流就增加 2ms 延迟。

**优化手段（按实际实施顺序）：**

1. **Pipeline 合并**：同一请求的两级限流 key 已知，可以一次 Lua 脚本处理两个维度的令牌桶，减少网络往返
2. **本地预检**：Caffeine 本地缓存存 API 的 "是否被完全限流"标记，本地判断拒绝后不再调 Redis
3. **Redis Cluster 分片**：按 API ID hash 分片，每个节点独立处理部分流量

**Key 设计考虑：**
```
API 级限流：ratelimit:{api_code}
授权级限流：ratelimit:{api_code}:{app_id}
```
两级限流的 key 有包含关系，用 hash tag `{api_code}` 保证两级 key 落在同一 Redis 节点，Lua 脚本可以合并查询。

**降级策略（Redis 不可用）：**
Redis 异常时，Lua 脚本 `onErrorResume` 直接返回放行。同时有本地信号量（Semaphore）做全局限流兜底，防止 Redis 挂掉后流量无限制打到下游：
```
if (semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
    // 放行
} else {
    return 503; // 全局限流兜底
}

---

### 1.2 NL2SQL + Agentic RAG + MCP

#### Q8：Agentic RAG 的具体架构？和普通 RAG 的区别是什么？

**完整架构（面试时一边讲一边画）：**

```
┌─────────────────────────────────────────────────────────┐
│                    用户自然语言                           │
│              "2026年理想L6的销量是多少？"                     │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
            ┌──────────────────┐
            │   Agent 规划      │  ← Function Call 模式
            │  · 意图识别       │    判断 SINGLE / MULTI
            │  · 子问题拆解     │    拆成子问题 + dependsOn
            │  · DAG 调度       │    拓扑排序 → 分层执行计划
            └────────┬─────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   子问题1       子问题2       子问题3        ← DAG 分层，同层并行
        │            │            │
        └────────────┼────────────┘
                     │
              每个子问题按类型分支：
                     │
        ┌────────────┴────────────┐
        ▼                         ▼
   ┌──────────┐             ┌──────────┐
   │ isQuery  │             │ isQuery  │
   │ = true   │             │ = false  │
   │ QUERY路径 │             │ COMPUTE路径│
   └────┬─────┘             └────┬─────┘
        │                        │
        ▼                        ▼
   ┌──────────────┐      ┌──────────────┐
   │ 双层检索      │      │ 等待依赖结果   │
   │ ①指标知识库RAG│      │ ↓            │
   │ ②MCP Schema  │      │ LLM计算/推理  │
   └──────┬───────┘      └──────┬───────┘
          │                     │
          ▼                     │
   ┌──────────────┐             │
   │  LLM 生成SQL  │             │
   └──────┬───────┘             │
          │                     │
          ▼                     │
   ┌──────────────┐             │
   │  SQL 安全校验 │             │
   └──────┬───────┘             │
          │                     │
          ▼                     │
   ┌──────────────┐             │
   │  执行SQL      │             │
   └──────┬───────┘             │
          │                     │
          └──────────┬──────────┘
                     │ 合并结果
                     ▼
            ┌──────────────┐
            │   结果返回     │  ← 错误时回传 LLM 自动修正
            └──────────────┘
```

**关键设计：QUERY 节点 vs COMPUTE 节点**

| | QUERY 节点 | COMPUTE 节点 |
|------|------|------|
| 是什么 | 需要查数据库取数据 | 纯计算/推理 |
| 执行链 | 检索 → Schema → 生成SQL → 执行 | 等待依赖 → LLM计算 |
| 示例 | "查L6 2026年销量" | "计算L6同比增长率"、"比较排名" |
| 依赖 | 无（独立查询） | 有（依赖 QUERY 节点的结果） |

以"L6和L7哪个增长更快"为例，7 个子问题中：
- 4 个 QUERY 节点（并行查各车型各年份销量）
- 3 个 COMPUTE 节点（算增长率 → 比较排名）

**为什么这样设计？** COMPUTE 节点不需要查数据库，拿到依赖结果后直接用 LLM 算就行。如果不做这个区分，所有节点走一套流程，"计算增长率"也会去调 Schema 检索和 SQL 生成，白白浪费 LLM 调用。

**和普通 RAG 的核心区别：**

| | 普通 RAG | Agentic RAG |
|---|---|---|
| 检索次数 | 1次（检索→生成） | 多次（每个子问题独立检索） |
| 规划能力 | 无（直接检索） | 有（意图识别→拆解→调度） |
| 工具使用 | 只有检索 | 检索 + MCP查Schema + 执行SQL + 计算器 |
| 错误恢复 | 无（生成错就错了） | 有（SQL报错→回传LLM→修正） |
| 适用场景 | 文档问答 | 复杂数据查询 |

**Agent 规划的具体实现：**

用了 Function Call 模式——定义一组工具（search_indicator、get_table_schema、execute_sql），LLM 根据用户问题自主决定调用哪些工具、什么顺序。

**意图识别怎么做的？**

"意图识别"不是一段单独的代码，而是 LLM 在 System Prompt 约束下自己做的分类+路由。以两个问题对比：

```
问题1："2026年理想L6的销量是多少？"
  → LLM 判断：单指标查询，不需要对比/增长率 → 简单问题
  → 路由：search_indicator → get_table_schema → execute_sql → 返回

问题2："2026年L6和L7的销量对比，哪个增长更快？"
  → LLM 判断：涉及两个车型+增长率计算 → 复杂问题
  → 路由：拆成子问题
      ├─ 查L6销量（可并行）
      ├─ 查L7销量（可并行）
      ├─ 查去年同期数据
      └─ 计算增长率 + 排序 → 合并结果
```

**System Prompt 里怎么定义这个分类逻辑：**

```markdown
你是一个数据分析 Agent。根据用户问题判断复杂度：

简单问题（一条 SQL 搞定）：
- 问单个指标数值："XX销量是多少？"
- 问某时间段的统计："上个月XX卖了多少？"
→ 直接 search_indicator → get_table_schema → execute_sql

复杂问题（需要多步推理）：
- 涉及对比："A和B哪个多？"
- 涉及二次计算："增长率、占比、排名"
- 涉及多表关联
→ 先拆解子问题 → 每个子问题走简单流程 → 合并结果
```

Agent 拿到问题后，在 System Prompt 的约束下自主判断走哪条路。**本质上是 Prompt Engineering + Function Call 的组合**——Prompt 定义了分类规则，Function Call 让 LLM 能真正调用工具来执行每步操作。

**为什么不用单次 LLM 调用？** 简单查询没问题，但"对比本月和上月的 GMV 增长率"这种需要子查询拆解的问题，一条 Prompt 塞太多上下文容易混乱。Agentic 模式让每步只关注一个子问题，上下文干净，准确率更高。

#### Q9：NL2SQL 准确率怎么评估？评测体系怎么搭？（重点深挖）

**一、评测数据集怎么构造？**

核心思路：标注 (自然语言问题, 标准SQL, 标准结果) 三元组。

数据来源分三类：
1. **线上真实用户问题**（最有价值）：从 Query 日志采样，人工标注对应标准 SQL
2. **产品/运营提供的典型查询**：按业务场景覆盖（电商/金融/物流各 30 条）
3. **边界 Case 手工构造**：空结果场景、超复杂聚合、多表 JOIN、跨时间段查询

数据集按难度分级：

| 难度 | 定义 | 示例 | 建议占比 |
|------|------|------|---------|
| L1 简单 | 单表单指标，无过滤 | "昨天GMV是多少" | 40% |
| L2 中等 | 单表多指标 + 过滤 + 分组 | "各品类上个月销售额" | 35% |
| L3 困难 | 多表JOIN + 子查询 + 窗口函数 | "环比增长率最高的品类Top3" | 25% |

标注量：至少 200-300 条才有统计意义，我们目前积累 500+ 条标注数据。

**二、评估指标体系（四维评估）**

不用单一指标，容易误判：

| 指标 | 定义 | 意义 |
|------|------|------|
| **语法正确率** | SQL 能被数据库解析执行不报错 | 最低门槛，不过就没意义 |
| **执行准确率（EA）** | 生成的 SQL 执行结果 == 标准答案执行结果 | **最关键的指标** |
| **精确匹配率（EM）** | 生成的 SQL 和标准 SQL 字符级完全一致 | 太严格，仅参考 |
| **有效结果率（VES）** | SQL 能执行 + 结果不为空 + 结果行数合理 | 补充评估 |

**为什么 EA 比 EM 更合理？**

一条查询可以有多种等价 SQL 写法：
```sql
-- 写法院1：JOIN
SELECT c.name, SUM(o.amount) FROM orders o JOIN category c ON ...
-- 写法2：子查询
SELECT c.name, (SELECT SUM(amount) FROM orders WHERE ...) FROM category c
```
EM 要求完全一致太严格，实际工作中这两种都算对。EA 比较执行结果，容忍不同写法，更贴近真实使用场景。

**三、知识库的 Ablation Study（消融实验）**

面试时主动讲这个，证明你不是"拍脑袋加了知识库"，而是**用实验数据驱动的决策**。

```
实验设计（500 条测试集）：
  实验组A：无知识库（仅 System Prompt + Schema）
  实验组B：仅 RAG 检索指标定义
  实验组C：RAG 检索 + Few-shot 示例（最终方案）

结果示例：
┌──────────────┬──────────┬──────────┐
│              │ L1 简单  │ L3 困难  │
├──────────────┼──────────┼──────────┤
│ 无知识库      │ 85% EA   │ 45% EA   │
│ 仅 RAG        │ 92% EA   │ 68% EA   │
│ RAG + Few-shot│ 94% EA   │ 72% EA   │
└──────────────┴──────────┴──────────┘
```

**关键发现**：知识库对简单问题提升有限（LLM 本身就会写简单 SQL），对困难问题提升显著（+27%），因为困难问题最依赖业务语义映射。这说明：**知识库的价值不在"教 LLM 写 SQL"，而在"告诉 LLM 业务上怎么算"。**

**四、Bad Case 自举闭环（持续优化机制）**

这是最能体现系统设计思维的部分：

```
上线后每一笔查询：
  用户问题 → LLM生成SQL → 自动评测（语法校验+执行+结果合理性）
                              │
                  ┌───────────┴───────────┐
                  ▼                       ▼
             通过（>80%置信度）         疑似错误
             正常返回结果        ┌───────┴───────┐
                                ▼               ▼
                           人工抽检标注      自动记录 Bad Case
                                │               │
                                └───────┬───────┘
                                        ▼
                               标注正确SQL → 加入知识库
                                        │
                               下次 RAG 召回 Few-shot 时优先命中
                                        │
                               形成"越用越准"的自优化飞轮
```

**关键设计**：Bad Case 标注后加入知识库，下次遇到类似问题 RAG 能检索到正确示例。这个**自举闭环**让系统的准确率随着使用量增长而持续提升，而不是一次性上线后就停滞。

#### Q10：指标知识库的内容结构 + RAG 召回率评估？

**知识库 Chunk 策略（和普通文档问答完全不同）：**

指标知识库的每个指标是独立的语义单元。Chunk 策略是"一个指标一个 Chunk"，不按固定 token 数切分：

```
每个 Chunk 包含：
  - 指标名称：GMV（商品交易总额）
  - 计算公式：sum(order_amount) where status in ('paid','shipped')
  - 适用维度：时间、品类、地区、渠道
  - 数据来源表：dw.dwd_order_info
  - 常见问法：GMV、销售额、交易额、成交金额、gmv
  - 计算口径说明：含税、不含退款、含运费
```

**为什么不按固定 512 token 切分？** 如果粗暴切分，一个指标的完整定义可能被切到两个 Chunk 里，检索时丢了一半信息，LLM 拿到残缺上下文生成的 SQL 肯定错。

**RAG 召回率评估（和 NL2SQL 准确率是两个独立维度）：**

| 指标 | 定义 |
|------|------|
| Recall@K | 正确答案在检索结果 Top-K 中 |
| MRR | 正确答案在检索结果中的排名倒数均值 |

我们 757 个指标的规模，Top-5 召回率 94%+，不需要 BM25 关键词检索做混合方案。等数据量增长导致召回率下降到 85% 以下时再引入 Hybrid Search（向量 + BM25），现阶段不做过度设计。


#### Q11：复杂查询的多步推理怎么做？QUERY 和 COMPUTE 怎么区分？

子问题拆出来后，不是所有节点都需要查数据库。DAG 调度时按 `isQuery` 分支：

**场景：**"2026年L6和L7哪个增长更快？"

```
Layer 0（4 个 QUERY 节点，并行）：
  step-1: "查L6 2026销量"   QUERY -> 检索指标 -> Schema -> SQL -> 执行
  step-2: "查L7 2026销量"   QUERY -> ...同上
  step-3: "查L6 2025销量"   QUERY -> ...
  step-4: "查L7 2025销量"   QUERY -> ...
         |
   等 Layer 0 全部完成
         v
Layer 1（2 个 COMPUTE 节点，并行）：
  step-5: "计算L6增长率"    COMPUTE -> 拿 step-1+3 结果 -> LLM 算
  step-6: "计算L7增长率"    COMPUTE -> 拿 step-2+4 结果 -> LLM 算
         |
         v
Layer 2（1 个 COMPUTE 节点，收尾）：
  step-7: "比较排名"        COMPUTE -> 拿 step-5+6 结果 -> LLM 比较
```

**QUERY vs COMPUTE 的区别：**

| | QUERY 节点 | COMPUTE 节点 |
|------|------|------|
| 触发条件 | 需要从数据库取数据 | 纯计算/推理 |
| 执行链 | 检索指标 -> Schema -> SQL生成 -> 执行SQL | 等待依赖 -> LLM计算/推理 |
| 示例 | "查销量"、"查订单数" | "算增长率"、"比较排名" |

**为什么必须区分？** 不区分的话"计算增长率"也会走 Schema 检索和 SQL 生成——浪费 LLM 调用，且 COMPUTE 节点根本没表可查。区分后各走各的链，规划阶段就标注好每个节点的执行路径。

**核心策略："宁可多步简单查询，不要一步复杂查询"**。窗口函数一条 SQL 也能搞定，但 LLM 写复杂窗口函数的准确率远低于分步简单查询。


#### Q12：NL2SQL 过程中，怎么防止用户注入恶意 SQL？

纵深防御三层：

**第一层 — Prompt 约束：**
System Prompt 明确定义"只能生成 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP/ALTER，禁止分号拼接多条语句"。

**第二层 — SQL 语法级校验（执行前拦截）：**
用 Druid SQL Parser 解析 SQL AST：
- 检测是否为 SELECT（非 SELECT 直接拒绝）
- 禁止关键字：DROP, DELETE, INSERT, ALTER, EXEC, INTO OUTFILE
- 禁止多条语句（分号检测）
- 禁止注释注入（`SELECT/*...*/`）

**第三层 — 数据库权限兜底：**
- 数据库连接使用只读账号（仅 SELECT 权限）
- `statement.setMaxRows(1000)` 硬限制行数
- `statement.setQueryTimeout(30)` 超时保护

**为什么不能只靠 Prompt？** Prompt 约束不防 jailbreak，语法校验不防 0-day，数据库权限是最终的安全底线。纵深防御 = 任一层失效，其他层仍然有效。

#### Q13：MCP 协议的设计动机？为什么不直接用 REST API？

**核心问题**：我们要接 6 种数据库（MySQL、OceanBase、PostgreSQL 等），每种库的 Schema 查询方式不同。如果写死 REST API，每加一种数据源就要改 Agent 的代码。

**MCP 解决方案**：每种数据源实现一个 MCP Server，暴露统一接口：

```
Agent 视角（只需要知道有这些工具）：
  ├─ get_table_info  → 不关心底层是 MySQL 还是 PG
  ├─ execute_sql
  └─ list_tables

底层（各数据源独立实现）：
  MySQL MCP Server  ─┐
  PG MCP Server     ─┼─ 都暴露相同的 tools/list 接口
  OceanBase MCP Svr ─┘
```

**MCP vs 传统 REST API 的本质区别：**

| | REST API | MCP |
|------|------|------|
| 接口发现 | 提前写死 URL，Agent 编码时就知道 | `tools/list` 动态发现，Agent 运行时才知道 |
| 新增数据源 | 改 Agent 代码 + 重新部署 | 注册 MCP Server，Agent 自动感知 |
| 协议标准化 | 各写各的，对接成本 O(n×m) | 统一协议，对接成本 O(n+m) |

**MCP 的本质**：适配器模式在 AI Agent 时代的协议化实现。

---

### 1.3 指标知识库 & 架构决策

#### Q14：数据同步怎么保证知识库和指标平台的一致性？

```
┌──────────────┐     定时拉取      ┌──────────────┐
│  指标管理平台  │ ───────────────→ │  知识库服务    │
│  (管理后台)   │                  │  (PG+pgvector) │
└──────────────┘                  └──────┬───────┘
                                        │ 指标变更 → 重新 Embedding
                                        ▼
                                  ┌──────────────┐
                                  │  Vector Store │
                                  └──────────────┘
```

**两级保障：**

1. **增量同步（主力）**：每 5 分钟拉取 `updated_at >= now() - 5min` 的变更指标，版本号对比后只更新变化的记录，只重新 Embedding 变化的指标（不全量重建索引）。

2. **全量对账（兜底）**：每天凌晨执行全量 Diff（对比指标 ID + 版本号），差异数 > 10 条触发告警。

**为什么不用 CDC（Canal/binlog）？** CDC 更实时，但指标定义不会频繁变更，5 分钟延迟完全可接受。CDC 要引入 Canal + MQ，增加链路复杂度，ROI 不划算。"够用就好"。

#### Q15：为什么向量库选 pgvector 而不是 Milvus？

**三个硬约束决定了 pgvector：**

**1. 事务一致性是硬需求。** 新增/修改指标时，元数据和向量必须在同一事务里原子生效，保证"改了就能搜到"。pgvector 天然满足（同一条 PG INSERT 写入），Milvus 需要两阶段提交或容忍不一致窗口。

**2. 数据规模没到该拆的程度。** 757 个指标 × 1536 维，pgvector HNSW 索引查询 < 1ms。端到端瓶颈是 LLM 生成 SQL（2-5 秒），优化向量检索对用户体验无感知提升。引入 Milvus 要加 etcd + MinIO + Pulsar，ROI 为负。

**3. 代码层留了扩展点。** 抽象了 VectorStore 接口（insert/search/delete），当前 pgvector 实现。未来数据量到几十万级时改一个实现类即可。这是**有意推迟决策**——不到真正需要时不引入复杂度。

面试时主动讲出这个选型逻辑，证明你不是"默认用 PG"，而是"在约束下做了 ROI 最高的选择"。

#### Q16：20+ OpenAPI 接口设计要点

- **幂等性**：写接口支持幂等键（idempotent_key），重复请求返回相同结果
- **版本管理**：URL 路径版本 `/api/v1/indicator/search` → `/api/v2/indicator/search`
- **向下兼容**：新增字段不删旧字段，废弃字段标记 @Deprecated
- **限流**：按 API Key 维度限流，防单用户打爆接口
- **统一错误码**：业务错误 / 系统错误 / 参数错误三类，方便调用方排错

---

### 1.4 项目量化数据（面试官会追问，必须脱口而出）

**网关项目：**
- 从 0 到 1，经历 6 期迭代
- 覆盖应用数 100+，角色包括研发、产品、运营
- API 日均调用量：百万级
- API 接入到发布：5 秒内完成
- 限流判断 P99 耗时：< 0.5ms
- 签名认证平均耗时：< 1ms

**Agentic RAG 项目：**
- 指标知识库：757 个指标、931 个维度
- 支持 6 种数据库：MySQL、OceanBase、PostgreSQL 等
- 20+ OpenAPI 接口
- 标注测试集：500+ 条 (自然语言, SQL, 结果) 三元组
- 知识库引入后困难问题准确率提升：+27%（45% → 72% EA）
- RAG 召回率：Top-5 Recall 94%+
- 向量检索延迟：< 1ms（pgvector HNSW）

---

## 二、Java & JVM

#### Q13：JVM 内存结构 + 对象从创建到回收的完整生命周期

> 知识点 → `src/jvm/jvm.md` 一、二、三章

#### Q14：GC 调优经验？有没有线上 Full GC 问题的排查经历？

> 知识点 → `src/jvm/jvm-tuning.md` 六章

**面试话术（STAR 框架）：**
- S：Kafka 消费者频繁 Full GC，消费延迟增大
- T：定位根因并解决
- A：jstat 观察 → jmap -histo 找大对象 → 发现 poll 了太多消息 → 调小 max.poll.records + 加大新生代
- R：Full GC 从 1次/2分钟 → 基本不触发

#### Q15：synchronized 锁升级过程

> 知识点 → `src/concurrent/locks/README.md`

```
无锁 → 偏向锁 → 轻量级锁 → 重量级锁（单向升级，不可降级）

偏向锁：同一个线程反复获取，CAS 设置线程 ID 即可
轻量级锁：不同线程竞争，CAS 自旋尝试获取
重量级锁：自旋 10 次仍未成功，升级为 Monitor，未获取到锁的线程阻塞
```

#### Q16：ThreadLocal 用过吗？内存泄漏怎么解决？

> 知识点 → `src/concurrent/threadpool/ThreadLocalLeakDemo.java`

**核心：** ThreadLocalMap 的 key 是弱引用 → key 被 GC 回收后 value 还在 → 线程池场景线程不死 → value 永远不回收
**解决：** 用完必须 `remove()`，finally 块里执行

---

## 三、MySQL

#### Q17：6 种数据库的索引机制区别

> 知识点 → `src/database/mysql-index.md`

- MySQL（InnoDB）：B+树，聚簇索引，二级索引回表
- OceanBase：LSM-Tree，写优化
- PostgreSQL：B+树 + GIN/GiST/BRIN 多种索引类型

**你项目的处理：** 数据源适配层屏蔽差异，SQL 方言层处理不同语法

#### Q18：慢 SQL 定位和优化

> 知识点 → `src/database/mysql-index.md` 全文

**标准流程：**
1. 慢查询日志 / APM 定位
2. EXPLAIN 看 type → ALL(全表) 必须优化
3. 加索引（最左前缀），覆盖索引避免回表
4. 分页优化（大 offset 子查询）

**你的项目案例：**
"API 调用日志统计查询，按时间+API ID+来源应用组合查询，建了联合索引 idx(time, api_id, app_id)，查询从 3s → 50ms"

#### Q19：API 调用日志分库分表方案

- 日均百万级 → Kafka → ClickHouse/ES（时序分析），MySQL 只存汇总
- 如果必须 MySQL：按月分表（log_202501），ShardingSphere 路由

#### Q20：事务隔离级别 + MVCC + ReadView（RC vs RR）

> 知识点 → `src/database/mysql-transaction.md` 全文

**关键一句话：** RC 每次 SELECT 生成新 ReadView（见最新提交），RR 第一次 SELECT 生成 ReadView 整个事务复用（读一致性）

---

## 四、Redis

#### Q21：项目里 Redis 的使用场景

> 知识点 → `src/database/redis.md`

| 场景 | 实现 |
|------|------|
| API 限流 | Redis + Lua 令牌桶（按 API + 授权用户双维度） |
| 防重放 | Nonce 缓存 + 时间戳窗口 |
| Token 缓存 | AK/SK 和 Token 双模式的 Token 存储 |
| 路由热加载 | 路由配置缓存到 Redis，Gateway 监听变更 |
| 响应缓存 | 高频 API 返回结果缓存 |

**面试时选 2-3 个展开讲，不要全部罗列**

#### Q22：缓存穿透/击穿/雪崩

> 知识点 → `src/database/redis.md` 四章

**穿透**（查不存在的数据）：布隆过滤器 + 空值缓存
**击穿**（热点 key 过期）：互斥锁 / 逻辑过期
**雪崩**（大量 key 同时过期）：过期时间加随机值 + 多级缓存

**结合项目：** "开放平台的 API 元数据缓存，我用布隆过滤器防穿透"

#### Q23：Redis 分布式锁 + Redisson 看门狗

> 知识点 → `src/database/redis.md` 五章

- 手写：`SET key uuid EX 30 NX`
- Redisson：看门狗自动续期，每 10 秒续到 30 秒
- 注意：自己指定了 leaseTime，看门狗不生效

#### Q24：Redis 集群模式

主从 → 哨兵（自动故障转移）→ Cluster（16384 slot 分片）

---

## 五、Kafka

#### Q25：Kafka 怎么保证消息不丢失？

> 知识点 → `src/kafka/kafka-review.md`

```
生产者端：
  acks=all（所有 ISR 副本确认）
  retries=3（发送失败重试）
  enable.idempotence=true（幂等，防止重复）

Broker 端：
  replication.factor=3（至少 3 副本）
  min.insync.replicas=2（至少 2 个 ISR 确认）

消费者端：
  手动提交 offset（处理完再提交）
  enable.auto.commit=false
```

#### Q26：API 日志采集链路，消息重复怎么处理？

```
Kafka 的"至少一次"语义天然可能重复（生产者重试 / 消费者 rebalance）
消费者做幂等：
  - 日志采集：重复日志不敏感，保留即可
  - 如果要去重：消息体带唯一 ID，消费者用 Redis SETNX 判断是否已处理
```

#### Q27：ISR 机制 + 分区策略 + Rebalance

- **ISR**：和 Leader 保持同步的副本集合，消息提交 = 所有 ISR 副本确认
- **分区策略**：默认按 key hash，没 key 则轮询
- **Rebalance**：消费者组增减 / 超时触发，期间消费者暂停消费
  - max.poll.records × 单条耗时 < max.poll.interval.ms（否则反复 Rebalance 死循环）

---

## 六、Spring Boot / Spring Cloud

#### Q28：Spring Cloud Gateway 和 Zuul 的区别？

| | Spring Cloud Gateway | Zuul 1.x |
|------|------|------|
| 底层 | WebFlux（非阻塞） | Servlet（阻塞） |
| 性能 | 高（NIO） | 低（BIO） |
| 维护 | 官方主推 | Netflix 已停更 |

#### Q29：项目中用了哪些 Spring Cloud 组件？Nacos/Apollo 怎么实时生效？

- 你项目用了 Gateway
- Apollo 配置变更：ApolloConfigChangeListener 监听 → 刷新 Bean
- Nacos：长轮询 + 本地快照，服务端变更后推 config

#### Q30：Spring Bean 生命周期 + 循环依赖

> 知识点 → `src/spring/spring-review.md`

**Bean 生命周期（精简版）：**
```
实例化 → 属性填充 → Aware 回调 → BeanPostProcessor 前置处理
→ init-method → BeanPostProcessor 后置处理 → 就绪 → 销毁
```

**循环依赖（三级缓存）：**
- 一级（singletonObjects）：成品 Bean
- 二级（earlySingletonObjects）：半成品 Bean（属性未填充）
- 三级（singletonFactories）：创建半成品的工厂

A → B → A 的解决流程：
1. 创建 A，发现依赖 B → 把 A 的工厂放入三级缓存
2. 创建 B，发现依赖 A → 从三级缓存拿 A 工厂，创建 A 半成品放入二级缓存 → B 注入 A 半成品
3. B 创建完成 → A 注入 B → A 创建完成 → 从三级升级到一级

#### Q31：Spring 事务传播机制

| 传播行为 | 含义 |
|---------|------|
| REQUIRED（默认）| 有事务则加入，无则新建 |
| REQUIRES_NEW | 总是新建事务，挂起当前 |
| NESTED | 嵌套事务，内层回滚不影响外层 |

---

## 七、系统设计场景题

#### Q32：设计一个开放 API 网关，日均调用量从百万增长到千万，架构怎么演进？

**1. 当前架构（百万级）：**
```
客户端 → Gateway（单集群）→ 业务服务
           ↓
     Redis（限流/鉴权缓存）
     Kafka（日志采集）
```

**2. 演进方案（千万级）：**
```
客户端 → LB（Nginx/SLB）
        ├→ Gateway 集群（多实例，无状态）
        │    ├→ Redis Cluster（限流，按 API ID 分片）
        │    └→ 本地缓存 + Redis 二级缓存（鉴权信息）
        ├→ 业务服务集群
        └→ Kafka 集群（增加 partition）
```

关键变化：
- Gateway 无状态 → 水平扩容加实例即可
- Redis 单机 → Cluster 分片
- Kafka partition 太少 → 增加 partition（注意只能增不能减）
- 增加本地缓存层（Caffeine）减少 Redis 压力
- 降级策略：限流阈值降到保底水位，非核心日志采样采集

#### Q33：全链路压测怎么设计？

```
1. 压测目标：QPS 峰值、P99 延迟、错误率
2. 压测隔离：
   - 流量染色（Header 标记压测流量）
   - 影子表（避免污染生产数据）
   - 独立消费者组（不影响线上消费）
3. 压测工具：JMeter / wrk / 内部压测平台
4. 监控大盘：Grafana 面板（QPS、RT、错误率、CPU、内存、GC）
5. 应急预案：
   - 自动熔断（RT > 阈值直接降级）
   - 快速回滚（K8s 回滚到上一个版本）
   - 压测流量标记过期自动失效
```

---

## 八、高频行为面试题

#### BQ1：介绍一个你最有挑战的项目

**策略：优先讲网关项目（技术深度更硬核），如果面试官对 AI 感兴趣再展开 NL2SQL。**

**S.T.A.R 框架（网关版）：**
- **S**：开放平台 API 网关从 0 到 1 建设，面向全业务线 100+ 应用
- **T**：核心矛盾 — 要把内部数据 API 安全地开放给外部合作方，同时保证稳定性和可观测性
- **A**（选 2 个最深的技术决策展开）：
  1. 签名认证：两步哈希（SHA256 压缩 + HMAC 认证），Nonce 本地 Guava Cache 去重的 tradeoff
  2. 路由热加载：AtomicReference + Copy-on-Write 无锁实现，PathTrie O(n)→O(d) 路由匹配优化
- **R**：覆盖 100+ 应用、日均百万级调用、接入到发布 5s、限流 P99 < 0.5ms

**展开策略：** 面试官问"最有挑战"时，不要列功能清单，而是挑 1-2 个你最熟的决策深讲"为什么这么做 + 为什么不那样做"（参考 Q1-Q7 深度版）。

#### BQ2：项目中遇到的最难的技术问题？怎么解决的？

**推荐讲"最难"的准则：选一个你有图有真相、能讲出具体解决路径的问题。**

**备选案例 1（网关项目 — 最有画面感）：**
> 过滤器链中 Body 只能读一次的问题。WebFlux 的 RequestBody 是 Stream，读完就没了。签名校验要先读 Body 算 SHA256，读完后业务转发拿不到 Body。解决方案：自定义 CacheRequestBody 过滤器放在链最前面，用 `exchange.getAttributes().put(CACHED_REQUEST_BODY_ATTR)` 缓存 Body，后续过滤器从 Attribute 拿而不是重复读 Stream。这个过滤器必须放在**所有需要读 Body 的过滤器之前**，一次缓存到处复用。

**备选案例 2（NL2SQL — 最有量化数据）：**
> NL2SQL 准确率从 45% 提升到 72%（困难问题）。最初只用 Schema 信息，LLM 不知道"GMV"是什么。引入指标知识库 RAG 后，把业务语义（757 个指标的公式定义）作为上下文注入，困难问题准确率提升了 27%。关键发现：知识库的价值不在"教 LLM 写 SQL"，而在"告诉 LLM 业务上怎么算"。（参考 Q9 评测体系 + Ablation Study）

#### BQ3：你和产品 / 测试 / 前端有过什么冲突？怎么解决的？

**回答原则：**
- 以用户价值为判断标准
- 用数据说话，而非主观偏好
- 举例：产品想做某个功能，但技术评估成本极高 → 给出替代方案

---

## 九、复习清单

按优先级排列，打勾跟踪进度：

### P0（本周必过 — 项目深挖）
- [ ] Q1-Q7：网关项目话术，按"深度版"逐题打磨
- [ ] Q8-Q13：Agentic RAG + NL2SQL，重点 Q9 评测体系 + Q8 架构图
- [ ] Q14-Q16：知识库同步 + pgvector 选型 + API 设计
- [ ] 量化数据：1.4 节所有数字脱口而出
- [ ] Kafka：消息不丢失、重复消费、ISR 机制

### P1（高频八股）
- [ ] MySQL 慢 SQL 优化 + EXPLAIN
- [ ] Redis 缓存三兄弟（穿透/击穿/雪崩）
- [ ] JVM GC 调优 + Full GC 排查
- [ ] synchronized 锁升级 + ThreadLocal 内存泄漏
- [ ] Spring Bean 生命周期 + 循环依赖

### P2（场景设计）
- [ ] API 网关架构演进
- [ ] 全链路压测

### P3（加分项）
- [ ] MVCC + ReadView（RC vs RR）
- [ ] Redis 集群模式
- [ ] 分布式事务（Seata / MQ 最终一致性）
- [ ] 系统设计：秒杀 / 短链接

---

*生成日期：2026-05-25*
*目标岗位：理想汽车 Java 高级开发工程师*
*基于：JD + 杨卫简历*
