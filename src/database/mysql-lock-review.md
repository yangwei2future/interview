# MySQL 锁机制 — 面试复习手册

> 本文按照"背景 → 原理 → 案例 → 关键结论"组织，专为面试前快速复习使用。
> 随学习进度持续更新。

---

## 一、表锁 vs 行锁

### 背景：为什么从表锁进化到行锁？

MySQL 早期存储引擎 MyISAM 用表锁——实现简单，但锁一张表期间所有并发操作全部阻塞，并发能力很差。

InnoDB 引入行锁——只锁你操作的那几行，其他行的并发完全不受影响，并发能力大幅提升。

现在生产环境几乎全用 InnoDB，MyISAM 基本退出历史舞台。

### 核心原理：行锁为什么加在索引上？

InnoDB 的数据存储结构是聚簇索引（B+ 树），**行数据就是主键索引的叶子节点**，数据和索引是一体的，不存在独立的"行存储"。

所以：**锁行 = 锁索引节点**，这是 InnoDB 的存储结构决定的，不是人为设计选择。

**InnoDB 一定有聚簇索引（无法绕开）：**
- 建了主键 → 主键作为聚簇索引
- 没建主键，有唯一索引 → 第一个唯一非空索引作为聚簇索引
- 两个都没有 → InnoDB 自动生成隐藏 6字节 ROW_ID 作为聚簇索引

### ⚠️ 致命细节：不走索引，行锁退化成表锁

InnoDB 加锁在存储引擎层，WHERE 条件过滤在 Server 层。存储引擎不知道哪行最终匹配，**边扫边锁，扫到哪行锁哪行**，不匹配的行锁也不释放。

全表扫描 → 所有行都被锁 → 等同于表锁。

```sql
-- ❌ name 没有索引，全表扫描，锁所有行（等同表锁）
SELECT * FROM users WHERE name = '张三' FOR UPDATE;

-- ✅ id 是主键，走索引，精准锁 id=1 这一行
SELECT * FROM users WHERE id = 1 FOR UPDATE;
```

### 表锁 vs 行锁对比

| | 表锁 | 行锁 |
|--|------|------|
| 锁的范围 | 整张表 | 某几行 |
| 并发能力 | 低 | 高 |
| 死锁风险 | 无 | 有 |
| 适用引擎 | MyISAM | InnoDB |

---

## 二、INSERT 的锁机制

### 背景：INSERT 数据还不存在，怎么加锁？

UPDATE/DELETE 锁的是已有的行（锁索引节点），但 INSERT 的数据还不在 B+ 树里，无法用普通行锁。InnoDB 为此设计了两阶段的锁机制。

### 插入前：检查间隙是否有 Gap Lock

INSERT 要往 B+ 树某个位置插入新节点，先检查目标间隙有没有 Gap Lock。有则阻塞等待，防止破坏其他事务的防幻读保护。

```sql
-- 事务A：id=4 不存在，加 Gap Lock 锁住 (3,5) 间隙
SELECT * FROM orders WHERE id=4 FOR UPDATE;

-- 事务B：要往 (3,5) 间隙插入 → 被阻塞！等事务A提交才能执行
INSERT INTO orders(id) VALUES(4);
```

### 插入后：隐式锁（不是普通行锁）

INSERT 成功后，新行上**没有显式行锁**，而是通过行数据的隐藏字段 `trx_id` 实现隐式保护。

```
INSERT 成功 → 新行 trx_id = 当前事务ID（如 trx_id=100）

其他事务想操作这行时：
→ 发现 trx_id=100 对应的事务还活着（未提交）
→ 自动将隐式锁升级为显式行锁
→ 阻塞等待事务100提交
```

**为什么不直接加显式锁？** 大多数 INSERT 没有冲突，显式锁占内存。隐式锁是"没冲突不记录，有冲突再升级"，节省开销。

### INSERT 锁机制总结

| 阶段 | 锁行为 |
|------|--------|
| 插入前 | 检查目标间隙有无 Gap Lock，有则阻塞 |
| 插入后 | 隐式锁（trx_id 标记），有冲突才升级为显式行锁 |

