package concurrent.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池异常处理与故障恢复
 * 知识点：
 *   1. execute() vs submit() 的异常行为差异
 *   2. 线程任务异常导致线程销毁问题
 *   3. 全局 UncaughtExceptionHandler
 *   4. 优雅关闭线程池
 *   5. 生产级自定义线程池（带监控、异常处理）
 */
public class ThreadPoolFailover {

    public static void main(String[] args) throws Exception {
        demo1_executeVsSubmit();
        demo2_threadDeathAfterException();
        demo3_uncaughtHandler();
        demo4_gracefulShutdown();
        demo5_productionPool();
    }

    // ---------------------------------------------------------------
    // DEMO 1: execute() 和 submit() 的异常行为差异
    // execute → 异常直接抛出，线程终止
    // submit  → 异常被包装进 Future，不 get() 就静默丢失！
    // ---------------------------------------------------------------
    static void demo1_executeVsSubmit() throws Exception {
        System.out.println("\n===== DEMO1: execute vs submit 异常行为 =====");

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // execute：异常会打印到控制台（如果有 UncaughtExceptionHandler）
        System.out.println("--- execute 方式 ---");
        pool.execute(() -> {
            System.out.println("  execute 任务开始");
            throw new RuntimeException("execute 抛出的异常");
            // 异常会传递给 Thread.UncaughtExceptionHandler
        });

        Thread.sleep(200);

        // submit：异常被封装进 Future，必须 get() 才能感知
        System.out.println("--- submit 方式 ---");
        Future<?> future = pool.submit(() -> {
            System.out.println("  submit 任务开始");
            throw new RuntimeException("submit 抛出的异常（被吞掉了！）");
        });

        // 不 get() → 异常静默丢失，这是最危险的情况
        Thread.sleep(200);
        System.out.println("  不调用 future.get()，异常被吞掉，没有任何输出");

        // 正确做法：一定要 get() 捕获异常
        Future<?> future2 = pool.submit(() -> {
            throw new RuntimeException("submit 异常，通过get()捕获");
        });
        try {
            future2.get();
        } catch (ExecutionException e) {
            System.out.println("  通过 future.get() 捕获到: " + e.getCause().getMessage());
        }

        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 2: 任务抛异常后线程被销毁，线程池自动补充新线程
    // ---------------------------------------------------------------
    static void demo2_threadDeathAfterException() throws InterruptedException {
        System.out.println("\n===== DEMO2: 任务异常后线程被销毁 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("death-test")
        );

        for (int i = 1; i <= 4; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.printf("  任务%d 由线程 [%s] 执行%n", taskId, Thread.currentThread().getName());
                if (taskId % 2 == 0) {
                    throw new RuntimeException("任务" + taskId + " 故意抛异常");
                    // 抛异常后，该线程被销毁，线程池创建新线程补充
                }
            });
            Thread.sleep(100);
        }

        Thread.sleep(500);
        System.out.println("  最终线程池大小: " + pool.getPoolSize() + "（仍为2，自动补充了新线程）");
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 3: 全局 UncaughtExceptionHandler 兜底
    // ---------------------------------------------------------------
    static void demo3_uncaughtHandler() throws InterruptedException {
        System.out.println("\n===== DEMO3: UncaughtExceptionHandler =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "safe-thread");
                    // 在线程工厂里设置 UncaughtExceptionHandler
                    t.setUncaughtExceptionHandler((thread, ex) ->
                            System.err.println("  [UncaughtHandler] 线程 " + thread.getName()
                                    + " 发生未捕获异常: " + ex.getMessage())
                    );
                    return t;
                }
        );

        pool.execute(() -> {
            throw new RuntimeException("未捕获异常，由 UncaughtExceptionHandler 兜底");
        });

