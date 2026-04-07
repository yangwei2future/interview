# Java 葵花宝典

> 系统整理 Java 相关知识点，包含代码示例、题解和学习笔记。

## 目录结构

```
src/
├── java-basics/        # Java 基础（集合、泛型、IO、反射等）
├── data-structures/    # 数据结构（链表、树、堆、图等）
├── algorithms/         # 算法（排序、搜索、动态规划等）
├── design-patterns/    # 设计模式（GoF 23种）
├── spring/             # Spring / Spring Boot 原理
├── jvm/                # JVM（内存模型、GC、类加载）
├── concurrent/         # 并发编程（线程、锁、JUC）
├── database/           # 数据库（MySQL、Redis、索引优化）
├── system-design/      # 系统设计（分布式、微服务、高并发）
└── behavioral/         # 行为面试题（STAR 法则）
```

## 使用说明

每个目录包含：
- `.md` 知识点笔记
- `.java` 代码示例（可直接运行）

## 并发编程 / 线程池

### ThreadPoolFailover.java — 线程池异常处理与故障恢复

| Demo | 知识点 |
|------|--------|
| DEMO 1 | `execute()` vs `submit()` 异常行为差异，submit 异常静默丢失陷阱 |
| DEMO 2 | 任务抛异常后线程被销毁，线程池自动补充新线程 |
| DEMO 3 | 全局 `UncaughtExceptionHandler` 兜底捕获未处理异常 |
| DEMO 4 | 优雅关闭：`shutdown()` + `awaitTermination()` + `shutdownNow()` |
| DEMO 5 | 生产级监控线程池：`beforeExecute` / `afterExecute` 钩子统计耗时与失败数 |

---

### ThreadPoolBackpressureDemo.java — 任务堆积排查实战

> 场景：队列积压持续增长，活跃线程始终等于 maxPoolSize，如何系统排查？

| Demo | 场景 | 核心手段 |
|------|------|---------|
| DEMO 1 | **制造堆积** | 2 线程 + 慢任务(500ms) + 高频提交(50ms/个)，直观观察队列单调递增 |
| DEMO 2 | **监控发现趋势** | 周期采样 `getQueue().size()` / `getActiveCount()` / `getCompletedTaskCount()`，通过趋势判断堆积阶段 |
| DEMO 3 | **定位慢任务根因** | `beforeExecute` / `afterExecute` 打耗时，超阈值立刻告警，精确暴露慢SQL/HTTP超时/大文件IO |

**排查三步法：**
```
1. 监控队列趋势          → queue 持续增长 = 生产 > 消费
2. 确认线程全部繁忙      → active == maxPoolSize，扩容或减少任务耗时
3. 找出慢任务            → 钩子记录耗时，告警 > 阈值的任务，定向优化
```

---

## 学习计划 & 进度

### 阶段一：并发编程（进行中）
- [x] 线程池核心参数与工作原理
- [x] 线程池异常处理与故障恢复
- [x] 任务堆积排查实战
- [x] 线程池动态配置（Apollo）
- [x] ThreadFactory & UncaughtExceptionHandler
- [ ] synchronized 锁升级（偏向锁→轻量级锁→重量级锁）
- [ ] volatile 可见性 & 禁止指令重排（happens-before）
- [ ] AQS 原理（ReentrantLock / CountDownLatch / Semaphore）
- [ ] ConcurrentHashMap 1.7 vs 1.8 实现对比

### 阶段二：Redis（下一阶段）
**基础数据结构**
- [ ] String / Hash / List / Set / ZSet 底层编码与使用场景
- [ ] 为什么 Redis 单线程还这么快？（I/O 多路复用）

**持久化**
- [ ] RDB 原理（bgsave / fork 写时复制）
- [ ] AOF 原理（三种刷盘策略）& AOF 重写
- [ ] RDB vs AOF 选型对比

**内存管理**
- [ ] 过期键删除策略（惰性删除 + 定期删除）
- [ ] 内存淘汰策略（8 种策略 + 选型建议）

**缓存经典问题**
- [ ] 缓存穿透（布隆过滤器 / 空值缓存）
- [ ] 缓存击穿（互斥锁 / 逻辑过期）
- [ ] 缓存雪崩（随机 TTL / 多级缓存）
- [ ] 缓存一致性（先删缓存 vs 先更 DB / 延迟双删）

**分布式应用**
- [ ] 分布式锁（SET NX + Lua 脚本 vs Redisson WatchDog）
- [ ] Redis 实现限流（滑动窗口 / 令牌桶）
- [ ] 消息队列（List / Stream 对比）

**高可用架构**
- [ ] 主从复制原理（全量同步 / 增量同步）
- [ ] 哨兵模式（故障检测 + 自动切换）
- [ ] Cluster 分片原理（哈希槽 / 节点通信）

**结合简历的场景题**
- [ ] API 日均百万调用的缓存架构设计
- [ ] 如何用 Redis 实现 API 限流（令牌桶实战）
- [ ] 分布式锁防止重复提交场景实战

### 阶段三：MySQL
- [ ] B+ 树索引结构与查询优化
- [ ] 联合索引 & 覆盖索引 & 索引下推
- [ ] 事务 ACID & 隔离级别
- [ ] MVCC 原理（ReadView / undo log）
- [ ] 行锁 / 间隙锁 / 死锁排查

### 阶段四：JVM
- [ ] 内存区域（堆 / 栈 / 方法区 / 元空间）
- [ ] GC 算法（标记清除 / 标记整理 / 复制）
- [ ] 垃圾收集器（G1 重点）
- [ ] 类加载机制 & 双亲委派

### 阶段五：Spring
- [ ] IoC 容器原理 & Bean 生命周期
- [ ] AOP 动态代理（JDK vs CGLIB）
- [ ] 事务传播机制（7 种）& 事务失效场景

### 阶段六：并发编程（补充）
> AQS 等内容如未在阶段一完成，此处继续

---

## 原始目录索引
- [ ] Java 基础（集合 / 泛型 / IO / 反射）
- [ ] 数据结构 & 算法
- [ ] 设计模式
- [ ] 系统设计
- [ ] 行为面试（STAR）
