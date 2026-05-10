# JVM 调优实践

## 知识地图

```
1. 常用 JVM 参数      → 堆、元空间、GC、dump 参数
2. 线上问题排查工具     → jstat、jmap、jstack、Arthas、MAT
3. CPU 100% 排查步骤   → Arthas / 原始命令
4. 内存泄漏排查步骤     → 监控 → dump → 分析 → 定位
5. OOM 八种类型        → 堆、元空间、线程、直接内存等 8 种
6. GC 调优            → GC 选型、G1 参数、日志分析
7. 日常监控指标        → 告警阈值、趋势监控
8. Arthas 在线诊断     → 常用命令速查
9. 线程泄漏排查 SOP    → 标准化排查流程
```

---

## 一、常用 JVM 参数

### 生产必配

```bash
# 1. 堆大小（Xms = Xmx，避免动态扩缩的性能损耗）
-Xms4g -Xmx4g

# 2. 新生代大小
-Xmn2g

# 3. 元空间（必须设上限，默认无限制会撑爆容器）
-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=256m

# 4. OOM 自动 dump
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/data/dump/  # 必须挂持久化卷！pod 重启后 dump 文件不会丢

# 5. GC 日志（排查 GC 问题的关键）
-Xlog:gc*:file=/data/logs/gc.log:time,tags,level

# 6. OOM 自动执行脚本（可选，留现场）
-XX:OnOutOfMemoryError="/app/scripts/oom-handler.sh %p"

# 7. 线程栈大小（连接池多的场景可以适当减小）
-Xss512k     # 默认 1m，线程多时减小能省内存
# 注意：一个线程栈 1m，2w 线程就是 20g 的虚拟内存，容器很容易被打爆

# 8. 防止容器 OOMKilled
-XX:+UseContainerSupport          # 识别容器内存限制
-XX:MaxRAMPercentage=75.0         # 堆占容器内存的 75%，留够堆外
```

> **为什么 Xms 和 Xmx 要设成一样？**
> 不一样的话 JVM 会动态扩容：需要向 OS 申请内存（系统调用开销）、可能触发 Full GC、高峰期扩容性能更差。提前申请好，运行期间不再扩缩容。

> **-Xss 为什么重要？** 线程栈默认 1m，云原生环境下容器通常只分 2-4g。如果有 6000 个线程，光线程栈就占 6000 × 1m ≈ 6g 虚拟内存，很容易被打爆。连接池多的场景建议减到 512k。

---

## 二、线上问题排查工具

| 工具 | 用途 |
|------|------|
| `jps` | 列出 JVM 进程 |
| `jstat -gc <pid> 1000` | 实时查看 GC 状态、各区内存占用 |
| `jstat -gcutil <pid> 1000` | 看各区域使用率（百分比） |
| `jmap -heap <pid>` | 查看堆配置和使用情况 |
| `jmap -dump:format=b,file=heap.hprof <pid>` | 导出 heap dump |
| `jstack <pid>` | 打印线程栈（排查死锁、CPU 100%、线程泄漏） |
| `jcmd <pid> GC.heap_info` | 堆概要信息 |
| Arthas `dashboard` | 实时 JVM 监控面板 |
| Arthas `heapdump` | 在线 heap dump |
| Arthas `thread` | 线程诊断（CPU 排行、死锁检测） |
| MAT（Memory Analyzer） | 分析 heap dump，找内存泄漏 |

---

## 三、CPU 100% 排查步骤

**常见原因：** 死循环、频繁 Full GC 导致 GC 线程一直在跑。

### 方式一：Arthas（推荐）

```bash
java -jar arthas-boot.jar

dashboard          # 看整体情况，找 CPU 高的线程
thread -n 3        # 列出 CPU 最高的 3 个线程和堆栈，直接定位代码
thread -b          # 直接找出死锁的线程
```

### 方式二：原始命令（没有 Arthas 时）

```bash
jps -l                                          # 找 Java 进程 PID
top -Hp <pid>                                   # 找 CPU 最高的线程 TID（十进制）
printf "%x\n" <tid>                             # TID 转十六进制
jstack <pid> | grep -A 30 "<tid十六进制>"        # 找到堆栈，定位代码
```

