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
