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

不只是返回值的区别，核心有三点不同。

### 1. 返回值

```java
pool.execute(runnable)   // void，没有返回值
pool.submit(callable)    // 返回 Future<T>，可以拿结果
pool.submit(runnable)    // 也返回 Future<?>，但 get() 永远是 null
```

### 2. 异常处理行为（最重要）

```java
// execute：异常直接抛出，线程死亡
pool.execute(() -> {
    throw new RuntimeException("炸了");
    // → 线程销毁，走 UncaughtExceptionHandler
});

// submit：异常被包进 Future，线程不死
Future<?> f = pool.submit(() -> {
    throw new RuntimeException("炸了");
    // → 线程活着，异常藏在 f 里
});
f.get(); // → 这里才抛 ExecutionException，不调则异常永远丢失
```

### 3. afterExecute 钩子里拿到的异常不同

```java
protected void afterExecute(Runnable r, Throwable t) {
    // execute 提交：t 直接就是异常对象
    if (t != null) { log.error("execute 任务异常", t); }

    // submit 提交：t 永远是 null，需要从 Future 里拆
    if (t == null && r instanceof Future<?> f && f.isDone()) {
        try { f.get(); } catch (ExecutionException e) { log.error("submit 任务异常", e.getCause()); }
    }
}
```

### 对比总结

| | `execute()` | `submit()` |
|--|-------------|-----------|
| 返回值 | 无 | `Future<T>` |
| 异常处理 | 直接抛，线程死亡 | 封进 Future，线程存活 |
| 异常可见性 | `UncaughtExceptionHandler` | 必须 `get()` 才能感知 |
| `afterExecute` 的 `t` 参数 | 就是异常 | 永远是 `null` |
| 适用场景 | 不关心结果，允许异常外抛 | 需要结果，或需要控制异常处理时机 |

### 生产选哪个？

- 需要返回值 → `submit()`，但**必须 `get()`，否则异常丢失**
- 不需要返回值 → 推荐也用 `submit()`，异常不会导致线程死亡
- 用 `execute()` 必须配 `UncaughtExceptionHandler`，否则异常只打 stderr

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

## 十二、守护线程 vs 非守护线程

**核心区别：JVM 退出时的行为不同。**

> 当所有非守护线程结束时，JVM 立即退出，不管守护线程是否还在运行。

| 类型 | JVM 退出时 | 典型场景 |
|------|-----------|---------|
| 非守护线程（默认） | JVM 等它跑完才退出 | 业务逻辑、数据库操作、文件写入 |
| 守护线程 | JVM 直接退出，强制终止 | GC、心跳检测、日志异步刷盘、监控采集 |

JVM 内置的 GC 线程就是守护线程，业务线程全退出后 GC 也没必要继续跑了。

**线程池为什么要设为非守护线程？**

线程池里的线程在处理业务请求，必须设成非守护线程。否则 main 线程结束后 JVM 直接退出，正在执行的任务被强制中断，可能导致数据丢失或损坏。

```java
t.setDaemon(false);  // 线程工厂里必须显式设置，保证业务任务不被强制中断
```

## 十三、ThreadFactory 和 UncaughtExceptionHandler 是什么

### ThreadFactory

`ThreadFactory` 是一个只有一个方法的接口：

```java
Thread newThread(Runnable r);
```

线程池每次需要创建新线程时就调这个方法，`r` 是线程池传进来的任务包装对象，由你决定怎么创建线程再返回给线程池。

```java
// lambda 形式就是实现这个接口
r -> {
    Thread t = new Thread(r, "biz-thread"); // 给线程起名字
    t.setDaemon(false);                     // 设置守护状态
    t.setUncaughtExceptionHandler(...);     // 绑上异常处理器
    return t;                               // 返回给线程池使用
}
```

作用：**自定义线程的创建过程**，名字、优先级、守护状态、异常处理器都可以在这里统一设置。

### UncaughtExceptionHandler

`Uncaught` = **未被捕获的**。Java 的异常有两种命运：

```java
// 命运一：被 catch 住 → 已捕获，开发者自己处理，线程继续活着
try {
    riskyMethod();
} catch (Exception e) {
    // 在这里处理，异常不再向上传播
}

// 命运二：没有任何 catch 接住，从 run() 方法逃出去 → 未捕获
pool.execute(() -> {
    throw new RuntimeException("没有人 catch 我"); // Uncaught
});
```

当异常从线程的 `run()` 方法逃出去，JVM 认定这是 **Uncaught Exception**，然后调用该线程绑定的 `UncaughtExceptionHandler`——这是 JVM 给的**最后一道兜底机会**：

```
任务抛异常
    ↓
有没有 try-catch？
    有 → 已捕获，正常处理，线程继续活着
    没有 → Uncaught，线程即将死亡
              ↓
        JVM 调用 UncaughtExceptionHandler   ← 最后机会记录日志/告警
              ↓
        线程销毁
```

它不是去 catch 异常，而是在**异常已经逃出去、线程即将死亡前，给你最后一次感知和处理的机会**。

### 为什么线程工厂里要设置 UncaughtExceptionHandler

`execute()` 提交的任务没有 Future，任务抛出异常后默认输出到 stderr。

**问题在于**：生产上日志框架（Logback/Log4j）通常只收集 `logger.error()` 的输出，stderr 不一定进日志文件，异常可能完全消失，找不到任何业务现场信息。

**因此**：为了防止找不到线程执行报错后的业务现场，需要在线程工厂创建线程时就指定 `UncaughtExceptionHandler`，主动用 logger 记录，不依赖 stderr 是否被收集。