        Thread.sleep(200);
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 4: 优雅关闭线程池
    // shutdown()          → 不再接受新任务，已提交任务继续跑
    // shutdownNow()       → 尝试中断所有线程，返回未执行的任务列表
    // awaitTermination()  → 等待所有任务完成
    // ---------------------------------------------------------------
    static void demo4_gracefulShutdown() throws InterruptedException {
        System.out.println("\n===== DEMO4: 优雅关闭线程池 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("shutdown-test")
        );

        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.println("  任务" + taskId + " 开始");
                try {
                    Thread.sleep(200);
                    System.out.println("  任务" + taskId + " 完成");
                } catch (InterruptedException e) {
                    // shutdownNow() 会触发 InterruptedException
                    System.out.println("  任务" + taskId + " 被中断");
                    Thread.currentThread().interrupt();
                }
            });
        }

        System.out.println("调用 shutdown()");
        pool.shutdown();

        try {
            // 等最多 5 秒让所有任务完成
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.println("超时，强制 shutdownNow()");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("线程池已关闭: isTerminated=" + pool.isTerminated());
    }

    // ---------------------------------------------------------------
    // DEMO 5: 生产级线程池（带监控统计）
    // ---------------------------------------------------------------
    static void demo5_productionPool() throws InterruptedException {
        System.out.println("\n===== DEMO5: 生产级监控线程池 =====");

        MonitoredThreadPool pool = new MonitoredThreadPool(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20),
                new NamedThreadFactory("prod")
        );

        for (int i = 1; i <= 10; i++) {
            int taskId = i;
            pool.execute(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Thread.sleep(500);
        pool.printStats();
        pool.gracefulShutdown(5, TimeUnit.SECONDS);
        pool.printStats();
    }
}

/**
 * 生产级监控线程池
 * 在 beforeExecute / afterExecute 钩子里记录任务耗时和失败次数
 */
class MonitoredThreadPool extends ThreadPoolExecutor {
    private final AtomicLong totalTasks = new AtomicLong(0);
    private final AtomicLong failedTasks = new AtomicLong(0);
    private final AtomicLong totalTimeMs = new AtomicLong(0);
    private final ThreadLocal<Long> startTime = new ThreadLocal<>();

    MonitoredThreadPool(int core, int max, long keepAlive, TimeUnit unit,
                        BlockingQueue<Runnable> queue, ThreadFactory factory) {
        super(core, max, keepAlive, unit, queue, factory,
                (r, executor) -> System.err.println("[MonitoredPool] 任务被拒绝！队列=" + executor.getQueue().size()));
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
        totalTasks.incrementAndGet();
        totalTimeMs.addAndGet(elapsed);

        if (t != null) {
            failedTasks.incrementAndGet();
            System.err.println("[MonitoredPool] 任务异常: " + t.getMessage());
        }
        // submit() 的异常包装在 Future 里，需要额外检测
        if (r instanceof Future<?> future && future.isDone()) {
            try {
                future.get();
            } catch (ExecutionException ee) {
                failedTasks.incrementAndGet();
                System.err.println("[MonitoredPool] Future 异常: " + ee.getCause().getMessage());
            } catch (Exception ignored) {}
        }
    }

    void printStats() {
        long total = totalTasks.get();
        System.out.printf("[Stats] 已完成=%d 失败=%d 平均耗时=%.1fms 活跃线程=%d 队列=%d%n",
                total, failedTasks.get(),
                total > 0 ? (double) totalTimeMs.get() / total : 0,
                getActiveCount(), getQueue().size());
    }

    /**
     * 优雅关闭：等待任务完成，超时后强制中断
     * @param timeout 最长等待时间
     * @param unit    时间单位
     */
    void gracefulShutdown(long timeout, TimeUnit unit) {
        shutdown();
        try {
            if (!awaitTermination(timeout, unit)) {
                System.err.println("[MonitoredPool] 优雅关闭超时，强制 shutdownNow()");
                shutdownNow();
            }
        } catch (InterruptedException e) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