> 面试回答：先说 Arthas `thread -n`，再补一句"没有 Arthas 用 top -Hp + jstack 手动排查"。

---

## 四、内存泄漏排查步骤

**本质：** 有引用一直指着对象，GC 可达性分析认为它还活着回收不掉，Old 区持续增长，最终 OOM。

### 排查流程

```
① Arthas memory / jstat 观察 Old 区持续增长，Full GC 后也降不下来
② Arthas heapdump 导出堆快照（不停服）
③ MAT 打开 heap dump，看 Leak Suspects 报告
④ 找占用最大的对象，查引用链，定位到具体代码修复
```

### 常见内存泄漏场景

- 静态集合无限增长（`static List` 一直加，不清理）
- 未关闭的资源（Connection、Stream 没有 close）
- ThreadLocal 没有 remove（配合线程池，value 一直留在 ThreadLocalMap）
- 缓存没有淘汰机制（往 Map 里放，从来不删）
- 连接池未释放（DruidDataSource 创建后没有 release）

### 生产预防

```bash
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/heapdump.hprof
```

---

## 五、OOM 八种类型及排查路径

OOM（OutOfMemoryError）不止一种，不同报错对应不同根因：

| # | OOM 类型 | 报错信息 | 常见原因 | 排查工具/方法 |
|---|---------|---------|---------|--------------|
| 1 | **堆内存溢出** | `java.lang.OutOfMemoryError: Java heap space` | 内存泄漏、堆设置过小、大对象过多 | `jmap -dump` + MAT 分析 |
| 2 | **GC 开销超限** | `GC overhead limit exceeded` | GC 占用 98% 时间但回收不到 2% 空间 | `jstat -gc` 看 FGC 频率和回收效果 |
| 3 | **元空间溢出** | `Metaspace` | 动态代理/反射生成大量类、类加载器泄漏 | `jstat -gc` 看 MU/MC |
| 4 | **直接内存溢出** | `Direct buffer memory` | NIO ByteBuffer 未释放、Netty 堆外内存泄漏 | `pmap` / NMT |
| 5 | **线程溢出** | `unable to create native Thread` | 线程泄漏、`-Xss` 设太大、ulimit 限制太低 | `jstack` 统计线程数、`/proc/pid/limits` |
| 6 | **Map 失败** | `Map failed` | mmap 失败，堆外内存 + 线程栈耗尽虚拟内存 | 同直接内存 |
| 7 | **数组超限** | `Requested array size exceeds VM limit` | 尝试分配超大数组（如 `new int[Integer.MAX_VALUE]`） | 检查业务逻辑中的数组分配 |
| 8 | **本地方法失败** | `Out of swap space?` | 系统 swap 不足，Native 方法分配失败 | `free -m` 查看系统内存和 swap |

### 最常见的三种

**① 堆内存溢出**
```bash
# 排查：jstat 观察 Old 区持续增长不降
jstat -gcutil <pid> 1000
# dump 后用 MAT 的 Leak Suspects 看谁占了最大空间
jmap -dump:live,format=b,file=heap.hprof <pid>
```

**② 元空间溢出**
```bash
# 类太多导致，常见原因：
# - CGLIB/动态代理疯狂生成类
# - 类加载器泄漏（模块重复部署不卸载）
jstat -gc <pid> | awk '{print $8}'  # MU 列，元空间使用量
# 解决：-XX:MaxMetaspaceSize 设上限（防止无限制增长打爆容器）
```

**③ 线程溢出（云原生高频）**
```bash
# 快速确认
jstack <pid> | grep "java.lang.Thread.State" | wc -l  # 总线程数
cat /proc/<pid>/limits | grep "Max processes"          # 最大可创建线程数

# 查看各状态线程分布
jstack <pid> | grep java.lang.Thread.State | sort | uniq -c

# 线程栈默认 1m，线程数多时对虚拟内存影响很大：
# 10000 线程 × 1m = 10g 虚拟内存，容器通常只分 2-4g 容易打爆
# 连接池多的场景建议减线程栈：-Xss512k
```

> **区别**：堆溢出是"堆里对象太多"，元空间溢出是"加载的类太多"，线程溢出是"线程太多把进程虚拟内存撑满了"（不是堆的问题，是操作系统层的限制）。

---

