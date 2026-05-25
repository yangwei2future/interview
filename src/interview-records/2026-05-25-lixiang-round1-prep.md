# 理想汽车 Java 高级开发工程师 — 面试准备

> 基于 JD + 简历（杨卫）的个性化面试题库，共 33 题，按模块分类。
> 每题标注了对应知识文档，可跳转复习。

---

## 一、项目深挖（必问，占 40-50%）

### 1.1 开放平台 API 网关

#### Q1：AK/SK 签名认证的具体流程是怎样的？Nonce + 时间戳怎么防重放攻击？

> **详细学习文档** → [aksk-signature.md](aksk-signature.md)（基于项目 SignUtil.java 实际代码分析）

**精简版（3 分钟面试话术）：**

两步哈希 + HMAC：
1. 把请求的 6 个关键要素拼成规范请求串 → SHA256 压缩
2. `HMAC-SHA256(SK, 压缩后的摘要)` → Base64 → 放入 `x-dip-signature` header

防重放：Timestamp（5 分钟窗口校验）+ Nonce（Guava Cache 本地缓存，重复即拒绝）

**核心设计要点：**
- HMAC 不用 SHA256：HMAC 有密钥 SK，攻击者无 SK 算不出
- QueryString 按 key 升序排序：客户端/服务端参数顺序可能不同
- 只签关键 Header（x-dip-* + Content-Type）：通用 Header 经代理会被改写
- Body 签 SHA256 而非原文：大 Body 性能，定长 64 字符
- SK 不进签名字符串：作为 HMAC 密钥层的输入，不在数据层

#### Q2：Token 模式和 AK/SK 模式分别在什么场景使用？

- AK/SK：服务间调用，离线计算签名，不需要额外网络请求
- Token：浏览器端调用，登录后下发，方便控制过期和权限
- 开放平台做双模式：给用户最大灵活度

#### Q3：令牌桶和漏桶的区别？为什么选令牌桶？Redis + Lua 怎么保证原子性？

| | 令牌桶 | 漏桶 |
|------|------|------|
| 原理 | 固定速率放令牌，有令牌才能通过 | 请求进桶，固定速率漏出 |
| 突发流量 | 允许（桶里有积攒的令牌） | 不允许（严格匀速） |
| 适用场景 | API 限流（允许合理突发） | 流量整形（严格平滑） |

**Redis + Lua 原子性：** Lua 脚本在 Redis 中是原子执行的，不会被其他命令插队。

```
-- 核心逻辑：KEYS[1]=桶key, ARGV[1]=速率, ARGV[2]=容量
local tokens = redis.call('GET', KEYS[1])  -- 当前令牌数
if tokens > 0 then
    redis.call('DECR', KEYS[1])            -- 扣令牌
    return 1  -- 放行
else
    return 0  -- 限流
end
```

#### Q4：路由配置热加载不重启，具体怎么实现的？

```
1. 管理后台修改路由配置 → 写入 MySQL
2. 发布时同步到 Redis（通知机制 / 定时刷新）
3. Gateway 监听 Redis 变更 → 刷新 RouteDefinition
4. Spring Cloud Gateway 的 RouteDefinitionWriter 支持动态增删路由
```

#### Q5：过滤器链的顺序怎么设计的？短路机制是怎样的？

```
请求 → 签名校验 → 参数校验 → 限流 → 请求重写 → 业务转发
                                                                ↓
响应 ← 日志采集 ← 响应缓存 ← 响应重写 ←──────────────────────┘

签名校验失败 → 直接返回 401，不继续后续过滤器
限流触发 → 直接返回 429，不转发到业务
```

#### Q6：API 日均百万级调用，Kafka 采集日志有没有遇到过消息堆积？

**你的回答框架：**
- 百万级 / 86400秒 ≈ 12 QPS 平均，峰值可能 100-200 QPS
- Kafka 单 partition 能扛几万 TPS，日志采集场景不会成为瓶颈
- 如果有堆积 → 增加消费者实例 / partition 数

#### Q7：百万级调用下，Redis 限流有没有性能瓶颈？

