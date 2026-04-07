package concurrent.threadpool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 企业级线程池工具类
 *
 * 设计目标：
 *   1. Builder 统一配置入口，参数语义清晰，替代裸 new ThreadPoolExecutor
 *   2. 慢任务自动告警，接入 beforeExecute/afterExecute 钩子无侵入采集
 *   3. 拒绝策略回调，告警而不是默默丢弃或崩溃
 *   4. 实时健康快照，暴露 metrics 供监控系统拉取
 *   5. 优雅关闭，保证任务不丢失
 *   6. 动态扩缩容，运行时调整 core/max（对接 Apollo 配置变更）
 *
 * 典型用法：
 * <pre>
 *   EnterpriseThreadPool pool = EnterpriseThreadPool.builder("order-async")
 *       .coreSize(4)
 *       .maxSize(8)
 *       .queueCapacity(200)
 *       .slowTaskThresholdMs(500)
 *       .onSlowTask((name, costMs) -> log.warn("慢任务 task={} cost={}ms", name, costMs))
 *       .onRejected((task, executor) -> alertService.send("线程池已满，任务被拒绝"))
 *       .build();
 *
 *   pool.execute("place-order", () -> orderService.process(order));
 *   Future<Result> future = pool.submit("query-stock", () -> stockService.query(sku));
 *   Metrics metrics = pool.getMetrics();
 * </pre>
 */
public class EnterpriseThreadPool {

    private final String name;
    private final InnerPool executor;

    private EnterpriseThreadPool(String name, InnerPool executor) {
        this.name = name;
        this.executor = executor;
    }

    // ---------------------------------------------------------------
    // 任务提交
    // ---------------------------------------------------------------

    /**
     * 提交无返回值任务，taskName 用于慢任务告警时识别来源
     */
    public void execute(String taskName, Runnable task) {
        executor.execute(new TaggedRunnable(taskName, task));
    }

    /**
     * 提交无返回值任务（无业务名称，兜底用）
     */
    public void execute(Runnable task) {
        execute("anonymous", task);
    }

    /**
     * 提交有返回值任务
     */
    public <T> Future<T> submit(String taskName, Callable<T> task) {
        return executor.submit(new TaggedCallable<>(taskName, task));
    }

