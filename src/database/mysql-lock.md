# MySQL 锁机制

## 一、为什么需要锁？

并发场景下，多个事务同时操作同一数据会产生问题。

**典型场景：超卖**
> 库存只剩1件，事务A和事务B同时读到库存=1，都认为可以卖，都执行扣减，库存变-1。

锁的作用：**让并发操作串行化，保证数据一致性。**

---

### 锁的分层防护（实际生产）

```
高并发请求
    ↓
Redis 分布式锁（主力，挂了直接熔断返回"系统繁忙"）
    ↓
数据库锁（兜底，FOR UPDATE 保最后一道）
```

**JVM 锁（synchronized）只能解决单进程内多线程问题，分布式多节点部署下无效，基本被 Redis 锁替代。**

---

## 二、锁的分类

### 按粒度分：表锁 vs 行锁

| | 表锁 | 行锁 |
|--|------|------|
| 锁的范围 | 整张表 | 单行 |
| 并发能力 | 低 | 高 |
| 死锁风险 | 无 | 有 |
| 适用引擎 | MyISAM | InnoDB |

**表锁细分：**
- `LOCK TABLES ... READ`（读锁）：当前事务只能读，其他事务可以读，不能写
- `LOCK TABLES ... WRITE`（写锁）：当前事务读写都行，其他事务读写全阻塞

**⚠️ 行锁的致命细节：行锁加在索引上，查询不走索引，行锁退化成表锁！**

```sql
-- name 没有索引，全表扫描，行锁退化成表锁
SELECT * FROM users WHERE name = '张三' FOR UPDATE;

-- id 是主键，走索引，只锁 id=1 这一行
SELECT * FROM users WHERE id = 1 FOR UPDATE;
```


---

### 按模式分：S锁 vs X锁

#### S锁（共享锁 / 读锁）
- 多个事务可以同时加 S 锁，大家一起读
- 有 S 锁时，其他事务不能加 X 锁（写会阻塞）

```sql
SELECT * FROM users WHERE id = 1 LOCK IN SHARE MODE;
```

#### X锁（排他锁 / 写锁）
- 只有一个事务能加 X 锁
- 其他事务加 S 锁和 X 锁都阻塞
- UPDATE / DELETE / INSERT 自动加 X 锁

```sql
SELECT * FROM users WHERE id = 1 FOR UPDATE;
```

**兼容关系口诀：读读兼容，有写必冲突。**

| | 已有S锁 | 已有X锁 |
|--|---------|---------|
| 再加S锁 | ✅ 兼容 | ❌ 冲突 |
| 再加X锁 | ❌ 冲突 | ❌ 冲突 |

---

### 意向锁（InnoDB 自动加，无需手动操作）

**为什么需要：** 事务加表锁前，需要判断表里有没有行锁。没有意向锁就要逐行扫描（1000万行很慢），有了意向锁只需看表级标记，O(1) 搞定。

**本质：** 表级别的标记位，不锁任何数据，只回答一个问题：**这张表里有没有事务在操作某些行？**

- **IS锁（意向共享锁）：** 我打算在某行加 S 锁
- **IX锁（意向排他锁）：** 我打算在某行加 X 锁

```sql
-- 执行这条SQL时，InnoDB 自动：
-- 1. 在 users 表加 IX 锁
-- 2. 在 id=1 这行加 X 锁
SELECT * FROM users WHERE id = 1 FOR UPDATE;
```

意向锁之间互相兼容，只与表锁冲突。

---

## 三、FOR UPDATE 和 LOCK IN SHARE MODE

这两个是**手动显式加锁**的语法，用于"先查再改"的场景。

**普通 SELECT 不加锁（快照读）：**
```sql
SELECT stock FROM products WHERE id=1;
-- 不加任何锁，其他事务可以同时读写
```

**FOR UPDATE（加X锁）：**
```sql
BEGIN;
SELECT stock FROM products WHERE id=1 FOR UPDATE;
-- 锁住这行，其他事务无法操作
-- 锁跟着事务走，事务提交/回滚才释放，不是查完就释放！
UPDATE products SET stock=stock-1 WHERE id=1;
COMMIT; -- 这里才释放锁
```

