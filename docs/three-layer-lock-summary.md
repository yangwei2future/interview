# 三层锁机制：JVM → Redis → MySQL

> 按"原理 → 适用场景 → 局限性 → 选型决策"串联三层锁。

---

## 一、JVM 锁（synchronized / ReentrantLock）

### 核心脉络

```
synchronized（JVM 内置）
  │
  ├─ 锁状态存在对象头的 Mark Word 里（64位，最后2位是锁标志位）
  │
  ├─ 锁升级路径（不可逆）：
  │   无锁(01) → 偏向锁(01+偏向位=1) → 轻量级锁(00) → 重量级锁(10)
  │                  ↑                          ↑              ↑
  │             同一线程反复进            多线程交替CAS+自旋   OS Mutex，线程阻塞
  │
  └─ 缺点：不能超时、不能中断、只有一个等待队列

ReentrantLock（JDK 5，基于 AQS）
  │
  ├─ AQS 核心：volatile int state + CLH 等待队列
  │   state=0 未锁，state=1 已锁，state>1 重入次数
  │
  └─ 比 synchronized 多的能力：
       tryLock(超时) / lockInterruptibly() / 公平锁 / 多个 Condition
```

### volatile

保证**可见性**（一个线程改，其他线程立刻看到），不保证**原子性**。`count++` 三步操作 volatile 拦不住，需要用 `AtomicInteger` / `synchronized`。

### 本质限制

**跨 JVM 无效。** 服务器A 的线程拿到锁，服务器B 的线程完全不知道，照样进同步块。分布式多节点部署下 JVM 锁直接失效。

---

## 二、Redis 分布式锁

### 演进过程

| 版本 | 方案 | 问题 |
|------|------|------|
| v1 | `SETNX lock 1` | 线程宕机 → 死锁 |
| v2 | `SET lock 1 EX 30 NX` | 业务超30s → 锁过期，其他线程趁虚而入 |
| v3 | 看门狗自动续期（Redisson） | 服务器宕机看门狗也挂 → 锁过期后误删 |
| v4 | UUID 验证 + Lua 原子释放 | 最终方案 |

### Redisson 底层

```java
RLock lock = redisson.getLock("order:lock:123");
lock.lock();
// 实际做的事：
// 1. SET order:lock:123 <uuid:threadId> EX 30 NX
// 2. 看门狗每10秒检查，还在执行就续期到30秒
// 3. 加锁失败 → 订阅 Redis 频道，等释放通知
// 4. unlock() → Lua 脚本：验证 UUID 是自己的才 DEL
```

Lua 脚本保证"判断 + 删除"两步原子：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

### 为什么用 Redis 而不是 Zookeeper

| | Redis | Zookeeper |
|--|-------|-----------|
| 性能 | 高（纯内存，10万+QPS） | 低 |
| 一致性 | AP（最终一致） | CP（强一致） |
| 上手成本 | 低，公司基本都有 | 高，需单独运维 |
| 适用场景 | 大部分业务 | 金融核心 |

### Redisson 的局限

Redis 主从异步复制，极端情况：
```
线程A 在 Redis 主节点拿到锁
主节点挂了，还没同步到从节点
从节点升级为新主，锁数据丢了
线程B 在新主也拿到同一把锁
→ 两个线程同时执行
```

需要强一致性时用 **RedLock** 或 **Zookeeper**。

---

## 三、MySQL 锁

### 三条主线

**主线一：锁的粒度**

```
表锁（MyISAM）→ 行锁（InnoDB）→ 行锁三种算法

行锁加在索引上，不走索引退化成表锁 ← 生产最常踩的坑
```

| 算法 | 锁什么 | 触发条件 |
|------|--------|---------|
| Record Lock | 只锁记录本身 | 唯一索引等值，记录存在 |
| Gap Lock | 只锁间隙 | 唯一索引等值，记录不存在 |
| Next-Key Lock | 间隙 + 右边记录 | 普通索引查询（RR默认） |

**主线二：读的方式**

```
快照读（普通 SELECT）→ MVCC → 不加锁，读历史版本
当前读（FOR UPDATE / UPDATE / DELETE）→ 加锁，读最新数据
```

**主线三：处理并发的思想**

```
悲观锁：FOR UPDATE → 先锁住再操作 → 写多冲突场景
乐观锁：version 字段 → 提交时校验 → 读多写少场景
```

### 死锁

四个必要条件（互斥、持有并等待、不可剥夺、循环等待），**能破坏的只有循环等待**。

最根本的防死锁手段：所有事务统一按 id 升序加锁。

---

## 四、三层锁的选型决策

```
1. 单机多线程竞争？
   → JVM 锁（synchronized / ReentrantLock）

2. 分布式多节点部署？
   → 纯数字增减 → Redis 原子操作（DECR/INCR，不需要锁）
   → 多步操作需要互斥 → Redis 分布式锁（Redisson）
   → 需要强一致性 → Zookeeper

3. 数据库层面兜底？
   → 扣库存 → WHERE stock>0（原子条件，不用锁）
   → 先查再改 → FOR UPDATE（悲观锁）或 version 字段（乐观锁）
```

## 五、面试万能回答模板

> "生产上分层用锁。入口 Sentinel 限流，热点用 Redis DECR 原子扣减挡掉 99% 流量，数据库 WHERE stock>0 兜底防超卖。Redis 分布式锁用的 Redisson，看门狗自动续期 + Lua 脚本原子释放。JVM 锁基本只在单机工具类用，不参与业务并发控制。"