---

## 三、S锁 vs X锁

### 背景：为什么需要区分读锁和写锁？

并发场景下，读和读之间没有冲突（两个人同时看同一份数据完全没问题），如果用同一种锁，读和读之间也互相阻塞，性能极差。所以需要区分两种锁，让读读之间可以并发。

### S锁（共享锁 / 读锁）

多个事务可以**同时**持有同一行的 S 锁，互不影响。但持有 S 锁期间，其他事务不能加 X 锁（写会阻塞）。

```sql
SELECT * FROM users WHERE id=1 LOCK IN SHARE MODE;  -- 手动加 S 锁
```

**注意：普通 SELECT 不加任何锁**，走 MVCC 快照读，读历史版本，读写互不阻塞。只有显式写 `LOCK IN SHARE MODE` 才加 S 锁。

### X锁（排他锁 / 写锁）

同一时间只能一个事务持有，完全独占。其他事务加 S 锁或 X 锁都阻塞。

```sql
SELECT * FROM users WHERE id=1 FOR UPDATE;   -- 手动加 X 锁
UPDATE users SET name='李四' WHERE id=1;     -- 自动加 X 锁
```

### 兼容关系

口诀：**读读兼容，有写必冲突。**

| | 已有 S 锁 | 已有 X 锁 |
|--|-----------|-----------|
| 再加 S 锁 | ✅ 兼容 | ❌ 阻塞 |
| 再加 X 锁 | ❌ 阻塞 | ❌ 阻塞 |

### ⚠️ 经典坑：S锁升级 X锁导致死锁

```sql
-- 事务A                                  -- 事务B
BEGIN;                                    BEGIN;
SELECT ... LOCK IN SHARE MODE;            SELECT ... LOCK IN SHARE MODE;
-- S+S 兼容，两个都拿到了，没问题

UPDATE ...;                               UPDATE ...;
-- 事务A 想升级为 X 锁                    -- 事务B 也想升级为 X 锁
-- 等事务B 释放 S 锁                      -- 等事务A 释放 S 锁
-- 互相等对方先放手 → 死锁！
```

死锁本质：两个事务都持有 S 锁，都想升级为 X 锁，互相等对方先释放。

**结论：查完要改，直接用 `FOR UPDATE`，不要先 `LOCK IN SHARE MODE` 再 UPDATE。**

### 总结

| | S锁 | X锁 |
|--|-----|-----|
| 语法 | `LOCK IN SHARE MODE` | `FOR UPDATE` / UPDATE / DELETE |
| 能否共存 | 多个事务可同时持有 | 独占，只能一个 |
| 用途 | 读最新值且防写 | 读最新值且要改 |
| 生产使用频率 | 少 | 多 |

---

## 四、意向锁（IS / IX）

### 背景：没有意向锁，加表锁有多慢？

场景：DBA 想对整张表加写锁。加之前必须确认表里有没有行在被锁，不然会冲突。没有意向锁只能逐行扫描，1000万行 = 1000万次检查，无法接受。

### 解决方案：表级标记位

每次事务加行锁时，InnoDB **自动**在表上加一个意向锁作为标记：

- 要加行 S 锁前 → 先在表上加 **IS 锁**（意向共享锁）
- 要加行 X 锁前 → 先在表上加 **IX 锁**（意向排他锁）

```sql
-- InnoDB 自动完成：
-- 1. users 表加 IX 锁（表级标记）
-- 2. id=1 这行加 X 锁（行级锁）
SELECT * FROM users WHERE id=1 FOR UPDATE;
```

DBA 加表锁前只需看一眼表上有没有意向锁，O(1) 搞定。

### 意向锁与表锁的兼容关系

| | 表读锁 | 表写锁 |
|--|--------|--------|
| IS 锁 | ✅ 兼容 | ❌ 冲突 |
| IX 锁 | ❌ 冲突 | ❌ 冲突 |

意向锁之间互相兼容（多个事务可以同时标记）。

### 三点结论

