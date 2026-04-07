package concurrent.threadpool;

import java.util.concurrent.*;

/**
 * 线程池适用场景示例
 * 知识点：
 *   1. 四种预置线程池（Executors 工具类）及其适用场景
 *   2. 每种池的底层参数
 *   3. 为什么生产上不推荐直接用 Executors
 */
public class ThreadPoolScenarios {

    public static void main(String[] args) throws Exception {
        demo1_fixedThreadPool();
        demo2_cachedThreadPool();
        demo3_singleThreadPool();
        demo4_scheduledThreadPool();
        demo5_whyNotExecutors();
    }

    // ---------------------------------------------------------------
    // 场景1: FixedThreadPool —— CPU 密集型 / 并发量稳定
    // 底层: core == max，LinkedBlockingQueue（无界）
    // 适合: 批量计算、图像处理、数据分析
    // 风险: 无界队列可能 OOM
    // ---------------------------------------------------------------
    static void demo1_fixedThreadPool() throws InterruptedException {
        System.out.println("\n===== FixedThreadPool: CPU密集型任务 =====");

        // 推荐 core 数 = CPU核心数 或 CPU核心数+1
        int cpuCores = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cpuCores);

        System.out.println("CPU核心数: " + cpuCores + "，线程池大小: " + cpuCores);

        CountDownLatch latch = new CountDownLatch(5);
        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.submit(() -> {
                try {
                    // 模拟 CPU 密集计算
                    long result = fibonacci(40);
                    System.out.println("  任务" + taskId + " 计算结果: " + result);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // 场景2: CachedThreadPool —— IO 密集型 / 短期大量并发
    // 底层: core=0, max=Integer.MAX_VALUE, SynchronousQueue, keepAlive=60s
    // 适合: 大量短时 IO 任务（HTTP 请求、文件读写）
    // 风险: 线程数无上限，可能创建过多线程导致 OOM
    // ---------------------------------------------------------------
    static void demo2_cachedThreadPool() throws InterruptedException {
        System.out.println("\n===== CachedThreadPool: IO密集型短任务 =====");

        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch latch = new CountDownLatch(10);

        for (int i = 1; i <= 10; i++) {
            int taskId = i;
            pool.submit(() -> {
                try {
                    // 模拟 IO 等待
                    Thread.sleep(100);
                    System.out.println("  IO任务" + taskId + " 完成 by " + Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // 场景3: SingleThreadExecutor —— 严格顺序执行
    // 底层: core=max=1, LinkedBlockingQueue（无界）
    // 适合: 日志写入、消息顺序消费、数据库单线程写
    // 风险: 无界队列可能 OOM
    // ---------------------------------------------------------------
    static void demo3_singleThreadPool() throws InterruptedException {
        System.out.println("\n===== SingleThreadExecutor: 严格顺序任务 =====");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.submit(() -> {
                try {
                    System.out.println("  顺序执行任务" + taskId + "（保证顺序）");
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // 场景4: ScheduledThreadPool —— 定时/周期性任务
    // 适合: 心跳检测、缓存刷新、定时报告
    // ---------------------------------------------------------------
    static void demo4_scheduledThreadPool() throws InterruptedException {
        System.out.println("\n===== ScheduledThreadPool: 定时任务 =====");

        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);

        // 延迟执行一次
        pool.schedule(() ->
                System.out.println("  [延迟] 500ms 后执行一次"),
                500, TimeUnit.MILLISECONDS);

        // 固定频率执行（前一个任务开始后每隔 300ms）
        ScheduledFuture<?> fixedRate = pool.scheduleAtFixedRate(() ->
                System.out.println("  [固定频率] 每300ms执行，from " + System.currentTimeMillis()),
                0, 300, TimeUnit.MILLISECONDS);

        // 固定延迟执行（前一个任务结束后再等 200ms）
        ScheduledFuture<?> fixedDelay = pool.scheduleWithFixedDelay(() ->
                System.out.println("  [固定延迟] 上次结束后200ms再执行"),
                0, 200, TimeUnit.MILLISECONDS);

        Thread.sleep(1000);
        fixedRate.cancel(false);
        fixedDelay.cancel(false);
        pool.shutdown();
        System.out.println("  定时任务已停止");
    }

    // ---------------------------------------------------------------
    // 场景5: 为什么生产上不用 Executors？
    // FixedThreadPool / SingleThreadExecutor → 无界队列 → OOM
    // CachedThreadPool                        → 无限线程 → OOM
    // ---------------------------------------------------------------
    static void demo5_whyNotExecutors() {
        System.out.println("\n===== 为什么生产不推荐直接用 Executors =====");
        System.out.println("""
                问题根源：Executors 工厂方法隐藏了危险参数

                FixedThreadPool(n) 底层等价于：
                  new ThreadPoolExecutor(n, n, 0L, MILLISECONDS,
                      new LinkedBlockingQueue<Runnable>())  // 队列容量 Integer.MAX_VALUE！
                  → 任务堆积可能 OOM

                CachedThreadPool() 底层等价于：
                  new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, SECONDS,
                      new SynchronousQueue<>())
                  → 线程数无上限，高并发可能 OOM

                生产最佳实践：
                  1. 手动 new ThreadPoolExecutor，明确每个参数
                  2. 队列用有界 ArrayBlockingQueue
                  3. 拒绝策略用 CallerRunsPolicy（限流效果）或自定义（记录日志/告警）
                  4. 线程工厂设置有意义的名称，方便排查
                """);

        // 生产推荐写法
        ThreadPoolExecutor productionPool = new ThreadPoolExecutor(
                4,
                8,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),           // 有界队列
                new NamedThreadFactory("biz-service"),   // 有意义的名称
                (r, executor) -> {                        // 自定义拒绝策略
                    System.err.println("[WARN] 线程池已满，任务被拒绝！" +
                            " 队列大小=" + executor.getQueue().size() +
                            " 活跃线程=" + executor.getActiveCount());
                    // 生产上这里应该：打日志 + 监控告警 + 可选降级
                }
        );

        System.out.println("生产推荐线程池已创建：" + productionPool);
        productionPool.shutdown();
    }

    static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
}