## 六、GC 调优

### 6.1 GC 选型

| 场景 | 推荐 GC | 原因 |
|------|---------|------|
| 响应优先（线上接口） | G1 | 可预测停顿，JDK9+ 默认 |
| 吞吐优先（离线计算、批处理） | Parallel | 吞吐量最高 |
| 低延迟极致（<10ms） | ZGC | 大堆超低延迟，JDK15+ 生产可用 |
| 小堆（<4G）+ 低延迟 | CMS（已废弃） | 被 G1 替代，JDK14 移除 |

### 6.2 GC 日志分析

```bash
# jstat 实时监控
jstat -gc <pid> 1000 10   # 每秒打印一次，共 10 次

# 核心关注指标：
# YGC  / YGCT  → 年轻代 GC 次数和总耗时
# FGC  / FGCT  → Full GC 次数和总耗时
# S0/S1/E/O/M  → 各个区域的使用量

# G1 专用
jstat -gcutil <pid> 1000  # 看各区域使用率百分比
```

### 6.3 G1 核心调优参数

```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200           # 期望的最大停顿时间（软目标）
-XX:G1HeapRegionSize=4m            # Region 大小（堆 4-8g 建议 4m）
-XX:InitiatingHeapOccupancyPercent=45   # 老年代占用达 45% 触发并发标记
-XX:G1ReservePercent=10            # 预留 10% 给晋升的对象
```

### 6.4 Full GC 频繁排查

**根本原因**：Minor GC 后存活对象太多 → Survivor 放不下 → 不断晋升老年代 → 老年代撑满 → Full GC。

排查思路：
1. `jstat -gcutil <pid> 1000` 观察晋升速度和老年代增长
2. 检查是否有大对象直接进老年代（`-XX:PretenureSizeThreshold`）
3. 检查 Survivor 区是否过小，对象被迫提前晋升
4. 检查是否有内存泄漏导致老年代只增不减

### 6.5 Full GC 的两个根因

本质就两条路径指向同一个结局——**分配太快**或**回收不掉**：

```
路径一（分配太快）：
  分配太快 → Eden 满 → Minor GC → 存活对象 Survivor 放不下 → 晋升老年代 → 老年代满了 → Full GC

路径二（回收不掉）：
  内存泄漏 → 对象无法回收 → 老年代只增不减 → 老年代满了 → Full GC
```

区分方法：
| | 分配太快（业务高并发） | 回收不掉（内存泄漏） |
|------|-------------------|---------------------|
| 老年代趋势 | 波动，GC 后能降下来 | 只增不减，GC 后也降不下来 |
| 触发场景 | 突发流量、大对象查询 | 持续增长，和流量无关 |
| 线程堆栈 | 业务代码正常跑 | 可能指向泄漏对象的持有者 |

### 6.6 业务突发高并发导致 Full GC 的处理

**短期（先扛住）：**

1. **横向扩容** —— 加 pod，每个 pod 的请求量降下来，堆压力自然减轻。K8s `kubectl scale` 一行命令的事。
2. **调大堆 + 提前触发 GC**：
   ```bash
   -Xms 翻倍（如 4G → 8G）
   -XX:InitiatingHeapOccupancyPercent=35  # 提前触发 Mixed GC，别等快满了再动手
   ```
   但这是临时手段——堆大了 Full GC 的 STW 也更久，治标不治本。
3. **重启 pod** —— 如果已经反复 Full GC，直接重启比调参更快。

**长期（治本）：**

1. **减少单次请求的对象分配量** —— 高并发下对象分配的热点通常是：
   - 大查询（一次查出几千条，生成几千个 DTO）
   - 循环里拼接字符串、stream 没复用
   - 序列化/反序列化产生的中间对象
   - 排查：Arthas `vmtool` 看哪些类的实例数异常多，或 dump 看 Histogram

2. **优化晋升速度** —— 如果对象在 Survivor 放不下被"被迫晋升"：
   ```bash
   -XX:MaxTenuringThreshold=15    # 默认 15，确认没被改小
   -XX:G1NewSizePercent=10        # 加大年轻代最小占比，给 Survivor 更多空间
   ```