1. **意向锁只为表锁服务**，跟行锁之间没有任何关系
2. **InnoDB 自动加**，不需要也不能手动操作
3. **本身不锁任何数据**，只是表头的标记位，存在的唯一目的是让加表锁能 O(1) 判断表内有无行锁

---

## 五、行锁三种算法：Record Lock / Gap Lock / Next-Key Lock

### 背景：只锁一行够用吗？

光有"锁住某一行"还不够：

```
事务A：查询 age > 18 的用户做统计，第一次查到3个结果
事务B：同时插入一个 age=20 的新用户
事务A：第二次查到4个结果 ← 同一事务两次结果不一样，这就是幻读
```

只锁已有的行，防不住别人插入新行。所以 InnoDB 设计了三种算法应对不同场景。

### 心理模型：记录 + 间隙

表数据 id = 1, 3, 5, 10，B+ 树上形成这些区域：

```
(-∞, 1)  [1]  (1, 3)  [3]  (3, 5)  [5]  (5, 10)  [10]  (10, +∞)
```

方括号是记录本身，圆括号是记录之间的空隙。

---

### Record Lock：只锁记录本身

**触发条件：唯一索引等值查询，且记录存在。**

```sql
-- id=5 存在，只锁 [5] 这一个节点，其他行不受影响
SELECT * FROM users WHERE id=5 FOR UPDATE;
```

---

### Gap Lock：只锁间隙，不锁记录

**触发条件：唯一索引等值查询，且记录不存在。**

```sql
-- id=4 不存在，锁住 (3, 5) 间隙
SELECT * FROM users WHERE id=4 FOR UPDATE;
```

- 插入 id=4 → ❌ 阻塞
- 修改 id=3 或 id=5 → ✅ 可以（Gap Lock 不锁记录本身）

**目的只有一个：防止往间隙里插新数据，解决幻读。**

---

### Next-Key Lock：间隙 + 右边那条记录（左开右闭）

**触发条件：普通索引查询（RR 隔离级别下的默认算法）。**

Next-Key Lock = **一个间隙 + 这个间隙右边那条记录**，是一个组合单元。

```sql
-- age 是普通索引，表数据 age = 3, 5, 10
-- 查 age=5，InnoDB 加两个 Next-Key Lock：
--   第一个：(3, 5]  ← 间隙(3,5) + 右边记录[5]
--   第二个：(5, 10] ← 间隙(5,10) + 右边记录[10]
-- 实际效果：(3, 10] 整段都被锁住
SELECT * FROM users WHERE age=5 FOR UPDATE;
```

**为什么要加两个？**
- `(3, 5]`：锁住左边间隙 + 记录本身，防止插入 age=4
- `(5, 10]`：普通索引可能有多个相同值，不锁右边间隙，别人可以从右边插入另一个 age=5，幻读没解决

**"Next-Key" 的含义：间隙 + 紧挨着的下一个 key（右边那条记录）。**

---

### 三种算法触发规律

```
唯一索引 + 记录存在   →  Record Lock（最小，只锁这一行）
唯一索引 + 记录不存在  →  Gap Lock（只锁间隙）
普通索引查询          →  Next-Key Lock（两个组合单元，覆盖目标值前后的完整范围）
范围查询              →  覆盖整个范围的 Next-Key Lock
```

**触发条件详细说明：**

```sql
-- ✅ Record Lock：唯一索引（主键/唯一索引）等值查询，且记录存在
SELECT * FROM users WHERE id=5 FOR UPDATE;                  -- 主键，id=5存在
SELECT * FROM users WHERE phone='13800138000' FOR UPDATE;   -- 唯一索引，记录存在

-- ✅ Gap Lock：唯一索引等值查询，记录不存在
SELECT * FROM users WHERE id=4 FOR UPDATE;                  -- 主键，id=4不存在

-- ✅ Next-Key Lock：普通索引查询，或范围查询
SELECT * FROM users WHERE age=20 FOR UPDATE;                -- 普通索引
SELECT * FROM users WHERE id > 3 AND id < 10 FOR UPDATE;   -- 范围查询
```

