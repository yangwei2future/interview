# Spring Cloud Gateway — 从原理到实践

## 零、网关基础概念

Spring Cloud Gateway 是 Spring 生态的 API 网关，基于 WebFlux + Netty（非阻塞 I/O），不依赖 Tomcat。三个核心概念：

```
请求 → Gateway → 后端服务

Route（路由）:     什么请求转发到什么地方
Predicate（谓词）:  匹配条件 —— 路径匹配、方法匹配、Header 匹配等
Filter（过滤器）:   请求处理 —— 鉴权、限流、重写、日志等
```

**一句话：谓词决定"这个请求归我管吗"，过滤器决定"管它的时候做什么"。**

---

## 一、动态路由（数据库驱动）

### 核心问题

传统 YAML 配置路由方式，每次新增或修改 API 都需要改配置文件 → 重新部署。面对上百个 API 不可维护。

### 解决方案

实现 Spring Cloud Gateway 预留的 SPI 接口 `RouteDefinitionRepository`：

```java
public interface RouteDefinitionRepository {
    Flux<RouteDefinition> getRouteDefinitions();  // Gateway 启动时从这里拿路由
    Mono<Void> save(Mono<RouteDefinition> route); // 保存路由（可空实现）
    Mono<Void> delete(Mono<String> routeId);      // 删除路由（可空实现）
}
```

核心类 `DynamicRouteDefinitionRepository`：
1. 启动时从 MySQL 查询路由表 → 转成 `RouteDefinition` 对象 → 返回
2. 路由缓存到 `volatile Map`，避免重复查库
3. POST `/admin/refresh-routes` 手动触发热刷新

### 热刷新机制

```java
repository.reload();  // 重新查数据库，更新缓存
publisher.publishEvent(new RefreshRoutesEvent(this));  // 通知 Gateway 重载路由
```

Gateway 收到事件后重新调用 `getRouteDefinitions()`，路由表原地更新，不中断正在处理的请求。

### 接口：SPI（Service Provider Interface）

框架定义接口，你提供实现，框架运行时发现并调用你的实现。JDBC 驱动、Servlet 容器都是这个模式。Gateway 通过 `RouteDefinitionRepository` 让你把路由数据源从 YAML 换成任何东西——数据库、Redis、Nacos、Consul 都可以。

---

## 二、PathTrie 前缀树路由匹配

### 核心问题

默认路由匹配是 O(n) 的谓词遍历 —— 请求来了遍历所有路由逐个做谓词匹配。当路由从几条增长到几百条时，遍历开销不可接受。

### 解决方案

把路由 path 组织成一棵前缀树（Trie），匹配时直接沿树的路径下探，O(路径段数)：

```
root
 └─ GET_
     ├─ baidu ──────── route-baidu
     ├─ echo/test ──── route-echo
     └─ httpbin
          └─ ** ────── route-httpbin

匹配 GET /httpbin/get：
  GET_ → 命中 → httpbin → 命中 → get 不命中 → ** 命中 → 返回 route-httpbin
  仅 3 次 HashMap.get() 查找
```

### 匹配优先级

| 通配符 | 含义 | 优先级 |
|--------|------|--------|
| 精确匹配 | 段名完全一致 | 最高 |
| `*` | 单段通配 | 中 |
| `**` | 多段通配（任意层级） | 最低 |

未命中时兜底走默认的谓词遍历，保证可用性。

### 数据结构

```java
class Node<V> {
    Map<String, Node<V>> children = new ConcurrentHashMap<>();  // O(1) 查找
    V value;  // 叶子节点存 Route 对象，中间节点为 null
}
```

无黑科技，就是嵌套 HashMap。空间换时间，跟 Nginx location 匹配、Spring MVC AntPathMatcher 同一思路。

---

## 三、AK/SK 签名认证（类 AWS SigV4）

### 核心问题

Token 明文传输不安全，谁截获就能冒充。需要在不传输密钥的前提下验证身份。

### 签名流程

```
客户端                                    服务端
1. 获取 AK、SK
2. 生成 timestamp + nonce
3. 拼接规范请求串:
   method + "\n"
   + path + "\n"
   + query参数(key升序URL编码) + "\n"
   + x-dip-*头(key升序，排除signature) + "\n"
   + 已签名头列表 + "\n"
   + SHA256(body)
4. HMAC-SHA256(规范请求串, SK) → signature
5. 发送: AK + timestamp + nonce + signature  ──→  6. 查 AK → 得到 SK
                                                   7. 验证 timestamp ±5min
                                                   8. 验证 nonce 未重复（防重放）
                                                   9. 重算签名 → 对比
```

