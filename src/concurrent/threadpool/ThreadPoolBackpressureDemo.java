package concurrent.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务堆积排查实战演示
 *
 * 包含三个场景，逐步演示堆积如何产生、如何发现、如何定位根因：
 *   DEMO 1 - 制造堆积：提交速度 >> 消费速度（模拟慢查询）
 *   DEMO 2 - 监控发现：通过周期性快照感知堆积趋势
 *   DEMO 3 - 定位根因：包装任务记录慢任务，找出瓶颈
 */
public class ThreadPoolBackpressureDemo {

    public static void main(String[] args) throws Exception {
        demo1_createBackpressure();
        demo2_monitorBackpressure();
        demo3_diagnoseSlowTask();
    }

    // ---------------------------------------------------------------
    // DEMO 1: 制造任务堆积
    //
    // 背景：2 个线程，每个任务模拟"慢查询"需要 500ms
    //       但每 50ms 就提交一个任务 → 生产速度 >> 消费速度
    //
    // 现象：队列持续增长，活跃线程始终 == 2（全部忙碌）
    // ---------------------------------------------------------------
    static void demo1_createBackpressure() throws InterruptedException {
        System.out.println("\n===== DEMO 1: 制造任务堆积 =====");
        System.out.println("线程池：core=2  max=2  队列=无界");
        System.out.println("任务耗时：500ms/个，提交间隔：50ms → 堆积不可避免\n");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),   // 无界队列，任务只会堆积不会被拒绝
                new NamedThreadFactory("slow-worker")
        );

        // 提交 20 个任务：每个任务 sleep 500ms 模拟慢查询
        for (int i = 1; i <= 20; i++) {
            int taskId = i;
            pool.execute(() -> simulateSlowQuery(taskId));
            Thread.sleep(50); // 每 50ms 提交一个，远快于消费速度

            // 每提交 5 个打印一次队列状态，直观感受堆积
            if (taskId % 5 == 0) {
                System.out.printf("  [提交进度] 已提交=%d  队列积压=%d  活跃线程=%d  已完成=%d%n",
                        taskId,
                        pool.getQueue().size(),
                        pool.getActiveCount(),
                        pool.getCompletedTaskCount());
            }
        }

        System.out.println("\n  → 提交结束，但队列里还有大量任务等待处理");
        System.out.printf("  → 当前积压：%d 个任务需要约 %d 秒才能清空%n",
                pool.getQueue().size(),
                pool.getQueue().size() / 2); // 2个线程并行，约需 size/2 秒

        pool.shutdownNow(); // 强制结束，不等了
        System.out.println("  (已强制终止，仅演示堆积现象)");
    }

    // ---------------------------------------------------------------
    // DEMO 2: 监控发现堆积趋势
    //
    // 核心思路：定期采样线程池指标，通过"趋势"判断是否在堆积
    //   - 队列持续增长 → 消费跟不上
    //   - 队列稳定高位 → 已达平衡但积压严重
    //   - 队列持续下降 → 正在消化，问题在缓解
    // ---------------------------------------------------------------
    static void demo2_monitorBackpressure() throws InterruptedException {
        System.out.println("\n===== DEMO 2: 监控发现堆积趋势 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("monitored-worker")
        );

        // 启动监控线程：每 300ms 打印一次快照
        Thread monitor = buildMonitorThread(pool);
        monitor.setDaemon(true);
        monitor.start();

        // 前 10 个任务：模拟慢查询（造成堆积）
        System.out.println("  阶段一：提交 10 个慢任务（每个 800ms）");
        for (int i = 1; i <= 10; i++) {
            pool.execute(() -> sleep(800));
        }

        Thread.sleep(2000); // 观察堆积阶段

        // 后 5 个任务：换成快任务（堆积开始消化）
        System.out.println("\n  阶段二：提交 5 个快任务（每个 50ms），观察队列下降");
        for (int i = 1; i <= 5; i++) {
            pool.execute(() -> sleep(50));
        }

        Thread.sleep(2000);
        monitor.interrupt();
        pool.shutdownNow();

        // 排查结论提示
        System.out.println("\n  排查结论：");
        System.out.println("  - 阶段一监控中：queue 持续高位 + active=2 → 消费速度不足");
        System.out.println("  - 阶段二监控中：queue 下降 → 任务耗时短了，开始消化");
        System.out.println("  - 根因指向：任务本身太慢，需要进一步定位慢在哪里 → 看 DEMO3");
    }

    // ---------------------------------------------------------------
    // DEMO 3: 定位根因 — 找出慢任务
    //
    // 手段：在 beforeExecute / afterExecute 钩子中记录耗时
    //       超过阈值的任务打印告警，暴露慢任务来源
    // ---------------------------------------------------------------
    static void demo3_diagnoseSlowTask() throws InterruptedException {
        System.out.println("\n===== DEMO 3: 定位慢任务根因 =====");
        System.out.println("  策略：任务耗时超过 200ms 即打印慢任务警告\n");

        DiagnosticThreadPool pool = new DiagnosticThreadPool(
                2, 2,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("diag-worker"),
                200 // 慢任务阈值：200ms
        );

        // 混合提交：正常任务 + 模拟各类慢任务根因
        pool.execute(namedTask("正常业务",       () -> sleep(50)));
        pool.execute(namedTask("慢SQL查询",      () -> sleep(600)));  // DB慢查询
        pool.execute(namedTask("HTTP超时",       () -> sleep(1200))); // 外部接口超时
        pool.execute(namedTask("正常业务",       () -> sleep(80)));
        pool.execute(namedTask("Redis慢操作",    () -> sleep(400)));  // 缓存慢
        pool.execute(namedTask("正常业务",       () -> sleep(30)));
        pool.execute(namedTask("大文件IO",       () -> sleep(900)));  // I/O慢

        Thread.sleep(4000);

        // 打印诊断报告
        pool.printDiagnosticReport();
        pool.gracefulShutdown(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------

    /** 模拟慢查询：sleep 500ms */
    private static void simulateSlowQuery(int taskId) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 给任务贴名字，方便诊断时识别是哪类任务慢 */
    private static Runnable namedTask(String name, Runnable action) {
        return new NamedRunnable(name, action);
    }

    /** 构建监控线程：每 300ms 打印一次线程池快照 */
    private static Thread buildMonitorThread(ThreadPoolExecutor pool) {
        return new Thread(() -> {
            long prevCompleted = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long completed = pool.getCompletedTaskCount();
                    long throughput = completed - prevCompleted; // 本周期内完成数
                    prevCompleted = completed;

                    System.out.printf("  [监控] queue=%-3d  active=%-2d  completed=%-3d  本周期完成=%d%n",
                            pool.getQueue().size(),
                            pool.getActiveCount(),
                            completed,
                            throughput);
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}

// ---------------------------------------------------------------
// 诊断线程池：在钩子中记录每个任务的耗时，超阈值打告警
// ---------------------------------------------------------------
class DiagnosticThreadPool extends ThreadPoolExecutor {

    private final long slowThresholdMs;
    private final ThreadLocal<Long> startTime = new ThreadLocal<>();
    private final AtomicInteger slowTaskCount = new AtomicInteger(0);
    private final AtomicInteger totalTaskCount = new AtomicInteger(0);
    private long totalElapsedMs = 0;

    DiagnosticThreadPool(int core, int max, long keepAlive, TimeUnit unit,
                         BlockingQueue<Runnable> queue, ThreadFactory factory,
                         long slowThresholdMs) {
        super(core, max, keepAlive, unit, queue, factory);
        this.slowThresholdMs = slowThresholdMs;
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
        totalElapsedMs += elapsed;

        // 慢任务告警：超阈值时打印任务名称和耗时
        String taskName = (r instanceof NamedRunnable) ? ((NamedRunnable) r).name : "未知任务";
        if (elapsed > slowThresholdMs) {
            slowTaskCount.incrementAndGet();
            System.out.printf("  [慢任务告警] 任务=%-12s 耗时=%dms > 阈值%dms  → 需要排查该任务%n",
                    taskName, elapsed, slowThresholdMs);
        } else {
            System.out.printf("  [正常完成]   任务=%-12s 耗时=%dms%n", taskName, elapsed);
        }
    }

    void printDiagnosticReport() {
        int total = totalTaskCount.get();
        System.out.println("\n  ====== 诊断报告 ======");
        System.out.printf("  总任务数：%d%n", total);
        System.out.printf("  慢任务数：%d（占比 %.0f%%）%n",
                slowTaskCount.get(),
                total > 0 ? slowTaskCount.get() * 100.0 / total : 0);
        System.out.printf("  平均耗时：%.0fms%n",
                total > 0 ? (double) totalElapsedMs / total : 0);
        System.out.println("  结论：慢任务是队列堆积的直接根因，优化「慢SQL查询」「HTTP超时」「大文件IO」优先级最高");
    }

    void gracefulShutdown(long timeout, TimeUnit unit) {
        shutdown();
        try {
            if (!awaitTermination(timeout, unit)) {
                shutdownNow();
            }
        } catch (InterruptedException e) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

/** 携带名称的 Runnable，方便在钩子中识别任务来源 */
class NamedRunnable implements Runnable {
    final String name;
    private final Runnable action;

    NamedRunnable(String name, Runnable action) {
        this.name = name;
        this.action = action;
    }

    @Override
    public void run() {
        action.run();
    }
}
