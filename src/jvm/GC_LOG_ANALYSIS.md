# GC 日志分析实战指南

> 基于真实 GC 日志（GCTuningBenchmark cache 模式，256MB 堆）总结的读写指南。

---

## 一、开启 GC 日志（JDK 8/11）

```bash
# JDK 8 及以前
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/tmp/gc.log

# JDK 9+（统一日志系统，推荐）
-Xlog:gc*:file=/tmp/gc.log:time

# 详细模式（包含元空间、引用等信息）
-Xlog:gc*:file=/tmp/gc.log:time:level
```

```bash
# 生产推荐参数（JDK9+）
-Xlog:gc*:file=/var/log/gc.log:time,uptime,pid:filesize=10m,filecount=10
# 解释：最多 10 个日志文件，每个 10MB，循环覆盖
```

---

## 二、读懂三种 GC 日志格式

### 2.1 Serial GC（串行收集器）

```
[2026-05-08T17:34:27.733+0000] GC(0) Pause Young (Allocation Failure)
[2026-05-08T17:34:27.789+0000] GC(0) DefNew: 69910K->8704K(78656K)              ← DefNew = Default New Generation
[2026-05-08T17:34:27.789+0000] GC(0) Tenured: 0K->31862K(174784K)               ← Tenured = 老年代
[2026-05-08T17:34:27.789+0000] GC(0) Metaspace: 16552K(17152K)->16552K(17152K)  ← 元空间（稳定不变）
[2026-05-08T17:34:27.789+0000] GC(0) Pause Young (Allocation Failure) 68M->39M(247M) 56.631ms  ← 汇总行
[2026-05-08T17:34:27.789+0000] GC(0) User=0.00s Sys=0.06s Real=0.05s            ← 时间分解
```

**解读步骤：**
1. `Pause Young (Allocation Failure)` — 原因：Eden 区满了，分配失败触发 Young GC
2. `DefNew: 69910K->8704K(78656K)` — 新生代从 69.9MB 降到 8.7MB（容量 78.6MB），存活对象约 8.7MB
3. `Tenured: 0K->31862K(174784K)` — 老年代从 0 升到 31.8MB（因为存活对象太多，Survivor 放不下，直接晋升）
4. **汇总行**: `68M->39M(247M)` — 堆从 68MB 降到 39MB，总共 247MB
5. **停顿时间**: `56.631ms` — STW 时间
6. **时间分解**: User=线程CPU时间, Sys=系统调用时间, Real=实际墙上时间

> **Serial 特点**：单线程 GC，停顿时间不稳定（56ms ~ 145ms 波动），因为单线程需要完整遍历整个新生代。

---

### 2.2 Parallel GC（吞吐量优先）

```
[2026-05-08T17:34:42.921+0000] GC(0) Pause Young (Allocation Failure)
[2026-05-08T17:34:42.921+0000] GC(0) PSYoungGen: 65315K->10736K(76288K)         ← PS = Parallel Scavenge
[2026-05-08T17:34:42.921+0000] GC(0) ParOldGen: 0K->24752K(175104K)             ← 老年代
[2026-05-08T17:34:42.921+0000] GC(0) Pause Young (Allocation Failure) 63M->34M(245M) 5.927ms
[2026-05-08T17:34:42.921+0000] GC(0) User=0.01s Sys=0.00s Real=0.01s
```

**解读：**
1. `PSYoungGen` — Parallel Scavenge 新生代收集器
2. `ParOldGen` — Parallel Old 老年代收集器
3. `63M->34M(245M) 5.927ms` — 5.9ms 完成，比 Serial 快很多（多线程并行）
4. `User=0.01s Real=0.01s` — User CPU 时间和 Real 接近，说明并行效果好

> **Parallel vs Serial 关键差异**：多线程并行，Young GC 速度快 N 倍（5.9ms vs 56ms），但老年代晋升更多（24MB vs 31MB），因为并行 GC 线程抢 CPU 导致业务线程分配更多临时对象。

---

### 2.3 G1 GC（默认，平衡型）

