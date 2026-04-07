# 线程池（ThreadPool）完整笔记

## 一、七个核心参数

```java
new ThreadPoolExecutor(
    int corePoolSize,           // 核心线程数（常驻，即使空闲）
    int maximumPoolSize,        // 最大线程数
    long keepAliveTime,         // 非核心线程空闲存活时间
    TimeUnit unit,              // keepAliveTime 时间单位
    BlockingQueue<Runnable> workQueue,  // 任务队列
    ThreadFactory threadFactory,         // 线程工厂
    RejectedExecutionHandler handler     // 拒绝策略
)
```

## 二、工作流程（重点）

```
提交任务
  ↓
线程数 < corePoolSize → 创建核心线程执行
  ↓
线程数 >= core → 放入队列
  ↓
队列满 → 创建非核心线程（不超过 max）
  ↓
max 也满 → 触发拒绝策略
```

## 三、四种拒绝策略

| 策略 | 行为 | 适用场景 |
|------|------|----------|
| AbortPolicy（默认）| 抛 RejectedExecutionException | 快速失败，调用方自己处理 |
| CallerRunsPolicy | 调用者线程自己执行 | 自然限流，不丢任务 |
| DiscardPolicy | 静默丢弃新任务 | 允许丢弃（日志打点等） |
| DiscardOldestPolicy | 丢弃队列最老任务 | 只要最新任务 |

## 四、常用队列对比

| 队列 | 特点 | 风险 |
|------|------|------|
| ArrayBlockingQueue | 有界，FIFO | 无 |
| LinkedBlockingQueue | 默认无界（Integer.MAX_VALUE）| OOM |
| SynchronousQueue | 不存储，直接交接 | 必须有线程就绪 |
| PriorityBlockingQueue | 优先级无界 | OOM |

## 五、适用场景

| 场景 | 推荐配置 |
|------|----------|
| CPU 密集型 | core = CPU核+1，有界队列 |
| IO 密集型 | core = CPU核 × (1 + 等待/计算比)，有界队列 |
| 定时任务 | ScheduledThreadPoolExecutor |
| 严格顺序 | SingleThreadExecutor（生产需自建） |

> 生产禁止直接用 `Executors.*`，必须手动 `new ThreadPoolExecutor`

## 六、execute vs submit

| | execute | submit |
|--|---------|--------|
| 返回值 | 无 | Future |
| 异常处理 | 抛给 UncaughtExceptionHandler | **封装进 Future，不 get() 就丢失** |
| 使用场景 | 不关心结果 | 需要返回值或捕获异常 |

## 七、异常处理最佳实践

```java
// 1. submit 必须 get()
Future<?> f = pool.submit(task);
try { f.get(); } catch (ExecutionException e) { /* 处理 */ }

// 2. execute 用 UncaughtExceptionHandler
threadFactory = r -> {
    Thread t = new Thread(r);
    t.setUncaughtExceptionHandler((thread, ex) -> log.error("...", ex));
    return t;
};

// 3. afterExecute 钩子监控
protected void afterExecute(Runnable r, Throwable t) {
    // 记录耗时、失败数
}
```

## 八、优雅关闭

```java
pool.shutdown();                          // 不再接新任务，等已有任务完成
if (!pool.awaitTermination(30, SECONDS)) {
    pool.shutdownNow();                   // 超时强制中断
}
```

## 九、问题排查

### 任务堆积
- **现象**：`getQueue().size()` 持续增长
- **原因**：任务执行慢（慢查询/外部调用超时）
- **解决**：加超时、扩大线程数、限流

### 线程泄漏
- **现象**：`getActiveCount()` 长期等于 max，完成数不增长
- **原因**：任务永远阻塞（吞掉 InterruptedException / 无超时）
- **解决**：给所有外部调用加超时；正确传递中断信号

### 死锁
- **命令**：`jstack <pid>` 搜索 "deadlock"
- **原因**：多线程以不同顺序获取锁
- **解决**：统一加锁顺序；用 `tryLock` 加超时

### 常用排查命令

```bash
jps                             # 查 Java 进程 PID
jstack <pid>                    # 打印所有线程栈
jstack <pid> | grep -A 20 "deadlock"
jstat -gcutil <pid> 1000        # 查 GC
top -H -p <pid>                 # 各线程 CPU 使用
```

## 十、调优经验公式

```
CPU 密集型：线程数 = CPU核数 + 1
IO  密集型：线程数 = CPU核数 × (1 + 等待时间/计算时间)
混合型：    压测找最优值（JMeter / wrk）
```

> 建议通过 Apollo / Nacos 动态调整 corePoolSize，无需重启

## 十一、线程数可以大于 CPU 核数吗？

**可以，但本质是从并行变成了并发。**

- **线程数 ≤ CPU 核数**：每个线程占一个核，真正并行执行，无切换开销
- **线程数 > CPU 核数**：CPU 以时间片轮转方式调度，同一时刻仍只有「核数」个线程在跑，其余等待，本质是并发而非并行

是否有益，需要分场景：

| 任务类型 | 线程数 > CPU核 | 原因 |
|---------|--------------|------|
| CPU 密集型 | **有害** | 线程一直占 CPU，超出核数只增加上下文切换开销，吞吐下降 |
| IO 密集型 | **有益** | 线程大部分时间在等待 IO，不占 CPU，多配线程让 CPU 利用率更高 |

> 上下文切换的代价：OS 需要保存/恢复寄存器、栈、PC 指针，本身消耗 CPU 算力，线程越多切换越频繁。

### 线程数超过拐点后吞吐量为什么会下降？

切换次数本身差不多，CPU 干的总活也差不多，**单纯排队等 CPU 时间变长不会让吞吐量下降**。

真正导致吞吐下降的原因是：

**1. Cache Miss（主要原因）**
线程越多，每个线程轮回来时等待的时间越长，缓存早被其他线程的数据覆盖，每次都要重新从内存加载。内存访问比 L1 缓存慢约 100 倍，Cache Miss 累积起来把有效 CPU 算力消耗掉了。

**2. 锁竞争加剧**
任务队列（BlockingQueue）内部有锁，线程越多抢锁等待时间越长。

| 阶段 | 吞吐变化 | 原因 |
|------|---------|------|
| 线程数从少增加到拐点 | 上升 | 并发收益 > Cache Miss 代价 |
| 线程数超过拐点继续增加 | 下降 | Cache Miss + 锁竞争 > 并发收益 |

> 排队等 CPU 变长只会让 P99 延迟上升，不会直接降低吞吐量，吞吐下降的根本是 Cache Miss 导致有效算力减少。