**正确的防超卖写法（加锁 → 判断 → 操作，三步缺一不可）：**
```java
@Transactional
public void placeOrder(Long productId) {
    Product product = productMapper.selectForUpdate(productId); // 加锁查询
    if (product.getStock() <= 0) {
        throw new BusinessException("库存不足"); // 回滚，锁释放
    }
    productMapper.decreaseStock(productId); // 有库存才扣减
} // 事务提交，锁释放
```

---

## 四、行锁三种算法

> 前提：表数据 id = 1, 3, 5, 10, 20，索引间隙为：(-∞,1) (1,3) (3,5) (5,10) (10,20) (20,+∞)

### Record Lock（记录锁）

锁住索引上的某一条具体记录，其他记录不受影响。

**触发条件：唯一索引（主键）等值查询，且记录存在。**

```sql
SELECT * FROM users WHERE id = 5 FOR UPDATE;
-- 只锁 id=5 这一行，id=3 和 id=10 不受影响
```

---

### Gap Lock（间隙锁）

锁住两条记录之间的间隙，**不锁记录本身**，防止其他事务往间隙里插入数据。

**触发条件：唯一索引等值查询，记录不存在。**

```sql
-- 表里没有 id=4
SELECT * FROM users WHERE id = 4 FOR UPDATE;
-- 锁住 (3, 5) 这个间隙
-- 其他事务无法插入 id=4，但可以修改 id=3 或 id=5
```

**目的：防止幻读（同一事务两次查询结果不一样）。**

---

### Next-Key Lock（临键锁）

= Gap Lock + Record Lock，锁住间隙 + 间隙右边那条记录，**左开右闭**区间。

**InnoDB 在 RR 隔离级别下的默认锁算法。**

**触发条件：普通索引查询。**

```sql
-- age 是普通索引，查 age=5
SELECT * FROM users WHERE age = 5 FOR UPDATE;
-- 锁住 (3, 5] + (5, 10]
-- 防止左边插入 age=4，也防止右边插入 age=6
```

**为什么锁两个区间：** 普通索引可能有多个相同值，需要把前后都锁住才能完全防幻读。

---

### 三种算法对比

| 算法 | 锁的范围 | 触发条件 |
|------|---------|---------|
| Record Lock | 只锁记录本身 | 唯一索引等值，记录存在 |
| Gap Lock | 只锁间隙，不锁记录 | 唯一索引等值，记录不存在 |
| Next-Key Lock | 间隙 + 记录（左开右闭） | 普通索引查询（RR默认） |

**InnoDB 加锁选择规律：**
```
唯一索引 + 记录存在  → Record Lock（范围最小）
唯一索引 + 记录不存在 → Gap Lock
普通索引查询        → Next-Key Lock（范围最大）
范围查询            → 覆盖整个查询区间的 Next-Key Lock
```

**⚠️ 锁的范围由查询条件动态决定，不是固定的。**

---

## 五、快照读 vs 当前读

| | 快照读 | 当前读 |
|--|--------|--------|
| 触发方式 | 普通 SELECT | SELECT...FOR UPDATE / UPDATE / DELETE |
| 读取数据 | MVCC 历史版本 | 最新数据 |
| 是否加锁 | 不加锁 | 加锁 |
| 受行锁影响 | 不受影响 | 受影响，会阻塞 |

```sql
-- 快照读，不受行锁影响，直接返回
SELECT * FROM users WHERE id=1;

-- 当前读，需要加锁，有行锁时阻塞
SELECT * FROM users WHERE id=1 FOR UPDATE;
```

---

## 六、死锁

### 什么是死锁

两个事务互相持有对方需要的锁，都在等待对方释放，永远无法推进。

```
事务A：持有 id=1 的锁，等待 id=2 的锁
事务B：持有 id=2 的锁，等待 id=1 的锁
→ 互相等待，死锁
```