```
[2026-05-08T17:34:57.967+0000] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
[2026-05-08T17:34:57.967+0000] GC(0) Using 4 workers of 4 for evacuation
[2026-05-08T17:34:57.969+0000] GC(0)   Pre Evacuate Collection Set: 0.0ms      ← 准备阶段
[2026-05-08T17:34:57.969+0000] GC(0)   Evacuation Collection Set: 2.0ms         ← 核心阶段：复制存活对象
[2026-05-08T17:34:57.969+0000] GC(0)   Post Evacuate Collection Set: 0.1ms      ← 收尾阶段
[2026-05-08T17:34:57.969+0000] GC(0) Eden regions: 14->0(95)                    ← Eden 14个region清空，扩容到95个
[2026-05-08T17:34:57.969+0000] GC(0) Survivor regions: 0->2(2)                  ← Survivor 0->2个region
[2026-05-08T17:34:57.969+0000] GC(0) Old regions: 0->2                          ← 老年代晋升了 2个region（2MB）
[2026-05-08T17:34:57.969+0000] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->3M(256M) 2.368ms
[2026-05-08T17:34:57.969+0000] GC(0) User=0.01s Sys=0.00s Real=0.00s
```

**Region 机制解读（G1 最核心的概念）：**
- `Eden regions: 14->0(95)` — GC 前有 14 个 Eden Region（每个 1MB），回收后全部清空，Eden 容量从 14 扩容到 95（为后续分配做准备）
- `Survivor regions: 0->2(2)` — 存活对象复制到 2 个 Survivor Region
- `Old regions: 0->2` — 从 Survivor 晋升了 2 个 Region 到老年代（2MB）
- `14M->3M(256M) 2.368ms` — 堆使用 14MB->3MB，仅 2.37ms 停顿

> **G1 为什么停顿短？** Young GC 只选 Eden Region 和部分 Survivor Region 来回收，其他 Region 不动。Region 数量可控，停顿就可预测。

---

## 三、核心指标解读

### 停顿时间（Pause Time）

| GC 类型 | 实测平均停顿 | 最差情况 |
|---------|------------|---------|
| Serial Young GC | 0.11ms | 145ms |
| Parallel Young GC | 0.45ms | 13ms |
| G1 Young GC | 0.24ms | 8.5ms |

> **注意**：这个测试中分配速率极高（4500万 obj/s），GC 几乎全在新生代。实际 Web 应用停顿会大很多（10-200ms）。

### 关键指标公式

```java
// 吞吐量 = 业务时间 / (业务时间 + GC时间)
// GC 日志中可以直接看到：
GC 时间占比 = (GC总耗时 / 运行总时长) × 100%

// 晋升速率（Promotion Rate）
// 通过 GC 日志中老年代的增长来估算
晋升速率 = (当前GC老年代大小 - 上次GC老年代大小) / 两次GC间隔
```

### 从日志中读晋升速率

看 Serial GC 的例子：
```
GC(0): Tenured: 0K->31862K(174784K)   ← 第一次GC：晋升 31MB
GC(1): Tenured: 31862K->76920K(174784K) ← 第二次GC：又晋升 45MB
GC(2): Tenured: 76920K->85406K(174784K) ← 第三次GC：再晋升 8.5MB
```

晋升速率 = (85406K - 0) / 三次GC间隔 ≈ 很快把老年代撑满 → 说明 Survivor 空间不足，对象提前晋升

**调优方向**：增大 Survivor 空间（增大新生代 `-Xmn`），或增大目标停顿时间让 G1 更充分回收。

---

## 四、实战调优分析流程

### 第一步：看 GC 频率

```bash
# 统计 GC 次数
grep -c "Pause Young" /tmp/gc-g1-cache.log      # Young GC 次数
grep -c "Pause Full\|Full GC" /tmp/gc-g1-cache.log # Full GC 次数

# 看每秒钟 GC 次数
# 取时间段内的 GC 次数 / 时间差
```

**判断标准：**
- Young GC 每几秒一次 → 正常
- Young GC 每秒 > 1 次 → 分配速率过高，检查代码
- Full GC 出现 → 立即排查（通常是老年代满或元空间满）

### 第二步：看停顿时间

```bash
# 提取所有 GC 停顿时间
grep "Pause Young" /tmp/gc-g1-cache.log | grep -oP '\d+\.\d+ms' | sort -n | tail -5
# 输出形如: 1.234ms, 2.567ms, 8.890ms → 看最大值和分布
```

**判断标准：**
- 平均 < 10ms → 很好
- 平均 10-50ms → 可以接受
- 平均 > 50ms → 需要优化（加大堆、换 GC、改代码）

