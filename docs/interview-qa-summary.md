# 面试知识总结

> 来自对话中的 SQL 优化、Spring 核心原理等知识梳理。

---

## 一、SQL 优化

### 1.1 字符集不一致导致索引失效

**为什么 charset 不一样会索引失效？**

MySQL 必须把其中一个字段的字符集转成另一个才能比较大小。转换函数套在谁身上，谁的索引就废了。

```sql
-- t_order: order_no utf8mb4，t_order_detail: order_no utf8mb3
-- JOIN 时 MySQL 把 utf8mb3 转成 utf8mb4
-- 等效于：CONVERT(d.order_no USING utf8mb4) = o.order_no
-- 索引列被套了函数 → 索引失效
```

EXPLAIN 结果：驱动表 type=ALL, key=NULL（全表扫描）。

**为什么 collation 不一样直接报错？**

```sql
-- utf8mb4_general_ci 联 utf8mb4_unicode_ci
ERROR 1267: Illegal mix of collations ...
```

collation 是比较规则（大小写敏不敏感、字符怎么排序），MySQL 没法替你决定用谁的规则，直接报错。而 charset 是编码转换，有明确的转换路径（utf8→utf8mb4 是扩展编码空间），所以不报错只是索引废了。

### 1.2 关联查询的驱动表与被驱动表

EXPLAIN 输出第一行是驱动表，后面的是被驱动表。

```
驱动表：外层循环，只扫一次，索引不重要
被驱动表：被探 N 次（N=驱动表行数），必须有索引
```

看 `ref` 列：驱动表的 ref=NULL，被驱动表的 ref 指向驱动表的字段。

MySQL 倾向选小表做驱动表（减少被驱动表的探测次数）。

> **口诀：驱动表看数据量，被驱动表看索引。**

### 1.3 ALL vs index

| | ALL | index |
|---|---|---|
| 扫什么 | 聚簇索引（数据文件） | 辅助索引（索引文件） |
| 叶子存什么 | 全部字段 | 只存索引列 + 主键 |
| 磁盘 I/O | 多 | 少（索引文件更瘦） |

`SELECT *` 需要全部列，辅助索引里没有，只能 ALL。`SELECT order_no` 索引覆盖，走 index。

性能排序：`ALL < index < range < ref < eq_ref < const < system`

### 1.4 索引优先级

**优先级：WHERE > JOIN > GROUP BY > ORDER BY**

按 SQL 执行顺序来：FROM → ON → JOIN → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT

- WHERE 最先过滤，削减数据量最狠
- JOIN 在 WHERE 之后，数据已经缩了一圈
- GROUP BY 操作的是剩余数据，没索引要建临时表
- ORDER BY 在最后，只对最终结果集排序

### 1.5 大数量优化路线（成本低到高）

1. **SQL 层面**：加索引、避免索引失效、覆盖索引、limit 延迟关联
2. **表结构**：分区表、冷热分离/归档
3. **架构**：读写分离、Redis 缓存、分库分表

---

## 二、JVM 监控

### 2.1 面试如何说 JVM 调参

没实际调过 JVM 时，可以这样坦白：

> "线上用 Arthas dashboard 看 GC 次数和耗时，FGC 基本不增长，Young GC 频率稳定，没触发过调参需求。"

Arthas dashboard 里能看到：
- `gc.ps_scavenge.count` / `gc.ps_scavenge.time` — Young GC
- `gc.ps_marksweep.count` / `gc.ps_marksweep.time` — Full GC

能说出具体的统计项名字，面试官才会觉得你真用过。

---

## 三、Spring Bean 生命周期

```
1. 实例化（new）              ← 反射调构造函数
2. 三级缓存暴露工厂            ← 只存函数引用，不执行
3. 属性填充（DI）             ← 循环依赖发生在这里
4. Aware 回调
5. BeanPostProcessor.before
6. @PostConstruct（初始化）
7. BeanPostProcessor.after    ← AOP 代理在这里生成
8. 放入一级缓存
9. @PreDestroy（销毁）
```