### 三个保护层

| 机制 | 防什么 | 实现 |
|------|--------|------|
| Timestamp ±5min | 请求被截获后长期重放 | 毫秒级时间戳 |
| Nonce（一次性随机数） | 短时间窗口内重复提交 | ConcurrentHashMap + 5min TTL |
| Signature | 参数被篡改、伪造身份 | HMAC-SHA256 |

### 关键细节

- HMAC-SHA256 是哈希不是加密，没有解密概念，就是重算对比
- SK 只在客户端和服务端各存一份，不传输
- `x-dip-signature` 头本身不参与签名计算（客户端算签名时这个头还不存在）
- Header 大小写必须统一处理（`toLowerCase`）

---

## 四、分布式限流（Redis 令牌桶 + Lua）

### 核心问题

多实例网关部署时，Guava 本地内存限流器无法跨实例协调。实例 A 不知道实例 B 发了多少，两个实例各限 1000 QPS，实际可能打 2000 QPS。

### 令牌桶算法

```
                  ┌──────────────┐
每秒补充 N 个 →   │   令牌桶      │  → 请求来取令牌
                  │ [●] [●] [●]  │    有 → 放行
                  │ 最多存 M 个   │    没 → 拒绝（429）
                  └──────────────┘
```

懒计算：不在后台每秒补充，而是请求来时算"过了多少秒 × 速率 = 补了多少"。

### 为什么用 Lua 脚本

Redis 操作"判断 + 扣减"分两步是竞态条件：

```
线程 A: 读 1 个令牌
线程 B: 读 1 个令牌
线程 A: 扣为 0
线程 B: 扣为 -1  ← 超发了
```

Lua 脚本在 Redis 单线程中原子执行，判断 + 扣减一步完成：

```lua
local delta = math.max(0, now - last_refreshed)
local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
local allowed = filled_tokens >= requested
if allowed then
    new_tokens = filled_tokens - requested
end
redis.call("setex", tokens_key, ttl, new_tokens)
return { allowed and 1 or 0, new_tokens }
```

### 三层限流体系

| 层级 | 实现 | 范围 | 作用 |
|------|------|------|------|
| 全局 | Guava RateLimiter（内存） | 单实例 | 粗筛，快速拒绝 |
| API 级 | Redis + Lua（令牌桶） | 集群 | 精确控制每个 API 的 QPS |
| 授权级 | Redis + Lua（令牌桶） | 集群 | 精确控制每个应用-API 调用的 QPS |

### 降级策略

Redis 不可用时不能把请求全拦了：

```java
.onErrorResume(e -> chain.filter(exchange))  // Redis 挂了降级放行
.switchIfEmpty(chain.filter(exchange))       // 极端情况降级放行
```

---

## 五、过滤器链

### GlobalFilter vs GatewayFilterFactory

| 类型 | 接口 | 作用范围 | 注册方式 |
|------|------|---------|---------|
| GlobalFilter | `implements GlobalFilter` | 所有路由，路由匹配之前执行 | `@Component` 自动生效 |
| GatewayFilterFactory | `extends AbstractGatewayFilterFactory` | 按路由配置，路由匹配之后执行 | 代码或 YAML 中配置 |

### 公司网关完整过滤器链（17个）

```
[全局] GlobalRateLimitFilter        — Guava 令牌桶，单机入口限流
[全局] GlobalLoggingFilter          — 请求/响应日志 + Kafka 审计 + oTel

[路由] CheckParamFilter             — AK/Token 提取，识别调用者
[路由] WhiteListFilter              — IP 白名单（精确 + 前缀匹配）
[路由] CacheRequestBody             — 缓存请求体（WebFlux body 流只能读一次）
[路由] CheckSignFilter              — 签名/Token 验证
[路由] CheckAuthFilter              — 授权检查（API 所有者或资产授权表）
[路由] ApiRateLimiterFilter         — API 级 Redis 分布式限流
[路由] AuthedRateLimiterFilter      — 授权级 Redis 分布式限流
[路由] RequestTimeoutFilter         — Reactor .timeout() 超时返回 504
[路由] RewriteRequestFilter         — 追加参数、清理 x-dip-* 头、注入 x-dip-app
[路由] CheckRequestFilter           — 参数值校验（固定值/范围值）
[路由] DynamicHostFilter            — Apollo 配置动态覆盖域名
[路由] 插件（数据库定义）            — 按 executeOrder 排序的可扩展过滤器
[路由] CacheResponseFilter          — GET 响应 Redis 缓存（ServerHttpResponseDecorator）
[路由] RewritePath                  — 路径改写转发
```

