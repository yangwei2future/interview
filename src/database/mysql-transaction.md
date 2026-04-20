# MySQL 事务 & MVCC

## 知识地图

```
1. 事务基础        → ACID 四特性
2. 并发问题        → 脏读、不可重复读、幻读
3. 隔离级别        → 四个级别、MySQL 默认
4. MVCC 原理       → 版本链、ReadView、可见性规则
5. 幻读的边界      → 快照读 vs 当前读
```

---

## 一、事务基础

事务是一组 SQL 操作，要么全成功，要么全失败，不允许中间状态。

```sql
-- 转账：两条 SQL 必须同时成功或同时失败
UPDATE account SET balance = balance - 100 WHERE user = '张三';
UPDATE account SET balance = balance + 100 WHERE user = '李四';
```

---

### ACID 四特性

| 缩写 | 英文 | 含义 | MySQL 实现 |
|------|------|------|-----------|
| A | Atomicity（原子性） | 要么全做，要么全不做 | undo log |
| C | Consistency（一致性） | 事务前后数据满足业务约束 | 由 AID 共同保证 |
| I | Isolation（隔离性） | 多事务并发互不干扰 | 锁 + MVCC |
| D | Durability（持久性） | 提交后永久保存，宕机不丢 | redo log |

> 一致性是**目的**，AID 是**手段**。
>
> - undo log = 后悔药，记录怎么撤回，保证原子性
> - redo log = 备忘录，记录做了什么，保证持久性

---

## 二、并发问题

多事务并发执行，不加隔离会出现三种问题：

### 脏读（Dirty Read）
读到了另一个事务**未提交**的数据。对方回滚后，读到的是根本不存在的数据。

```
事务A：修改 balance=500，未提交
事务B：读到 balance=500
事务A：回滚，balance 还原为 400
事务B：用 500 做了后续计算 → 错误
```

### 不可重复读（Non-Repeatable Read）
同一事务内，两次读同一行，值不一样（因为另一个事务**提交了修改**）。

```
事务A：第一次读 balance=400
事务B：提交，balance 改成 500
事务A：第二次读 balance=500 → 前后不一致
```

> **名字理解**："不可重复读"描述的是现象——第一次读到的结果无法被重现（不可重复）。
> 对应的解决方案叫 REPEATABLE READ（可重复读），两者是问题和解法的关系。

### 幻读（Phantom Read）
同一事务内，同一范围查询，前后**行数**不一样（另一个事务 INSERT / DELETE 了数据）。

```
事务A：SELECT COUNT(*) WHERE age > 18 → 10 条
事务B：INSERT 一条 age=20，提交
事务A：SELECT COUNT(*) WHERE age > 18 → 11 条 → 多出一行"幻影"
```

### 三者区别

| 问题 | 对方事务状态 | 本质 |
|------|------------|------|
| 脏读 | 未提交 | 读到中间态数据 |
| 不可重复读 | 已提交 | 同一行值前后不一致（UPDATE） |
| 幻读 | 已提交 | 行数前后不一致（INSERT/DELETE） |

---

## 三、隔离级别

| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---------|------|----------|------|
| READ UNCOMMITTED（读未提交） | ✅ 会 | ✅ 会 | ✅ 会 |
| READ COMMITTED（读已提交） | ❌ | ✅ 会 | ✅ 会 |
| REPEATABLE READ（可重复读） | ❌ | ❌ | ⚠️ 理论上会 |
| SERIALIZABLE（串行化） | ❌ | ❌ | ❌ |

**MySQL InnoDB 默认：REPEATABLE READ（RR）**

Oracle / SQL Server 默认：READ COMMITTED（RC）

各级别实现方式：
- READ UNCOMMITTED：不限制，直接读最新数据
- READ COMMITTED：只读已提交数据，每次 SELECT 生成新 ReadView
- REPEATABLE READ：事务第一次 SELECT 生成 ReadView，后续复用
- SERIALIZABLE：事务完全串行，所有读加锁

---

## 四、MVCC 原理

MVCC（Multi-Version Concurrency Control，多版本并发控制）：同一行数据保存多个历史版本，不同事务读到不同版本，**读不加锁，读写并发，性能高**。

### 4.1 三个核心组件

**① 隐藏字段**

InnoDB 每行数据有两个隐藏列：

```
┌──────────┬──────────┬──────────────────┬─────────────────────┐
│ 业务字段  │ 业务字段  │    trx_id        │      roll_ptr       │
│          │          │ 最后修改该行的     │ 指向 undo log 中的   │
│          │          │ 事务 ID（自增）    │ 上一个历史版本       │
└──────────┴──────────┴──────────────────┴─────────────────────┘
```

**② undo log 版本链**

每次修改一行，旧版本写入 undo log，通过 `roll_ptr` 串成链：

```
当前版本（trx_id=100）
      ↓ roll_ptr
旧版本1（trx_id=60）
      ↓ roll_ptr
旧版本2（trx_id=30）
      ↓ roll_ptr
最原始版本（trx_id=10）
```

**③ ReadView（读视图）**

事务执行快照读时生成，包含四个字段：

```
m_ids          → 当前所有活跃事务 ID 列表（未提交的）
min_trx_id     → m_ids 中最小的事务 ID
max_trx_id     → 下一个将要分配的事务 ID（当前最大 + 1）
creator_trx_id → 创建该 ReadView 的事务自己的 ID
```