**核心规律：唯一性能精准定位到一行 → Record Lock / Gap Lock；定位不到精确一行 → Next-Key Lock。**

普通索引之所以用 Next-Key Lock，是因为相同值可能有多行，无法精准定位，必须把前后间隙也锁住才能完全防幻读。

### 对比

| | 锁记录本身 | 锁间隙 | 触发条件 |
|--|-----------|--------|---------|
| Record Lock | ✅ | ❌ | 唯一索引，记录存在 |
| Gap Lock | ❌ | ✅ | 唯一索引，记录不存在 |
| Next-Key Lock | ✅ | ✅ | 普通索引查询（默认）|

---

## 六、快照读 vs 当前读 + MVCC

### 背景：读也要加锁吗？

最朴素的做法是读加 S 锁、写加 X 锁，读写互斥。但读操作极其频繁，每次都等写锁释放，并发性能极差。

**InnoDB 的解决方案：读不加锁，读历史版本。** 这就是 MVCC。

---

### MVCC 底层：两个隐藏字段 + undo log 版本链

InnoDB 每行数据有两个关键隐藏字段：

- `trx_id`：最后一次修改这行的事务ID
- `roll_pointer`：指向 undo log 里上一个版本的指针

每次修改一行，不是覆盖旧数据，而是把旧版本存入 undo log，新版本通过 roll_pointer 指向旧版本，形成版本链：

```
当前版本（trx_id=103）
    ↓ roll_pointer
旧版本（trx_id=100）
    ↓ roll_pointer
更旧版本（trx_id=95）
```

---

### ReadView：决定你能看到哪个版本

快照读时，InnoDB 生成一个 **ReadView**，包含四个关键信息：

- `creator_trx_id`：创建这个 ReadView 的事务ID（自己）
- `min_trx_id`：当前活跃事务列表里最小的事务ID
- `max_trx_id`：下一个将要分配的事务ID（当前最大已分配ID + 1）
- `m_ids`：当前所有活跃事务的ID列表（未提交的）

**可见性判断**（拿版本链上每个版本的 trx_id 去比）：

```
版本 trx_id < min_trx_id
→ 在我创建 ReadView 之前已提交 → 可见 ✅

版本 trx_id >= max_trx_id
→ 在我创建 ReadView 之后才开启 → 不可见 ❌

版本 trx_id 在 m_ids 活跃列表里
→ 还没提交 → 不可见 ❌

版本 trx_id 不在 m_ids 里，且在 min 和 max 之间
→ 在我之前已提交 → 可见 ✅
```

沿版本链从新到旧找，**第一个可见的版本**就是读到的数据。

一句话：**已提交 且 在我创建 ReadView 之前提交的版本，才可见。**

**比较的是版本链上每个版本的 trx_id，不是 creator_trx_id：**

```
假设 ReadView：min=80, max=120, m_ids=[80,90,100]

版本链上：
  版本1：trx_id=200  → 200 >= max(120) → 我之后才开启 → 不可见 ❌
  版本2：trx_id=90   → 90 在 m_ids 里  → 还没提交     → 不可见 ❌
  版本3：trx_id=70   → 70 < min(80)    → 我之前已提交 → 可见 ✅ 读这个
```

**creator_trx_id 的作用：** 自己修改的数据自己要能看到。版本的 trx_id == creator_trx_id → 直接可见。

**记录里 trx_id 存的是什么：** 最近一次修改这行数据的事务ID。每次被修改就更新，历史版本存在 undo log 里各自保留当时的 trx_id。

---

### 快照读

普通 `SELECT`，读 MVCC 版本链里的历史版本，**不加任何锁**，读写完全并发。

```sql
SELECT * FROM users WHERE id=1;  -- 快照读，不加锁
```

---

### 当前读

`SELECT...FOR UPDATE`、`UPDATE`、`DELETE`，读最新数据，需要加锁。加什么锁取决于查询条件（同行锁三种算法）：