    /**
     * 批量提交，等待全部完成并返回结果列表
     * 单个任务失败不影响其他任务，失败结果以 null 填充
     *
     * @param taskName 批次名称，用于日志区分
     * @param tasks    任务列表
     * @param timeout  整批最长等待时间
     * @param unit     时间单位
     */
    public <T> List<T> submitBatch(String taskName, List<Callable<T>> tasks,
                                   long timeout, TimeUnit unit) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            futures.add(submit(taskName + "#" + i, tasks.get(i)));
        }

        List<T> results = new ArrayList<>(tasks.size());
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        for (Future<T> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                results.add(null);
                continue;
            }
            try {
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (ExecutionException e) {
                results.add(null);
            } catch (TimeoutException e) {
                future.cancel(true);
                results.add(null);
            }
        }
        return results;
    }

    // ---------------------------------------------------------------
    // 运维接口
    // ---------------------------------------------------------------

    /**
     * 获取当前健康快照，供监控系统定期拉取
     */
    public Metrics getMetrics() {
        return new Metrics(
                name,
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size(),
                executor.getCompletedTaskCount(),
                executor.rejectedCount.get(),
                executor.slowTaskCount.get(),
                executor.totalTaskCount.get() > 0
                        ? executor.totalElapsedMs.get() / executor.totalTaskCount.get()
                        : 0
        );
    }

    /**
     * 是否健康：活跃线程未打满 max，队列未超过 80%
     */
    public boolean isHealthy() {
        return executor.getActiveCount() < executor.getMaximumPoolSize()
                && executor.getQueue().size() < executor.getQueue().remainingCapacity() * 4;
    }

    /**
     * 动态调整线程数（对接 Apollo 配置变更回调）
     * 示例：
     *   @ApolloConfigChangeListener
     *   public void onChange(ConfigChangeEvent event) {
     *       pool.resize(config.getInt("core"), config.getInt("max"));
     *   }
     */
    public void resize(int newCoreSize, int newMaxSize) {
        if (newMaxSize >= newCoreSize && newCoreSize > 0) {
            // 必须先调大 max 再调大 core，否则 core > max 会抛异常
            if (newMaxSize > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(newMaxSize);
                executor.setCorePoolSize(newCoreSize);
            } else {
                executor.setCorePoolSize(newCoreSize);
                executor.setMaximumPoolSize(newMaxSize);
            }
        }
    }

    /**
     * 优雅关闭：等待任务完成，超时后强制中断
     */
    public void shutdown(long timeout, TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                List<Runnable> dropped = executor.shutdownNow();
                if (!dropped.isEmpty()) {
                    System.err.printf("[%s] 优雅关闭超时，%d 个任务被丢弃%n", name, dropped.size());
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------

    public static Builder builder(String poolName) {
        return new Builder(poolName);
    }

    public static class Builder {
        private final String name;
        // Apollo 配置 key 建议：threadpool.{name}.coreSize / maxSize / queueCapacity
        private int coreSize = 4;
        private int maxSize = 8;
        private int queueCapacity = 500;
        private long keepAliveSeconds = 60;
        private long slowTaskThresholdMs = 1000;
        private BiConsumer<String, Long> onSlowTask = null;
        private BiConsumer<Runnable, ThreadPoolExecutor> onRejected = null;

        private Builder(String name) {
            this.name = name;
        }

        public Builder coreSize(int coreSize) {
            this.coreSize = coreSize;
            return this;
        }

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder keepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
            return this;
        }

        public Builder slowTaskThresholdMs(long thresholdMs) {
            this.slowTaskThresholdMs = thresholdMs;
            return this;
        }

        /** 慢任务回调，接入告警平台（如钉钉/飞书/Sentry） */
        public Builder onSlowTask(BiConsumer<String, Long> callback) {
            this.onSlowTask = callback;
            return this;
        }

        /** 拒绝任务回调，接入告警平台 */
        public Builder onRejected(BiConsumer<Runnable, ThreadPoolExecutor> callback) {
            this.onRejected = callback;
            return this;
        }

        public EnterpriseThreadPool build() {
            final String poolName = name;
            if (onSlowTask == null) {
                onSlowTask = (taskName, costMs) ->
                        System.err.printf("[SlowTask] pool=%s task=%s cost=%dms%n", poolName, taskName, costMs);
            }
            if (onRejected == null) {
                onRejected = (task, pool) ->
                        System.err.printf("[Rejected] pool=%s queueSize=%d activeThreads=%d%n",
                                poolName, pool.getQueue().size(), pool.getActiveCount());
            }
            final BiConsumer<Runnable, ThreadPoolExecutor> rejectedCallback = onRejected;
            InnerPool executor = new InnerPool(
                    coreSize,
                    maxSize,
                    keepAliveSeconds,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new NamedThreadFactory(name),
                    slowTaskThresholdMs,
                    onSlowTask,
                    (r, pool) -> {
                        rejectedCallback.accept(r, pool);
                        // 默认拒绝策略：由调用方线程自己执行，起到背压效果
                        new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(r, pool);
                    }
            );
            return new EnterpriseThreadPool(name, executor);
        }
    }

    // ---------------------------------------------------------------
    // 健康快照值对象
    // ---------------------------------------------------------------

    public static class Metrics {
        public final String poolName;
        public final int poolSize;
        public final int activeCount;
        public final int coreSize;
        public final int maxSize;
        public final int queueSize;
        public final long completedCount;
        public final long rejectedCount;
        public final long slowTaskCount;
        public final long avgCostMs;

        Metrics(String poolName, int poolSize, int activeCount, int coreSize, int maxSize,
                int queueSize, long completedCount, long rejectedCount,
                long slowTaskCount, long avgCostMs) {
            this.poolName = poolName;
            this.poolSize = poolSize;
            this.activeCount = activeCount;
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueSize = queueSize;
            this.completedCount = completedCount;
            this.rejectedCount = rejectedCount;
            this.slowTaskCount = slowTaskCount;
            this.avgCostMs = avgCostMs;
        }

        @Override
        public String toString() {
            return String.format(
                    "[Metrics] pool=%-15s  core/max=%d/%d  threads=%d  active=%d  " +
                    "queue=%d  completed=%d  rejected=%d  slow=%d  avgCost=%dms",
                    poolName, coreSize, maxSize, poolSize, activeCount,
                    queueSize, completedCount, rejectedCount, slowTaskCount, avgCostMs);
        }
    }

    // ---------------------------------------------------------------
    // 内部线程池实现（对外不暴露）
    // ---------------------------------------------------------------

    private static class InnerPool extends ThreadPoolExecutor {

        private final long slowTaskThresholdMs;
        private final BiConsumer<String, Long> onSlowTask;

        final AtomicLong totalTaskCount = new AtomicLong(0);
        final AtomicLong totalElapsedMs = new AtomicLong(0);
        final AtomicLong slowTaskCount = new AtomicLong(0);
        final AtomicLong rejectedCount = new AtomicLong(0);

        /** 用 ThreadLocal 记录每个任务的开始时间 */
        private final ThreadLocal<Long> startTime = new ThreadLocal<>();

        InnerPool(int core, int max, long keepAlive, TimeUnit unit,
                  BlockingQueue<Runnable> queue, ThreadFactory factory,
                  long slowTaskThresholdMs, BiConsumer<String, Long> onSlowTask,
                  RejectedExecutionHandler handler) {
            super(core, max, keepAlive, unit, queue, factory,
                    (r, pool) -> {
                        // 包装拒绝策略，统一计数
                        ((InnerPool) pool).rejectedCount.incrementAndGet();
                        handler.rejectedExecution(r, pool);
                    });
            this.slowTaskThresholdMs = slowTaskThresholdMs;
            this.onSlowTask = onSlowTask;
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            startTime.set(System.currentTimeMillis());
            super.beforeExecute(t, r);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);

            long elapsed = System.currentTimeMillis() - startTime.get();
            startTime.remove();

            totalTaskCount.incrementAndGet();
            totalElapsedMs.addAndGet(elapsed);

            // 慢任务检测
            if (elapsed > slowTaskThresholdMs) {
                slowTaskCount.incrementAndGet();
                String taskName = resolveTaskName(r);
                onSlowTask.accept(taskName, elapsed);
            }

            // submit() 封装的异常，捞出来避免静默吞掉
            if (t == null && r instanceof Future<?> future && future.isDone()) {
                try {
                    future.get();
                } catch (ExecutionException ee) {
                    t = ee.getCause();
                } catch (Exception ignored) {}
            }
            if (t != null) {
                System.err.printf("[TaskError] task=%s error=%s%n", resolveTaskName(r), t.getMessage());
            }
        }

        private String resolveTaskName(Runnable r) {
            if (r instanceof TaggedRunnable)    return ((TaggedRunnable) r).taskName;
            if (r instanceof TaggedCallable<?>)  return ((TaggedCallable<?>) r).taskName;
            return r.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------
    // 携带业务名称的任务包装类
    // ---------------------------------------------------------------

    private static class TaggedRunnable implements Runnable {
        final String taskName;
        private final Runnable delegate;

        TaggedRunnable(String taskName, Runnable delegate) {
            this.taskName = taskName;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    private static class TaggedCallable<T> implements Callable<T> {
        final String taskName;
        private final Callable<T> delegate;

        TaggedCallable(String taskName, Callable<T> delegate) {
            this.taskName = taskName;
            this.delegate = delegate;
        }

        @Override
        public T call() throws Exception {
            return delegate.call();
        }
    }

    // ---------------------------------------------------------------
    // 使用示例
    // ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // 1. 构建线程池（对应 Apollo 配置：threadpool.order-async.coreSize=4 等）
        EnterpriseThreadPool pool = EnterpriseThreadPool.builder("order-async")
                .coreSize(2)
                .maxSize(4)
                .queueCapacity(20)
                .slowTaskThresholdMs(300)
                .onSlowTask((taskName, costMs) ->
                        System.err.printf("[告警] 慢任务 task=%s cost=%dms，请排查！%n", taskName, costMs))
                .onRejected((task, executor) ->
                        System.err.printf("[告警] 任务被拒绝，queue=%d active=%d，请扩容！%n",
                                executor.getQueue().size(), executor.getActiveCount()))
                .build();

        // 2. 提交普通任务
        pool.execute("send-notification", () -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("  通知已发送");
        });

        // 3. 提交有返回值的任务
        Future<String> future = pool.submit("query-user", () -> {
            Thread.sleep(200);
            return "user-info-result";
        });
        System.out.println("  查询结果: " + future.get(2, TimeUnit.SECONDS));

        // 4. 触发慢任务告警
        pool.execute("slow-db-query", () -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        // 5. 动态扩容（模拟 Apollo 推送配置变更）
        Thread.sleep(200);
        pool.resize(4, 8);
        System.out.println("  已动态扩容: core=4 max=8");

        // 6. 打印健康快照
        Thread.sleep(1000);
        System.out.println("\n" + pool.getMetrics());
        System.out.println("  是否健康: " + pool.isHealthy());

        // 7. 优雅关闭
        pool.shutdown(10, TimeUnit.SECONDS);
        System.out.println("  线程池已关闭");
    }
}