---

### 4.2 可见性判断规则

读到一行数据，拿它的 `trx_id` 对照 ReadView 判断：

```
trx_id == creator_trx_id        → 自己改的 → 可见

trx_id < min_trx_id             → ReadView 生成前就提交了 → 可见

trx_id >= max_trx_id            → ReadView 生成后才开始的事务 → 不可见

min_trx_id ≤ trx_id < max_trx_id：
    在 m_ids 里                 → 还未提交 → 不可见
    不在 m_ids 里               → 已提交   → 可见
```

不可见就沿 `roll_ptr` 往旧版本找，直到找到可见版本。

---

### 4.3 RC 和 RR 的本质区别

**ReadView 生成时机不同：**

| 隔离级别 | ReadView 生成时机 | 效果 |
|---------|----------------|------|
| RC | 每次 SELECT 都生成新的 | 每次能看到最新已提交数据 → 不可重复读 |
| RR | 事务第一次 SELECT 时生成，后续复用 | 整个事务看到的数据一致 → 解决不可重复读 |

---

### 4.4 具体示例

```
初始数据：id=1, age=18, trx_id=5

时刻1：事务A 开始（ID=20）
时刻2：事务C 开始（ID=25），把 age 改成 30，提交
时刻3：事务B 开始（ID=30），把 age 改成 50，未提交
时刻4：事务A 第一次 SELECT（RR 级别，生成 ReadView）
时刻5：事务B 提交
时刻6：事务A 第二次 SELECT
```

时刻4 ReadView：
```
m_ids = [30]，min_trx_id = 30，max_trx_id = 31，creator_trx_id = 20
```

时刻4 读取过程：
```
当前行 trx_id=30，在 m_ids 里 → 不可见
↓ roll_ptr → 旧版本 trx_id=25，25 < min_trx_id(30) → 可见 ✅
读到 age=30
```

时刻6 读取过程（复用同一 ReadView）：
```
当前行 trx_id=30，m_ids 里还有 30（不管提没提交）→ 不可见
↓ roll_ptr → trx_id=25 → 可见 ✅
还是读到 age=30
```

**结论：RR 级别下两次都读到 age=30，不可重复读问题解决。**

---

## 五、幻读的边界

### 快照读 vs 当前读

| 操作类型 | 是否走 ReadView | 说明 |
|---------|---------------|------|
| 普通 SELECT | 走（快照读） | 读历史版本，不加锁 |
| SELECT FOR UPDATE | 不走（当前读） | 读最新数据，加锁 |
| UPDATE / DELETE | 不走（当前读） | 读最新数据，加锁 |

### 幻读什么时候出现

**纯快照读：幻读不出现**
```
事务A：SELECT * WHERE age > 18      → 10 条（生成 ReadView）
事务B：INSERT age=20，提交
事务A：SELECT * WHERE age > 18      → 还是 10 条 ✅
```

**混用当前读：幻读出现**
```
事务A：SELECT * WHERE age > 18               → 10 条（生成 ReadView）
事务B：INSERT age=20，提交
事务A：UPDATE user SET name='x' WHERE age>18 → 影响 11 行（当前读，读到了新行）
事务A：SELECT * WHERE age > 18               → 11 条 ❌ 幻读
```

UPDATE 是当前读，把事务B 插入的那行也改了，`trx_id` 变成事务A 自己的，最后 SELECT 时 `creator_trx_id` 匹配，新行可见，幻读出现。

### MySQL 如何解决当前读的幻读

RR 级别下，当前读操作加**间隙锁（Gap Lock）**，锁住查询范围，阻止其他事务在范围内 INSERT，从根源上防止幻读。

```
快照读（SELECT）         → MVCC 解决幻读
当前读（UPDATE 等）      → 间隙锁解决幻读
```

---

## 六、面试高频问答

### Q1：ACID 中原子性和持久性分别靠什么实现？

- 原子性靠 **undo log**：每次修改前记录旧值，事务回滚时按 undo log 还原
- 持久性靠 **redo log**：提交时先写 redo log，宕机重启后重放日志恢复数据

### Q2：脏读、不可重复读、幻读的区别？

- 脏读：读到未提交的数据，对方回滚后数据根本不存在
- 不可重复读：同一事务内同一行值前后不一致，原因是对方提交了 UPDATE
- 幻读：同一事务内同一范围行数前后不一致，原因是对方提交了 INSERT/DELETE

### Q3：MySQL 默认隔离级别是什么？能解决幻读吗？

默认 **REPEATABLE READ**。

- 对于快照读（普通 SELECT）：MVCC 保证读同一个版本，幻读不出现
- 对于当前读（UPDATE / SELECT FOR UPDATE）：间隙锁防止其他事务插入，幻读不出现
- 特殊场景（先快照读再当前读）：当前读穿透 ReadView，幻读可能出现

所以说"基本解决"，不是"完全解决"。

### Q4：RC 和 RR 隔离级别的核心区别？

ReadView 生成时机不同：
- RC：每次 SELECT 生成新 ReadView，能看到最新已提交数据
- RR：第一次 SELECT 生成 ReadView，整个事务复用，保证读到的数据一致