```sql
SELECT * FROM users WHERE id=1 FOR UPDATE;   -- IX锁 + Record Lock
SELECT * FROM users WHERE id=4 FOR UPDATE;   -- IX锁 + Gap Lock（id=4不存在）
SELECT * FROM users WHERE age=5 FOR UPDATE;  -- IX锁 + Next-Key Lock（普通索引）
```

---

### RC vs RR：ReadView 生成时机的区别

```
RC（读已提交）：每次 SELECT 都重新生成新的 ReadView
→ 能看到其他事务已提交的最新数据
→ 同一事务两次查结果可能不一样（不可重复读）

RR（可重复读）：只在第一次 SELECT 时生成 ReadView，整个事务复用
→ 整个事务期间看到的数据是一致的快照
→ 同一事务两次查结果一定一样（可重复读）
```

**RR 叫"可重复读"的原因：ReadView 只生成一次，读的始终是同一个快照。**

### MVCC 能完全解决幻读吗？

**不能**，只能解决快照读的幻读，当前读的幻读需要 Gap Lock 解决。

```
T1：事务A 普通SELECT查范围 → 快照读，ReadView挡住了B的新行，看不到 ✅
T2：事务B 插入新行并提交
T3：事务A FOR UPDATE查同一范围 → 当前读，绕过MVCC读最新数据，看到了B的新行 ❌ 幻读
```

**原因：当前读（FOR UPDATE / UPDATE / DELETE）绕过 MVCC，直接读磁盘最新数据，ReadView 对它完全无效。**

触发当前读的操作：
```
SELECT ... FOR UPDATE        → 当前读，加 X 锁
SELECT ... LOCK IN SHARE MODE → 当前读，加 S 锁
UPDATE / DELETE / INSERT     → 自动当前读，加 X 锁
普通 SELECT                  → 快照读，不加锁
```

### 两套防幻读机制

```
快照读  →  MVCC（读历史版本，天然看不到别人新插入的数据）
当前读  →  间隙锁 Gap Lock / Next-Key Lock（物理上阻止别人插入新数据）
```

两套机制解决同一个问题，手段完全不同：
- MVCC 是**读的时候绕开新数据**
- 间隙锁是**直接把新数据挡在门外**

### 总结

| | 快照读 | 当前读 |
|--|--------|--------|
| 触发方式 | 普通 SELECT | FOR UPDATE / UPDATE / DELETE |
| 读取版本 | MVCC 历史版本 | 最新数据 |
| 是否加锁 | 不加锁 | 加锁（Record/Gap/Next-Key） |
| 防幻读方式 | MVCC + ReadView | Gap Lock / Next-Key Lock |

---

## 七、死锁

### 背景：行锁引入的新问题

表锁时代，单张表一次性锁住，不容易形成循环等待。行锁粒度更细，一个事务可以同时持有多行锁并等待其他行，更容易形成循环等待。

**注意：表锁也会死锁**，跨表操作时加锁顺序不一致同样会产生：

```
事务A：持有表a的X锁，等待表b的X锁
事务B：持有表b的X锁，等待表a的X锁 → 死锁
```

### 死锁的四个必要条件

1. **互斥**：X 锁只能一个事务持有
2. **不可剥夺**：锁只能持有者主动释放，不能强夺
3. **持有并等待**：持有锁的同时还在等其他锁
4. **循环等待**：A 等 B，B 等 A，形成环

前三个是锁的固有特性，无法消除。**能破坏的只有循环等待。**

### InnoDB 如何处理死锁

InnoDB 自动检测（等待图算法，检测有没有环），检测到后：
- 回滚代价较小的事务（undo log 少的那个）
- 被回滚事务收到错误：`ERROR 1213: Deadlock found`
- 另一个事务自动继续执行

应用层需要处理这个异常并重试：

```java
try {
    orderMapper.updateById(id);
} catch (DeadlockLoserDataAccessException e) {
    orderMapper.updateById(id); // 重试一次
}
```

### 主动检测死锁