---

## 六、RouteDefinition vs Route

| | RouteDefinition | Route |
|------|---------|------|
| 阶段 | 配置定义 | 运行时实例 |
| Predicate | 字符串 args（`{name: "Path", args: {patterns: "/api/**"}}`） | 编译后的 Predicate 对象 |
| Filter | 字符串 args | 编译后的 GatewayFilter 对象 |
| 你代码里的角色 | `DynamicRouteDefinitionRepository` 返回 | PathTrie 里存的就是 Route |

Gateway 内部把 RouteDefinition 转换成 Route（CachingRouteLocator 完成），PathTrie 存的不是定义而是运行时对象。

---

## 七、压力测试体系

### 环境策略

```
JMeter(Mac/压测机) → Gateway + Echo(192.168.1.4/被测机)

Gateway 和 Echo 同机部署 → 网关→下游网络延迟 = 0 → 消去网络噪声
```

### 场景设计

| 场景 | 链路 | 测什么 |
|------|------|--------|
| S0 | JMeter → Echo | 下游基线 |
| S1 | JMeter → Gateway → Echo | 网关纯转发开销 |
| S2 | JMeter → Gateway(+签名) → Echo | 签名认证开销 |

```
S1 - S0 = 纯网关开销
S2 - S1 = 纯签名开销
```

### JVM 配置思路

- 网关（4C8G）：堆 4G，Netty IO Worker 8（核数×2），G1 50ms 停顿目标
- 下游（4C8G）：堆 4G，Tomcat 800 线程，G1 100ms 停顿目标
- 每个参数都有"为什么是这个值"的理由，不是背参数

### 压测规范

- 10 分钟起步（5 分钟预热不计入结果）
- KeepAlive 对齐（开关不一致测的是建连性能而非网关性能）
- CLI 模式（GUI 消耗资源干扰结果）
- P95/P99 比平均延迟更重要

---

## 八、面试问题速答

**Q: 动态路由怎么实现的？**
> `RouteDefinitionRepository` SPI，从 MySQL 加载，`RefreshRoutesEvent` 热刷新，15 秒定时 + 手动触发。

**Q: 路由多了怎么保证性能？**
> PathTrie 前缀树，O(路径段数) 匹配，精确 > * > ** 优先级，未命中兜底默认谓词遍历。

**Q: 签名怎么会不上去？**
> HMAC-SHA256，不是加密是哈希。两边用同样的规范请求串重算，SK 不传输。踩过的坑：signature 头本身被错误纳入签名计算。

**Q: 多实例限流怎么做？**
> Redis 令牌桶 + Lua 脚本。Redis 单线程保证 Lua 原子执行。Redis 挂了降级放行。

**Q: WebFlux 请求体只能读一次怎么处理？**
> CacheRequestBody 缓存到 exchange attributes，后续过滤器都从 attributes 读。需要修改 body 时用 ServerHttpRequestDecorator 重构。

**Q: API 生命周期怎么管理的？**
> 三态（开发中→发布中→已发布）+ 上线开关。状态变化和网关路由刷新联动，`status=3 AND is_online=1` 时网关才加载路由，最多 15 秒自动生效。

---

## 九、术语表

| 术语 | 含义 |
|------|------|
| SPI | Service Provider Interface，框架定义接口你提供实现 |
| Route | 网关里一条路由的完整配置（URI + Predicates + Filters） |
| Predicate | 谓词，判断请求是否匹配某个路由的条件函数 |
| HMAC-SHA256 | 基于哈希的消息认证码，用到 SHA-256 |
| Nonce | Number used once，一次性随机数，防重放攻击 |
| RateLimiter | 限流器，控制请求速率的组件 |
| 令牌桶 | 限流算法：固定速率补充令牌，请求需消耗令牌 |
| 前缀树/Trie | 字符串检索树：每段路径一个节点，共用公共前缀 |
| WebFlux | Spring 响应式编程框架，基于 Project Reactor，运行在 Netty 上 |
| DataBuffer | Netty 的字节缓冲区，WebFlux 中操作响应体必须用它 |
