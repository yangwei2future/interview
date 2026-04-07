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

## 进度

- [ ] Java 基础
- [ ] 数据结构
- [ ] 算法
- [ ] 设计模式
- [ ] Spring
- [ ] JVM
- [x] 并发编程（线程池）
- [ ] 数据库
- [ ] 系统设计
- [ ] 行为面试