```sql
-- 查看最近一次死锁详情（持有什么锁、等什么锁）
SHOW ENGINE INNODB STATUS;

-- 查当前活跃事务
SELECT * FROM information_schema.INNODB_TRX;

-- 查当前锁等待关系（谁在等谁，死锁前发现苗头）
SELECT * FROM information_schema.INNODB_LOCK_WAITS;
```

兜底参数：`innodb_lock_wait_timeout`，默认50秒，生产建议设为3~5秒，等锁超时自动回滚。

### 如何避免死锁

**最根本：固定加锁顺序**，所有事务按同一顺序加锁，不会形成循环等待。无论行锁还是表锁，跨多个资源操作时都适用。

```java
List<Long> ids = Arrays.asList(id1, id2);
Collections.sort(ids); // 统一升序
for (Long id : ids) {
    orderMapper.selectForUpdate(id);
}
```

其他方法：
- 缩短事务，减少持锁时间（远程调用移到事务外）
- 走索引，避免锁范围扩大
- 大事务拆小事务

### 总结

| 问题 | 答案 |
|------|------|
| 死锁怎么产生 | 多个事务循环等待对方的锁（行锁、表锁都可能） |
| MySQL 怎么处理 | 自动检测，回滚代价小的事务 |
| 怎么避免 | 固定加锁顺序（最根本）、缩短事务、走索引 |

---

## 八、乐观锁 vs 悲观锁

### 背景：这是思想，不是具体的锁

S锁、X锁、Gap Lock 是数据库里真实存在的锁。乐观锁和悲观锁是**处理并发冲突的两种思想**，不是具体锁类型。

### 悲观锁

**思想：默认冲突一定会发生，先加锁再操作。**

用数据库的锁机制保证串行，靠"我拿着锁你进不来"。

```sql
BEGIN;
SELECT stock FROM products WHERE id=1 FOR UPDATE;  -- 先锁住
UPDATE products SET stock=stock-1 WHERE id=1;
COMMIT;
```

**适合：** 写多、冲突频繁（秒杀扣库存）。**缺点：** 高并发下大家排队等，吞吐量低。

### 乐观锁

**思想：默认冲突不会发生，不加锁，提交时验证有没有人改过。**

用业务逻辑保证一致性，靠"提交时检查数据有没有被动过"。表里加 `version` 字段实现：

```sql
-- 查询时带出 version（不加锁）
SELECT stock, version FROM products WHERE id=1;
-- 返回 stock=10, version=5

-- 更新时检查 version 是否一致
UPDATE products SET stock=stock-1, version=version+1
WHERE id=1 AND version=5;
-- 影响行数=0 说明被别人改过，业务层重试
```

**乐观锁是业务层实现的，数据库本身不知道有这回事**，`WHERE version=5` 在数据库眼里只是一个普通的条件。

**适合：** 读多写少、冲突概率低（编辑资料、点赞）。**缺点：** 冲突多时大量重试，反而比悲观锁更慢。

### 如何选择

就看一个问题：**冲突概率高不高？**

- 高 → 悲观锁，直接串行，省去大量重试开销
- 低 → 乐观锁，大家并发跑，偶尔冲突再重试，整体吞吐量更高

### 对比

| | 悲观锁 | 乐观锁 |
|--|--------|--------|
| 加锁时机 | 操作前 | 提交时校验 |
| 实现层 | 数据库锁（FOR UPDATE） | 业务层（version字段） |
| 适合场景 | 写多、冲突频繁 | 读多、冲突少 |
| 高并发性能 | 差（排队等锁） | 好（无锁） |
| 冲突多时 | 稳定 | 大量重试，反而更差 |

---

## 九、MDL 锁（元数据锁）

### 背景：DDL 和 DML 并发会出什么问题？

正常业务在读写数据（DML：SELECT/INSERT/UPDATE/DELETE），DBA 同时在改表结构（DDL：ALTER/CREATE/DROP）。没有保护的话结构对不上，数据错乱。

MDL 锁保证：**改表结构时不能有人读写数据，有人读写数据时不能改表结构。**

### MDL 锁是什么

MySQL 5.5+ 引入，全称 Metadata Lock，保护表结构，全自动，不能手动操作。