- Redis 单机 10万 QPS 级别，限流只是 GET/SET 操作，完全够用
- 如果担心单机问题 → Redis Cluster 分片
- 限流维度：按 API + 授权用户两个维度，key 设计为 `ratelimit:{api_id}:{app_id}`

---

### 1.2 NL2SQL + Agentic RAG + MCP

#### Q8：Agentic RAG 的具体架构？MCP 协议在这里面扮演什么角色？

**架构（标准回答）：**
```
用户自然语言 → Agent 规划 → 拆解子问题
    ├→ 指标知识库召回（RAG）：检索相关指标/维度定义
    ├→ MCP Server 提供库表 Schema 检索（get_table_info 等）
    └→ LLM 根据召回的知识 + Schema 生成 SQL
```

**MCP 的角色：** 标准化的上下文提供协议，LLM 通过 MCP 协议调用库表检索能力，获取表结构和字段信息。

#### Q9：自然语言转 SQL 的准确率怎么评估？指标知识库提升了多少？

- 评估指标：SQL 语法正确率、语义准确率（生成的 SQL 是否真正回答用户问题）
- 知识库的价值：提供业务语义（"GMV" = sum(order_amount) where status='paid'），让模型不再猜
- 量化影响：有这个知识库之前 vs 之后的 NL2SQL 准确率对比

#### Q10：NL2SQL 过程中，怎么防止用户注入恶意 SQL？

```
1. LLM 生成的 SQL 只允许 SELECT，禁止 INSERT/UPDATE/DELETE/DROP
2. SQL 执行前做语法解析校验，检测危险关键字
3. 数据库连接使用只读账号，最小权限原则
4. 结果集行数限制（如最多 1000 行）
```

---

### 1.3 指标知识库

#### Q11：数据同步怎么保证知识库和指标平台的一致性？

**回答框架：**
```
1. Job 服务定时拉取指标平台的变更数据
2. 增量同步 + 全量对账：
   - 增量：每 5 分钟拉取近 5 分钟变更的指标
   - 全量：每天凌晨做一次全量对账，Diff 修复
3. 关键字段版本号比较，只更新变化的指标
4. 监控：对账差异数告警
```

#### Q12：20+ OpenAPI 接口设计，怎么考虑幂等性和版本管理？

- **幂等性**：写接口支持幂等键（idempotent_key），重复请求返回相同结果
- **版本管理**：URL 路径版本 `/api/v1/indicator/search` → `/api/v2/indicator/search`
- **向下兼容**：新增字段不能删除旧字段，废弃字段标记 @Deprecated + 文档说明

---

### 1.4 项目量化数据（面试官会追问）

**牢记这些数字：**
- 开放平台从 0 到 1，经历 6 期迭代
- 覆盖应用数 100+，角色包括研发、产品、运营
- API 日均调用量：百万级
- API 接入到发布：5 秒内完成
- 指标知识库：757 个指标、931 个维度
- 支持 6 种数据库：MySQL、OceanBase、PostgreSQL 等
- 20+ OpenAPI 接口

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

**S.T.A.R 框架回答：**
- S：开放平台从 0 到 1 建设，面向全业务线
- T：要搭建完整的 API 网关体系（鉴权、限流、日志、告警）
- A：AK/SK 签名认证 + Token 双模式、Redis+Lua 限流、可插拔过滤器链、Kafka 日志采集
- R：覆盖 100+ 应用、日均百万级调用、接入到发布 5s 内完成

#### BQ2：项目中遇到的最难的技术问题？怎么解决的？

**备选案例：**
- 过滤器链顺序设计（签名失败短路 vs 日志采集对所有请求生效）
- NL2SQL 准确率提升（从无知识库到引入 RAG + MCP）

#### BQ3：你和产品 / 测试 / 前端有过什么冲突？怎么解决的？

**回答原则：**
- 以用户价值为判断标准
- 用数据说话，而非主观偏好
- 举例：产品想做某个功能，但技术评估成本极高 → 给出替代方案

---

## 九、复习清单

按优先级排列，打勾跟踪进度：

### P0（本周必过）
- [ ] 项目话术：逐题打磨 Q1-Q12，用量化数据说话
- [ ] Kafka：消息不丢失、重复消费、ISR 机制
- [ ] Spring Cloud Gateway：原理 + 与 Zuul 区别

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