### 死锁产生的四个必要条件

1. **互斥**：资源只能被一个事务持有（X锁本质）
2. **持有并等待**：持有锁的同时还在等其他锁
3. **不可剥夺**：锁不能被强制夺走，只能等持有者主动释放
4. **循环等待**：A等B，B等A，形成环

> 实际上只需关注**循环等待**，其他三个条件是行锁的固有特性，无法消除。

### 经典死锁案例

```sql
-- 事务A
BEGIN;
UPDATE orders SET status=1 WHERE id=1;  -- 锁住 id=1
-- (等待...)
UPDATE orders SET status=1 WHERE id=2;  -- 等待 id=2

-- 事务B（同时执行）
BEGIN;
UPDATE orders SET status=1 WHERE id=2;  -- 锁住 id=2
-- (等待...)
UPDATE orders SET status=1 WHERE id=1;  -- 等待 id=1 → 死锁！
```

### InnoDB 死锁检测与处理

InnoDB **自动检测死锁**（等待图算法，检测环），检测到后：
- **回滚代价较小的事务**（通常是 undo log 较少的那个）
- 被回滚的事务收到错误：`ERROR 1213 (40001): Deadlock found`
- 另一个事务自动继续执行

```java
// 应用层必须处理死锁重试
@Transactional
public void updateOrder(Long id) {
    try {
        orderMapper.updateById(id);
    } catch (DeadlockLoserDataAccessException e) {
        // 死锁被回滚的一方，重试一次
        orderMapper.updateById(id);
    }
}
```

**相关参数：**
- `innodb_lock_wait_timeout`：等锁超时时间，默认50秒，建议生产设为3~5秒
- `innodb_deadlock_detect`：死锁检测开关，默认ON

### 如何避免死锁（面试核心）

**1. 固定加锁顺序（最有效）**
```java
// 多个资源按 id 升序加锁，所有事务都遵守同一顺序
List<Long> ids = Arrays.asList(id1, id2);
Collections.sort(ids); // 升序
for (Long id : ids) {
    orderMapper.selectForUpdate(id);
}
```

**2. 缩短事务，减少持锁时间**
```java
// ❌ 事务内有耗时操作，长时间持锁
@Transactional
public void bad() {
    selectForUpdate(id);
    callRemoteAPI();   // 可能耗时几秒，持锁等待
    update(id);
}

// ✅ 把远程调用移到事务外
public void good() {
    Object result = callRemoteAPI(); // 事务外执行
    doUpdate(result);                // 事务内只做 DB 操作
}
```

**3. 尽量用唯一索引查询，避免锁范围扩大**

**4. 大事务拆小事务，降低并发持锁数量**

---

## 七、乐观锁 vs 悲观锁

这是**思想层面**的分类，不是具体锁类型。

### 悲观锁

**思想：** 默认冲突会发生，先加锁再操作。

**实现：** `SELECT ... FOR UPDATE`

```sql
BEGIN;
SELECT stock FROM products WHERE id=1 FOR UPDATE;  -- 加锁
UPDATE products SET stock=stock-1 WHERE id=1;
COMMIT;
```

**适合：** 写多、冲突频繁、数据强一致要求高的场景（如扣库存）。

**缺点：** 持锁时间长，高并发下性能差。

---

### 乐观锁

**思想：** 默认不会冲突，操作时不加锁，提交时校验是否有人改过。

**实现：版本号（Version）机制**

```sql
-- 表结构加 version 字段
ALTER TABLE products ADD version INT DEFAULT 0;

-- 查询时带出 version（不加锁）
SELECT stock, version FROM products WHERE id=1;
-- 假设返回 stock=10, version=5

-- 更新时检查 version 是否被改过
UPDATE products
SET stock=stock-1, version=version+1
WHERE id=1 AND version=5;  -- version 对上才更新
-- 影响行数=0 说明被其他事务修改过，需重试
```