- **读 MDL 锁**：DML（增删改查）自动加，多个事务可以同时持有
- **写 MDL 锁**：DDL（ALTER等）自动加，独占，其他一切操作全部阻塞

### 经典生产事故：加字段把整张表搞瘫了

```
1. 事务A：BEGIN; SELECT * FROM orders;
   → 拿到读 MDL 锁，事务未提交，锁一直持有

2. DBA：ALTER TABLE orders ADD COLUMN remark VARCHAR(200);
   → 申请写 MDL 锁，有读 MDL 锁，进入等待队列

3. 事务C：SELECT * FROM orders;
   → 申请读 MDL 锁，前面有写 MDL 锁在排队，也被阻塞！

4. 后续所有请求全部阻塞，整张表不可用
```

**根本原因：** MDL 等待队列中写锁会阻塞后续所有读锁（防止写锁被读锁一直插队饿死）。

**一个未提交的事务 + 一个 DDL = 整张表瘫痪。**

### 正确的加字段姿势

```sql
-- 1. 先确认没有未提交的长事务
SELECT * FROM information_schema.INNODB_TRX;

-- 2. 设置超时，等不到就放弃，不阻塞业务
SET lock_wait_timeout = 5;
ALTER TABLE orders ADD COLUMN remark VARCHAR(200);

-- 3. 大表用工具在线改，不锁表
-- pt-online-schema-change 或 gh-ost
```

### 和行锁的区别

| | 行锁 | MDL 锁 |
|--|------|--------|
| 保护对象 | 行数据 | 表结构 |
| 手动操作 | 可以（FOR UPDATE） | 不能，全自动 |
| 触发时机 | DML 操作行时 | 任何访问表时 |

### 一句话总结

MDL 锁是表结构的守护者。**生产最大的坑：未提交的长事务 + DDL = 整张表阻塞。** 加字段前先检查长事务，设置超时时间。

---

## 十、实战：秒杀防超卖方案

### 问题背景

库存100件，10000个请求同时进来。核心矛盾：
- "查库存 → 判断 → 扣减"三步不是原子操作，并发下会超卖
- 分布式多节点部署，JVM 锁失效
- 性能不能太差，高并发下锁竞争会成为瓶颈

### 标准分层方案

```
Sentinel 限流（闸门，每秒只放N个请求）
  → Redis DECR 原子扣减（主力，挡99%流量）
  → MQ 异步削峰（解耦，消费者慢慢写库）
  → DB WHERE stock>0 兜底（防超卖最后一道）
  → 支付超时回滚（还库存，别忘了！）
```

**第一层：Redis DECR 原子扣减**

```java
Long stock = redisTemplate.opsForValue().decrement("stock:product:1");
if (stock < 0) {
    redisTemplate.opsForValue().increment("stock:product:1"); // 还回去
    return "库存不足";
}
messageQueue.send(new OrderMessage(userId, productId)); // 发MQ异步写库
```

**第二层：数据库兜底（不需要 FOR UPDATE）**

```sql
-- WHERE stock>0 天然防超卖，比 FOR UPDATE 性能更好
UPDATE products SET stock=stock-1 WHERE id=1 AND stock>0;
-- 影响行数=0 说明库存不足，回滚
```

**支付超时回滚（容易遗忘的细节）**

```
用户抢到 → 15分钟未付款 → 订单关闭
→ Redis INCR +1，DB 库存 +1
```

### 为什么秒杀不用锁，而用 DECR

锁解决的是"读-判断-写"三步不原子的问题。DECR 把这三步合并成一个原子操作，用 Redis 单线程串行执行替代了锁，效果一样但性能更好。

**什么时候用锁，什么时候用原子操作：**

| 场景 | 方案 |
|------|------|
| 先查再改，逻辑复杂 | 悲观锁 FOR UPDATE |
| 冲突少，偶尔重试 | 乐观锁 version |
| 分布式环境，多步操作 | Redis 分布式锁 |
| 纯数字增减 | Redis 原子操作，不需要锁 |

**一句话：能用原子操作解决的不需要锁，需要保护多步操作的才需要锁。**