3. **接入层限流** —— 在高并发把 JVM 打爆之前，先在网关层拦住多余的请求：
   - Sentinel / Resilience4j 做限流，返回降级结果
   - 别让所有请求全打到 pod 里

**总结：扩容先别死 → 调参给缓冲 → 优化代码减少分配（根）→ 限流挡住超量请求**

---

## 七、日常监控指标

### 红线告警

| 指标 | 阈值 | 严重程度 |
|------|------|---------|
| 堆使用率 | > 85% | 严重 |
| 老年代使用率 | > 80% | 警告 |
| Full GC 频率 | > 1次/小时 | 警告 |
| Full GC 耗时 | > 1s | 严重 |
| 线程数 | > 预警阈值 | 严重 |
| Young GC 耗时 | > 50ms | 注意 |

### 趋势告警（能提前发现泄漏）

- 线程数增长率 > n/天：在线程泄漏 OOM 前就能告警
- GC 后堆占用持续增长：内存泄漏前兆
- 元空间使用量持续增长：类加载器泄漏前兆

---

## 八、Arthas 在线诊断速查

```bash
# 启动
java -jar arthas-boot.jar

# === 看整体 ===
dashboard              # 实时面板：CPU、内存、GC、线程

# === 线程诊断 ===
thread -n 5            # CPU 使用率 top 5 线程，直接定位代码
thread -b              # 找出死锁线程
thread --state BLOCKED # 看所有阻塞的线程
thread --state WAITING # 看所有等待的线程

# === 内存诊断 ===
memory                 # 内存使用概览
heapdump /tmp/dump.hprof  # 在线导出 heap dump（不停服）

# === 查实例数（快速确认泄漏） ===
vmtool --action getInstances --className com.alibaba.druid.pool.DruidDataSource --limit 5000

# === 方法监控（看 release 有没有执行） ===
watch <类名> <方法名> '{params, returnObj, throwExp}' -x 3

# === GC ===
vmooption -XX:+PrintGCDetails  # 动态开启 GC 日志
```

---

## 九、线程泄漏排查 SOP

以 DruidDataSource 泄漏导致 `unable to create native Thread` 为例：

```
第一步：确认现象
  - 日志搜 "OutOfMemoryError" / "unable to create"
  - 看 pod 重启次数和重启时间

第二步：抓现场（能留住的尽量留）
  jstack <pid> > thread.dump          # 线程 dump
  jmap -dump:live,file=heap.hprof <pid>  # 堆 dump
  jcmd <pid> GC.heap_info             # 堆概要

第三步：分析
  - 线程 dump：统计线程数、按线程名/状态分组，找异常增长的线程组
    例: grep "Druid-ConnectionPool" thread.dump | wc -l
  - 堆 dump：MAT 做 Leak Suspects、看 Dominator Tree、查 GC Root 引用链

第四步：根因定位
  - 查找持有泄漏对象的 GC Root
  - 追溯代码中的创建和释放逻辑
  - 验证释放路径是否可达
    （例：密码 md5 不一致导致 DruidDataSource 创建后永远无法从 map 中移除）

第五步：修复验证
  - 修复后压测验证线程数稳定
  - 加上线程数的监控告警
```

### DruidDataSource 泄漏案例分析

```
现象：unable to create native Thread，pod 反复重启

分析过程：
1. pod 重启，现场丢失（dump 文件未持久化）
2. 从存活 pod 导出 heap dump，MAT 分析
3. 发现 DruidDataSource 实例 3000+，活跃的只有 17 个，其余为僵尸实例
4. 每个 DruidDataSource 维护 2 个线程（创建连接 + 销毁连接），共 6000+ 线程

根因定位：
- DruidDataSource 存储在 Map 中，key 用 md5(jdbcUrl + username + password) 生成
- 生成 key 时用明文密码，release 时用加密后的密文密码
- 两次 md5 结果不同 → 创建的实例永远无法从 map 中移除
- 每次连接信息变更（改密码、新增数据源）都会生成新实例，僵尸不断堆积
- 最终线程数达到上限（单 pod 最大 2w 线程），无法创建新线程 → OOM → 重启

教训：
- 连接池实例必须正确释放，key的一致性至关重要
- 线程数监控告警能在 OOM 前发现问题
- OOM dump 文件要挂持久化卷，不能随 pod 重启丢失
```