```java
// 应用层处理
public boolean decreaseStock(Long productId, int version) {
    int rows = productMapper.decreaseWithVersion(productId, version);
    if (rows == 0) {
        throw new OptimisticLockException("并发冲突，请重试");
    }
    return true;
}
```

**MyBatis-Plus 内置乐观锁插件：** 字段加 `@Version` 注解，框架自动处理。

**适合：** 读多写少、冲突概率低的场景（如资料编辑、点赞数）。

**缺点：** 冲突多时大量重试，反而更慢。

---

### 对比

| | 悲观锁 | 乐观锁 |
|--|--------|--------|
| 加锁时机 | 操作前 | 提交时校验 |
| 实现 | FOR UPDATE | version 字段 |
| 适合场景 | 写多、冲突频繁 | 读多、冲突少 |
| 性能 | 高并发下差 | 高并发下好 |
| 数据库压力 | 高（持锁） | 低（无锁） |

---

## 八、MDL 锁（元数据锁）

### 什么是 MDL 锁

MySQL 5.5+ 引入，**保护表结构**，防止 DML（数据操作）和 DDL（结构变更）并发冲突。

- **读 MDL 锁**：普通 SELECT / DML 自动加，多个事务可以同时持有
- **写 MDL 锁**：ALTER TABLE 等 DDL 自动加，独占，其他一切操作都阻塞

### 经典生产事故：加字段导致全表不可用

```
时间线：
1. 事务A：BEGIN; SELECT * FROM orders; （持有读MDL锁，未提交）
2. DBA：ALTER TABLE orders ADD COLUMN remark VARCHAR(200); （申请写MDL锁，被阻塞，等待）
3. 事务C：SELECT * FROM orders; （申请读MDL锁，被写MDL锁的等待队列阻塞！）
4. 事务D、E、F...：全部阻塞
→ 一个未提交的事务 + 一个DDL，导致整张表不可用！
```

**原因：** MDL 锁等待队列中，写锁会阻塞后续所有读锁申请（防止写锁饿死）。

### 正确的加字段姿势

```sql
-- 1. 执行 DDL 前，先确认没有长事务
SELECT * FROM information_schema.INNODB_TRX;  -- 查看当前事务

-- 2. 设置超时，避免长时间阻塞（DDL 等不到就放弃，不影响业务）
SET lock_wait_timeout = 5;
ALTER TABLE orders ADD COLUMN remark VARCHAR(200);

-- 3. 大表用 pt-online-schema-change 或 gh-ost 工具（不锁表）
pt-online-schema-change --alter "ADD COLUMN remark VARCHAR(200)" D=db,t=orders
```

---

## 九、生产实践：减少锁竞争

### 高并发扣库存方案对比

| 方案 | 原理 | 适合场景 |
|------|------|---------|
| `FOR UPDATE` 悲观锁 | 串行化，强一致 | 低并发，强一致要求 |
| 乐观锁（version） | 无锁+重试 | 中等并发，冲突少 |
| Redis 分布式锁 | 分布式串行化 | 高并发，主力方案 |
| 库存分桶 | 拆成N份，减少竞争 | 超高并发秒杀 |

### 减少锁竞争的通用原则

1. **缩短事务**：事务内只做必要的 DB 操作，远程调用、复杂计算移到事务外
2. **按固定顺序加锁**：多资源操作时统一排序，避免死锁
3. **走索引**：避免行锁退化成表锁
4. **小事务替代大事务**：批量操作分批提交，减少每次持锁时间
5. **读写分离**：读请求走从库，减少主库锁竞争

### 面试常问：UPDATE 语句的完整加锁流程

```sql
UPDATE orders SET status=2 WHERE user_id=100;
```

1. 开启事务（隐式）
2. 在 `orders` 表加 **IX 锁**（意向排他锁）
3. 根据 `user_id` 查询：
   - 有索引 → 行锁（Record/Gap/Next-Key Lock）
   - 无索引 → 全表扫描，每行都加 X 锁（等同表锁）
4. 执行更新
5. 事务提交，释放所有锁