---

## 十一、分布式事务：转账场景

### 锁、事务、分布式事务的本质区别

```
锁        →  解决并发问题，防止多个请求同时操作同一资源导致数据错误（超扣、重名）
事务      →  解决原子性问题，保证多步操作要么全成功要么全失败（转了没收到）
分布式事务 →  解决跨库/跨服务的原子性问题，本地事务管不了两个库
```

### 单机转账：数据库事务 + 固定加锁顺序

```java
@Transactional
public void transfer(String fromId, String toId, BigDecimal amount) {
    // 按 id 升序加锁，防死锁（A转B 和 B转A 都按同一顺序）
    List<String> ids = Arrays.asList(fromId, toId);
    Collections.sort(ids);

    Account first  = accountMapper.selectForUpdate(ids.get(0));
    Account second = accountMapper.selectForUpdate(ids.get(1));

    Account from = first.getId().equals(fromId) ? first : second;
    Account to   = first.getId().equals(fromId) ? second : first;

    if (from.getBalance().compareTo(amount) < 0) {
        throw new BusinessException("余额不足");
    }
    accountMapper.decrease(from.getId(), amount);
    accountMapper.increase(to.getId(), amount);
}
```

加锁防超扣，事务防"扣了没收到"，固定顺序防死锁。

### 分布式转账：为什么本地事务失效？

分库分表后，A和B可能在不同的库/服务上：

```
用户 id % 2 == 0  →  账户服务A（数据库1）
用户 id % 2 == 1  →  账户服务B（数据库2）
```

同时操作两个库，本地事务只管自己的库，跨库无法保证原子性。

### 方案一：TCC（强一致，适合金融）

**本质：把跨库大事务拆成多个本地事务 + 协调者统一指挥。**

每个服务只操作自己的库（本地事务），协调者通过 RPC 远程调用串联：

```
协调者（机器C）
  ↓ RPC          ↓ RPC
账户服务A        账户服务B
  ↓ 本地事务       ↓ 本地事务
数据库1          数据库2
```

三个阶段：

```
Try 阶段：
  A服务：冻结100元（balance-100, frozen+100）← 本地事务
  B服务：预增100元（frozen+100）            ← 本地事务

Confirm 阶段（两边Try都成功）：
  A服务：清除冻结（frozen-100）             ← 本地事务
  B服务：冻结转余额（balance+100, frozen-100）← 本地事务

Cancel 阶段（任意一步失败）：
  A服务：解冻（balance+100, frozen-100）    ← 本地事务
  B服务：取消预增（frozen-100）             ← 本地事务
```

**账户表需要加 frozen_amount 字段：**

```sql
UPDATE accounts SET balance=balance-100, frozen_amount=frozen_amount+100
WHERE id='A' AND balance>=100;  -- Try：冻结
```

优点：强一致。缺点：业务侵入性强，要写三个方法，性能较差。

### 方案二：本地消息表（最终一致，适合大多数业务）

**本质：把"跨库两步操作"拆成"本地一步 + 异步补偿"，破坏非原子性条件。**

```
第一步（同一个库，本地事务，原子）：
  A扣款 + 写消息表

第二步（异步，可重试）：
  定时任务扫描消息表 → 发MQ → B服务加款 → 标记消息完成
```

失败了无限重试，最终一定成功。代价是短暂不一致。

### 如何选择

```
有资损风险，必须强一致    →  TCC（银行核心账务）
允许短暂不一致，可以重试  →  本地消息表（大多数业务）
```

支付宝"实时到账"底层也是最终一致，只是延迟极短用户感知不到。

### 什么时候需要分布式锁

满足两个条件才需要：
1. 多台机器部署（分布式环境）
2. 操作共享资源，且不是原子操作

```
判断流程：
并发会发生吗？→ 不会 → 不需要锁
    ↓ 会
单机还是多机？→ 单机 → JVM锁够了
    ↓ 多机
能用原子操作解决？→ 能 → Redis原子操作（如DECR）
    ↓ 不能
需要分布式锁
```