### 3.1 BeanPostProcessor

两个钩子方法，在初始化前后执行：

```java
public interface BeanPostProcessor {
    Object postProcessBeforeInitialization(Object bean, String beanName); // 初始化前
    Object postProcessAfterInitialization(Object bean, String beanName);  // 初始化后，AOP 在这里
}
```

Spring 内置的关键实现：
- `AutowiredAnnotationBeanPostProcessor` — 处理 @Autowired、@Value
- `CommonAnnotationBeanPostProcessor` — 处理 @PostConstruct、@PreDestroy、@Resource
- `AnnotationAwareAspectJAutoProxyCreator` — AOP 代理生成

---

## 四、循环依赖与三级缓存

### 4.1 三个缓存职责

| 缓存 | 存什么 | 职责 |
|---|---|---|
| 一级（singletonObjects） | 完全初始化好的 bean | 最终成品 |
| 二级（earlySingletonObjects） | 早期引用 | 缓存工厂结果，保证多次引用拿到同一个 |
| 三级（singletonFactories） | 工厂 Lambda | 延迟执行，懒加载 |

### 4.2 循环依赖完整流程

```
1. new A → 原始A → 工厂放三级缓存
2. A做DI → 需要B
3. new B → 原始B → 工厂放三级缓存
4. B做DI → 需要A
5. 调A的工厂 → 判断要不要AOP → 返回早期A → 放二级缓存（删三级）
6. B拿到早期A → B完成DI → B初始化 → B放一级缓存
7. A拿到完整B → A完成DI → A初始化 → A放一级缓存
```

**B 先进一级缓存，A 后进。**

### 4.3 为什么需要三级，二级不够？

如果 new 时就决定代理放二级缓存：100% bean 都要遍历 Advisor 做切点匹配。但 99% 的 bean 没有循环依赖。

三级缓存存一个函数引用（几字节，零开销），只有循环依赖发生时工厂才被真正调用。

**核心结论：三级缓存的本质是懒加载策略，省开销。**

### 4.4 无循环依赖时二级缓存不动

正常路径：
```
new A → 注册三级缓存 → DI → init → postProcessAfterInitialization(套代理)
→ 放一级缓存 → 三级被清掉
```
二级缓存从头到尾没碰过，三级里的工厂也没人调。

### 4.5 getEarlyBeanReference 和 postProcessAfterInitialization 的关系

两者底层调的是同一个方法 `wrapIfNecessary()`：

- 正常路径：`postProcessAfterInitialization → wrapIfNecessary → 代理`
- 循环依赖路径：`getEarlyBeanReference → wrapIfNecessary → 代理`

判断要不要代理，看的是类的切点匹配，类加载时就知道。但代理不能套在空壳子上（妨碍后续 DI），正常路径最后才套。

A 如果已经被 `getEarlyBeanReference` 代理过，`postProcessAfterInitialization` 不会再包一层。

---

## 五、事务

### 5.1 事务为什么绑在线程上

```
数据库事务 → 长在 Connection 上
Connection → 不是线程安全的
Spring     → ThreadLocal 存 Connection
           → 同线程共用同一 Connection = 同一事务
```

链路：

```java
Connection conn = dataSource.getConnection();
conn.setAutoCommit(false);   // 开事务
bindResource(conn);           // ThreadLocal 绑定
// 业务代码拿的是同一个 ThreadLocal 里的 conn
conn.commit();                // 提交
unbindResource();             // 解绑
```

### 5.2 线程池会断开事务

```java
@Transactional
public void A() {
    userMapper.insert(user);              // T1 的事务
    threadPool.submit(() -> {
        B();                              // 新线程 T2，事务上下文断开了
        throw new RuntimeException();      // 异常只在 T2，T1 不知道
    });
}
```

**换线程 = 换 ThreadLocal = 新 Connection = 新事务。A 不知道 B 炸了，A 不会回滚。**

如果 A 必须感知 B 的结果：要么不用线程池，要么 `future.get()` 拿到异常后 A 自己抛。

---

*（2026-05-17 整理）*