### 第三步：看晋升速率

```bash
# 观察老年代是否持续增长
grep "Old regions:" /tmp/gc-g1-cache.log | tail -10
# 如果 Old regions 持续增加且 GC 后不减少 → 内存泄漏或晋升太快
```

### 第四步：GC 问题定位矩阵

| 现象 | 根因 | 调优手段 |
|------|------|---------|
| 频繁 Young GC | Eden 太小 / 分配速率过高 | 增大 `-Xmn`，或检查代码中有没有大量临时对象 |
| 频繁 Full GC | 老年代满 / 晋升太快 | 增大堆，或增大 Survivor，或检查内存泄漏 |
| GC 停顿超长 | 堆太大 / GC线程不足 | 调 `-XX:MaxGCPauseMillis`，或增大 GC 线程数 |
| CMS 碎片导致 Full GC | 标记-清除碎片 | 换 G1，或开启 `-XX:+UseCMSCompactAtFullCollection` |

---

## 五、调优决策树

```
用户线程停顿 > 100ms？
├── 是 → 需要低延迟
│   ├── 堆 < 4G → G1（默认即可）
│   ├── 4G~16G → G1 + 调参
│   └── >16G    → ZGC（JDK15+）
│
└── 否 → 追求吞吐量
    ├── 堆 < 4G → Parallel GC
    └── 堆 > 4G → G1

启动后 GC 压力大？
├── 代码问题？
│   ├── 循环内大量 new → 优化代码
│   ├── 字符串拼接 → StringBuilder
│   └── 大对象频繁创建 → 对象池/缓存
└── 参数问题？
    ├── 堆太小 → Xms=Xmx=总内存的 70%
    ├── 新生代太小 → Xmn=堆的 1/3 ~ 1/2
    └── G1目标太紧 → MaxGCPauseMillis=200~500
```

---

## 六、面试高频追问

### Q1: 如何从 GC 日志判断是否存在内存泄漏？
**答：** 观察老年代使用量。如果 Full GC 后老年代仍然居高不下（甚至持续增长），说明有对象一直被强引用，无法回收，就是内存泄漏。

```bash
# 对比 Full GC 前后的老年代大小
grep "ParOldGen\|Tenured" gc.log | head -5
# 如果每次 Full GC 后老年代不降 → 内存泄漏
```

### Q2: CMS 的并发标记失败（Concurrent Mode Failure）是什么意思？
**答：** CMS 在并发清理期间，用户线程还在分配对象到老年代。如果老年代在 CMS 完成前就被填满，就会触发 Concurrent Mode Failure，JVM 降级为 Serial Old 做 Full GC（单线程、STW 极长）。

**解决方案：** 提前触发 CMS，调 `-XX:CMSInitiatingOccupancyFraction=75`（默认 92%），或者换 G1。

### Q3: G1 在日志中 Region 大小是多少？怎么影响性能？
**答：** Region 大小 = 堆大小 / 2048（1MB~32MB），如 256MB 堆就是 1MB/region。

Region 越小 → 停顿越短但 GC 次数越多 → RT 稳定但总开销高
Region 越大 → 停顿越长但 GC 次数少 → 吞吐量好但 RT 波动大

### Q4: 生产上 Full GC 一次几秒正常吗？
**答：** 不正常。Young GC 应该 < 100ms，Full GC 应该 < 1s。如果 Full GC 几秒甚至十几秒，说明：
1. 堆太大（>16G）但用旧 GC（Parallel / CMS）→ 换 G1 或 ZGC
2. 老年代有大量存活对象 → 检查内存泄漏
3. GC 线程数不足 → 调 `-XX:ParallelGCThreads`

### Q5: 你的项目中做过 JVM 调优吗？效果如何？
**答（参考）**：我们的数据服务平台 API 日均百万调用，之前观察 GC 日志发现 Young GC 平均 20ms，但偶尔有 200ms+ 的 Full GC。
排查发现是某个定时任务每次执行创建了大对象（MB 级数组），直接进了老年代，同时老年代缓存淘汰不及时导致碎片化。
**优化方案：**
1. 将大对象拆分为小对象分批处理
2. 给缓存设置 TTL 和最大容量限制
3. 增大 Survivor 空间，减少晋升速率
4. Full GC 频率从每 2 小时 1 次降到 0，Young GC 稳定在 10-15ms