```java
threadFactory = r -> {
    Thread t = new Thread(r, "biz-thread");
    t.setUncaughtExceptionHandler((thread, ex) -> {
        // 主动走 logger，确保异常一定进业务日志
        log.error("线程 {} 未捕获异常，现场信息：", thread.getName(), ex);
        // 还可以接入告警
        alertService.send("线程池异常: " + ex.getMessage());
    });
    return t;
};

## 十四、核心线程空闲时会被销毁吗？

默认**不会**，核心线程永久存活，`keepAliveTime` 对核心线程无效，只对非核心线程生效。

```
核心线程    → 默认永久存活，空闲也不销毁
非核心线程  → 空闲超过 keepAliveTime 后销毁
```

如果希望核心线程也能在空闲时被销毁，需要显式开启：

```java
pool.allowCoreThreadTimeOut(true);
// 开启后，核心线程空闲超过 keepAliveTime 同样会被销毁，线程池最终可缩减到 0
```

**适用场景**：任务量波动大，低峰期长时间无任务，不希望核心线程白白占用资源。代价是下次来任务时需要重新创建线程，有轻微延迟。

## 十五、线程池创建后可以修改核心配置吗？

**可以。** `ThreadPoolExecutor` 提供了运行时 setter，改完立刻生效，无需重启：

```java
pool.setCorePoolSize(int newCore)
pool.setMaximumPoolSize(int newMax)
pool.setKeepAliveTime(long time, TimeUnit unit)
```

各参数改完后 JDK 内部的处理行为：

| 操作 | JDK 内部行为 |
|------|-------------|
| `coreSize` 调大 | 下次提交任务时自动创建新核心线程 |
| `coreSize` 调小 | 超出的核心线程等空闲后自然销毁（不强杀） |
| `maxSize` 调大 | 队列满时可以创建更多非核心线程 |
| `maxSize` 调小 | 超出的非核心线程空闲后自然销毁 |

**唯一不能改的是队列。** `workQueue` 在构造时是 `final` 的，没有 setter，队列容量必须在创建时就确定好。

**调整顺序有陷阱：**

```java
// 扩容时：必须先调大 max，再调大 core
// 否则瞬间出现 core > max，JDK 直接抛 IllegalArgumentException
pool.setMaximumPoolSize(newMax);
pool.setCorePoolSize(newCore);

// 缩容时：反过来，先调小 core，再调小 max
pool.setCorePoolSize(newCore);
pool.setMaximumPoolSize(newMax);
```

**结合 Apollo 动态生效：**

```java
@ApolloConfigChangeListener
public void onChange(ConfigChangeEvent event) {
    int newCore = Integer.parseInt(event.getChange("threadpool.order-async.coreSize").getNewValue());
    int newMax  = Integer.parseInt(event.getChange("threadpool.order-async.maxSize").getNewValue());
    pool.resize(newCore, newMax);  // EnterpriseThreadPool 内部已处理调整顺序
}
```

> Apollo 推送配置变更 → 回调触发 → `resize()` 生效，全程无需重启。

## 十六、线程池中线程挂了怎么处理

### 两种提交方式的线程命运不同

| | `execute()` | `submit()` |
|--|------------|-----------|
| 异常结果 | 线程**销毁**，线程池自动补新线程 | 线程**不死**，异常被封进 Future |
| 异常可见性 | 走 `UncaughtExceptionHandler`，不设置只打 stderr | 不 `get()` 异常**永远丢失** |
| 线程重建代价 | 有，频繁崩溃频繁重建 | 无 |

### DEMO 1 的关键输出（线程自动补充）

```
任务2 → 线程[demo1-thread-2]   ← 原始线程
任务4 → 线程[demo1-thread-3]   ← 线程2死了，自动补了 thread-3
任务6 → 线程[demo1-thread-4]   ← 线程3死了，自动补了 thread-4
线程池大小仍为: 2              ← 数量没变，但都是新线程
```

线程编号一直递增，证明旧线程死了一直在补新的，有持续的创建开销。

### 四种处理手段（由浅到深）

**方案一：`UncaughtExceptionHandler`（execute 场景）**

```java
threadFactory = r -> {
    Thread t = new Thread(r);
    t.setUncaughtExceptionHandler((thread, ex) ->
        log.error("[线程异常] thread={} msg={}", thread.getName(), ex.getMessage(), ex)
    );
    return t;
};
```
线程死了，但异常被感知记录，不会悄悄丢失。

**方案二：`afterExecute` 钩子捞 Future 异常（submit 场景）**

```java
@Override
protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    if (t == null && r instanceof Future<?> future && future.isDone()) {
        try {
            future.get();
        } catch (ExecutionException ee) {
            log.error("submit 任务异常", ee.getCause());
        }
    }
}
```
线程不死，异常从 Future 里捞出来统一处理。

**方案三（最佳实践）：任务内部 `try-catch`，彻底不让线程死**

```java
pool.execute(() -> {
    try {
        bizService.process(order);
    } catch (Exception e) {
        log.error("[task=place-order] 异常，现场: orderId={}", order.getId(), e);
        alertService.send("下单异常: " + e.getMessage());
        // 线程正常退出，不死亡，不重建，性能最优
    }
});
```

三种方案对比：

| 方案 | 线程存活 | 异常可见 | 推荐场景 |
|------|---------|---------|---------|
| 不处理 | 死，自动补 | 只打 stderr | 不可用 |
| UncaughtExceptionHandler | 死，自动补 | ✅ 日志 | execute 兜底 |
| afterExecute 捞 Future | 不死 | ✅ 日志 | submit 兜底 |
| 任务内 try-catch | **不死** | ✅ 日志 + 告警 | **生产首选** |

> `EnterpriseThreadPool` 的 `execute(taskName, runnable)` 已在内部封装了 `safeWrap`，任务异常自动消化，线程永远不会因业务异常而死亡。
