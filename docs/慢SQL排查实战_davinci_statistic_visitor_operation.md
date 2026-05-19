# 线上慢 SQL 排查实战

## 背景

线上监控发现一条慢 SQL，扫描行数接近 2000 万，需要定位问题并优化。

## 慢 SQL 原文

```sql
SELECT
    name,
    COUNT(id) AS COUNT(id)
FROM
    (
        SELECT
            davo.*,
            d.id dashboard_id,
            d.name
        FROM
            davinci_statistic_visitor_operation davo
            LEFT JOIN dashboard d ON davo.viz_id = d.id
        WHERE
            d.id IS NOT NULL
            AND davo.create_time > DATE_FORMAT(NOW(), '%Y-%m-%d')
    ) t
GROUP BY
    name
ORDER BY
    COUNT(id) DESC
```

## 排查步骤

### 1. 确认 SQL 来源

- 搜索项目代码确认，SQL 不在当前应用代码中
- `davinci_statistic_visitor_operation` 表在代码中只用于 INSERT（数据采集），没有 SELECT 查询
- 该 SQL 来自外部报表工具或手动查询

### 2. 评估数据量

```sql
SELECT COUNT(id) FROM dashboard;
-- 结果：4,258 行
```

- `davinci_statistic_visitor_operation` 表：约 **2000 万行**
- LEFT JOIN 以左表为驱动表，扫描量完全由左表决定
- dashboard 只有 4,258 行，JOIN 开销可忽略

### 3. EXPLAIN 分析（优化前）

```
id  select_type  table       type    possible_keys  key    key_len  ref                       rows       Extra
1   PRIMARY      <derived2>  ALL     null           null   null     null                      19562681   Using temporary; Using filesort
2   DERIVED      davo        ALL     null           null   null     null                      19562681   Using where
2   DERIVED      d           eq_ref  PRIMARY        PRIMARY 8        dip_davinci.davo.viz_id  1          null
```

关键发现：

- **davo 行 type=ALL**：全表扫描，1956 万行
- **possible_keys=null, key=null**：没有任何可用索引
- **Using where**：扫描后逐行过滤 WHERE 条件，没有索引帮忙
- **Using temporary**：GROUP BY 需要创建临时表
- **Using filesort**：ORDER BY 需要额外排序

### 4. 根因分析

```
全表扫描 davinci_statistic_visitor_operation（1956万行）
    ↓ 逐行过滤 create_time > DATE_FORMAT(NOW(), ...)（无索引，逐行判断）
    ↓ 逐行 JOIN dashboard（eq_ref，主键匹配，OK）
    ↓ 子查询结果 1956万行
    ↓ GROUP BY name → 创建临时表（Using temporary）
    ↓ ORDER BY count → 文件排序（Using filesort）
```

**根因：`create_time` 列没有索引，导致全表扫描。**

### 5. 优化方案

创建联合索引 `(create_time, viz_id)`：

```sql
ALTER TABLE davinci_statistic_visitor_operation
ADD INDEX idx_create_time_viz_id (create_time, viz_id);
```

选择联合索引而非单列索引的原因：

| 索引 | 效果 |
|------|------|
| `(create_time)` 单列 | 索引定位后仍需回表取 viz_id 再 JOIN |
| `(create_time, viz_id)` 联合 | 索引中直接包含 viz_id，**省去回表**（ICP） |

> **ICP（Index Condition Pushdown）**：范围条件后，`viz_id` 不能参与索引查找路径（key_len 不体现），但可通过 ICP 在存储引擎层直接过滤，避免回表。

### 6. EXPLAIN 验证（优化后）

```
id  select_type  table       type   possible_keys            key                    key_len  ref                       rows    Extra
1   PRIMARY      <derived2>  ALL    null                     null                   null     null                      10816   Using temporary; Using filesort
2   DERIVED      davo        range  idx_create_time_viz_id   idx_create_time_viz_id  5       null                      10816   Using index condition
2   DERIVED      d           eq_ref PRIMARY                  PRIMARY                 8       dip_davinci.davo.viz_id  1       null
```

优化效果：

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| davo 扫描行数 | 19,562,681 | 10,816 | **降低 99.94%** |
| davo type | ALL | range | 全表扫 → 索引范围扫 |
| Extra | Using where | Using index condition | ICP 过滤 |

### 7. 线上执行注意事项

- **Online DDL**：MySQL 5.6+ 支持，ADD INDEX 不会锁表
- **微秒级 metadata lock**：操作开始和结束时各一次，基本无感
- **IO/CPU 开销**：2000 万行建索引约几分钟到十几分钟，建议**业务低峰期执行**
- 主库建完索引后，从库需等主从同步完成

## EXPLAIN 关键字段速查

### select_type

| 值 | 含义 |
|----|------|
| SIMPLE | 简单查询，无子查询和 UNION |
| PRIMARY | 最外层主查询 |
| DERIVED | FROM 子句中的子查询，结果物化到临时表 |
| SUBQUERY | SELECT/WHERE 中的子查询 |
| UNION | UNION 的第二个及之后 SELECT |

### type（访问类型，从差到好）

| 值 | 含义 | 扫描量 |
|----|------|--------|
| ALL | 全表扫描（扫主键 B+Tree，含完整行数据，页多 IO 大） | 全表 |
| index | 全索引扫描（扫二级索引 B+Tree，只含索引列 + 主键，页少 IO 小） | 全索引 |
| range | 索引范围扫描 | 部分 |
| ref | 索引精确匹配 | 少量 |
| eq_ref | JOIN 时用主键/唯一键匹配 | 1 行 |
| const | 主键或唯一键常量匹配 | 1 行 |

### Extra

| 值 | 含义 |
|----|------|
| Using where | 存储引擎返回行后，Server 层逐行判断 WHERE 条件 |
| Using index condition | ICP：WHERE 条件推到了存储引擎层，在索引中过滤，减少回表 |
| Using index | 覆盖索引：SELECT 的列全在索引中，无需回表 |
| Using temporary | 需要创建临时表（常见于 GROUP BY、DISTINCT） |
| Using filesort | 需要额外排序（常见于 ORDER BY 没有走索引排序时） |

### key_len

表示 MySQL **实际使用了联合索引的前多少个字节**。

- 范围条件会**中断**后续列在索引查找路径中的使用
- `key_len = 5`：只用到了 `create_time`（TIMESTAMP 4 字节 + NULL 标记 1 字节）
- `key_len = 14`：两列都用上（5 + BIGINT 8 字节 + NULL 标记 1 字节 = 14）

## 核心经验总结

1. **定位瓶颈**：EXPLAIN 看哪个表 type=ALL 且 rows 最大，那就是罪魁祸首
2. **联合索引优于单列索引**：多出来的列即使不参与索引查找，也能通过 ICP 减少回表
3. **范围条件中断索引**：联合索引中，范围条件后面的列不能参与索引查找，但可参与 ICP
4. **2000 万行全扫 → 建索引 → 1 万行 range 扫描**：质的飞跃
5. **大表建索引选低峰期**：Online DDL 不锁表，但消耗 IO/CPU 资源
