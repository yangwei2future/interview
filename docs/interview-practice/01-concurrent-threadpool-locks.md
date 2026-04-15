# 面试练习记录 01 —— 并发：线程池 & 锁

> 日期：2026-04-15

---

## 题 1：线程池工作流程

**题目：** 任务进来后，什么时候创建核心线程、什么时候入队、什么时候创建非核心线程、什么时候拒绝？

**我的回答：**
> 任务进来之后，先看是否有空闲的核心线程，如果有的话，直接复用即可。如果没有的话，且当前的核心线程数是小于配置的数量，此时会新起一个核心线程来干活。如果核心线程满了的话，先放到等待队列里面，如果任务堆积到队列也满了的话，并且此时配置的最大线程数是大于核心线程数的话，就新起非核心线程来跑对应的任务。如果非核心线程也占满了，此时就开始由线程池配置的拒绝策略来决定如何处理，默认的策略是静默丢弃，也可以配置成谁提交谁执行。

**需要纠正的两点：**

**错误 1：第一步逻辑不对**
- ❌ "先看是否有空闲核心线程，有就复用"
- ✅ 只要 `workerCount < corePoolSize`，就直接创建新线程，**不管现有线程是否空闲**
- 核心线程是"先创建满，再入队"，不是"优先复用空闲的"

**错误 2：默认拒绝策略说反了**
- ❌ 默认是"静默丢弃"
- ✅ 默认是 `AbortPolicy`，直接抛 `RejectedExecutionException`

| 策略 | 行为 |
|------|------|
| **AbortPolicy（默认）** | 抛 RejectedExecutionException |
| CallerRunsPolicy | 谁提交谁执行 |
| DiscardPolicy | 静默丢弃新任务 |
| DiscardOldestPolicy | 丢弃队列最老任务 |

**正确流程：**
```
提交任务
 → count < core？ → 新建核心线程
 → 入队（有界/无界）
 → 队列满？ → count < max？ → 新建非核心线程
 → 触发拒绝策略（默认 AbortPolicy 抛异常）
```

---

## 题 2：submit 异常陷阱

**题目：**
```java
ExecutorService pool = Executors.newFixedThreadPool(10);
pool.submit(() -> {
    throw new RuntimeException("业务异常");
});
// 没有调用 future.get()
```
这段代码会发生什么？异常去哪了？线程还活着吗？

**我的回答：**
> submit 会返回一个 future，异常通过 future.get() 去拿到的。你没调用 get 方法，那么就拿不到异常信息。同时，因为是 submit 方法，里面是有 try catch 逻辑的，异常是被传递到了 future 中，线程还是活的。

**评价：完全正确 ✅**
- 异常被包装进 Future，不会抛出
- 没调用 `get()` → 异常静默丢失
- 线程存活（submit 内部 catch 了异常）

**补充延伸点（面试可能追问）：`afterExecute` 钩子里能拿到 submit 的异常吗？**

```java
protected void afterExecute(Runnable r, Throwable t) {
    // execute() → t 就是异常
    // submit()  → t 永远是 null！要这样拿：
    if (t == null && r instanceof Future<?>) {
        try {
            ((Future<?>) r).get();
        } catch (ExecutionException e) {
            t = e.getCause(); // 这才是真正的业务异常
        }
    }
}
```
submit 场景下 `afterExecute` 的第二个参数 `t` 始终为 null，这是经典陷阱。

---

## 题 3：任务堆积排查 + 不重启动态扩容

**题目：** 订单系统异步通知下游的线程池，某天早上发现任务堆积严重，队列快满了。怎么排查？排查完如果需要临时扩容，怎么做（不重启服务）？

**我的回答：**
> 说明消费跟不上生产。有几个可能：一是提交的任务有问题，一直占着线程不结束，可能是慢 SQL 或 RPC 调用超时。二是消费能力跟不上，业务量暴增，此时最有效的方式是水平扩展实例（加机器），也可以分析线程池设置是否合理。

**评价：排查方向对，缺失不重启动态扩容的答案**

**完整排查步骤：**
1. 看监控：`activeCount` 是否跑满 `maxSize`？
2. 看任务耗时分布：p99 是否异常（慢 SQL / RPC 超时）
3. `jstack` 线程快照：看活跃线程卡在哪一行
4. 看队列积压速度：是持续增长还是偶发抖动

**不重启动态扩容（关键缺失点）：**

```java
ThreadPoolExecutor executor = (ThreadPoolExecutor) pool;

// 扩容（顺序不能错！先扩 max，再扩 core）
executor.setMaximumPoolSize(20);
executor.setCorePoolSize(10);

// 缩容（顺序反过来：先缩 core，再缩 max）
executor.setCorePoolSize(4);
executor.setMaximumPoolSize(8);
```

> 顺序为什么重要：扩容时若先设 core > 当前 max，会直接抛 `IllegalArgumentException`。

结合 Apollo 配置中心，可以实现配置变更自动触发 resize，秒级生效，比加机器（分钟级）快得多。

---

## 延伸实践：jstack & Arthas 线上排查

### jstack 快速使用

```bash
jps -l                    # 找到进程 PID
jstack [pid] > dump.txt   # 抓线程快照
```

**线程状态对照：**

| 线程状态 | 说明 | 说明问题 |
|----------|------|----------|
| `WAITING (parking)` + 卡在业务代码 | IO/锁等待 | 慢 SQL 或 RPC 超时 |
| `TIMED_WAITING` + 卡在 `getTask` | 队列空，等任务 | 线程空闲，正常 |
| `BLOCKED` | 等 synchronized 锁 | 锁竞争严重 |

**示例：正常空闲线程长这样（不是问题）**
```
"job-event-22" TIMED_WAITING (parking)
    at LinkedBlockingQueue.poll       ← 在等任务，队列空
    at ThreadPoolExecutor.getTask
    at ThreadPoolExecutor.runWorker
```

### Arthas 实战

```bash
java -jar arthas-boot.jar [pid]   # attach 进程，0 侵入

# 常用命令
thread -n 5                        # CPU 最高的 5 个线程
thread --state WAITING             # 所有等待中的线程
trace com.xxx.Service method -n 5  # 追踪方法内各步骤耗时
watch com.xxx.Service method "{params,returnObj,throwExp}" -x 2  # 看入参/返回/异常
stop                               # 退出并 detach
```

**实际 trace 输出示例：**
```
---[297ms] OrderService:processOrder()
    +---[27% 80ms ] DbService:queryOrder()           # 查DB
    +---[68% 203ms] RpcService:notifyDownstream()    # RPC ← 瓶颈
    `---[4%  12ms ] CacheService:updateCache()       # 缓存
```

一眼定位：68% 时间在 RPC，优化方向明确。

**面试表述模板：**
> "用 Arthas trace 挂到线上进程，不重启服务。输出显示 notifyDownstream 占了 68% 耗时，定位到是下游 RPC 没有设合理 timeout。加了 200ms 超时 + 降级逻辑后，任务堆积问题消失。"
