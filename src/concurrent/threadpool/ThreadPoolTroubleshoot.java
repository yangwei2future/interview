package concurrent.threadpool;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池问题排查
 * 知识点：
 *   1. 如何判断线程池是否健康（getPoolSize / getActiveCount / getQueue）
 *   2. 线程泄漏排查（任务挂死、死锁）
 *   3. 任务堆积排查
 *   4. jstack 分析思路（代码模拟）
 *   5. 线程池调优经验公式
 */
public class ThreadPoolTroubleshoot {

    public static void main(String[] args) throws Exception {
        demo1_healthCheck();
        demo2_queueBacklog();
        demo3_threadLeak();
        demo4_deadlock();
        demo5_tuningFormula();
    }

    // ---------------------------------------------------------------
    // DEMO 1: 线程池健康检查（监控指标）
    // ---------------------------------------------------------------
    static void demo1_healthCheck() throws InterruptedException {
        System.out.println("\n===== DEMO1: 线程池健康检查 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                new NamedThreadFactory("health")
        );

        // 提交一批任务
        for (int i = 0; i < 8; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Thread.sleep(100);
        printPoolHealth(pool, "运行中");

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        printPoolHealth(pool, "关闭后");
    }

    static void printPoolHealth(ThreadPoolExecutor pool, String label) {
        System.out.printf("""
                [%s] 线程池状态：
                  核心线程数: %d / 最大线程数: %d
                  当前线程数: %d / 活跃线程数: %d
                  队列中任务: %d / 队列容量剩余: %d
                  已完成任务: %d / 累计提交任务: %d
                  是否关闭: %s
                %n""",
                label,
                pool.getCorePoolSize(), pool.getMaximumPoolSize(),
                pool.getPoolSize(), pool.getActiveCount(),
                pool.getQueue().size(), pool.getQueue().remainingCapacity(),
                pool.getCompletedTaskCount(), pool.getTaskCount(),
                pool.isShutdown()
        );
    }

    // ---------------------------------------------------------------
    // DEMO 2: 任务堆积排查
    // 现象：队列持续增长，活跃线程数长期等于 max
    // 原因：任务生产速度 >> 消费速度
    // ---------------------------------------------------------------
    static void demo2_queueBacklog() throws InterruptedException {
        System.out.println("\n===== DEMO2: 任务堆积排查 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),  // 无界队列，方便演示堆积
                new NamedThreadFactory("backlog")
        );

        AtomicInteger rejected = new AtomicInteger(0);

        // 生产者：快速提交大量慢任务
        for (int i = 0; i < 50; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(200); // 每个任务 200ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 监控队列变化
        for (int i = 0; i < 5; i++) {
            Thread.sleep(100);
            System.out.printf("  [监控] 队列堆积=%d 活跃线程=%d 已完成=%d%n",
                    pool.getQueue().size(),
                    pool.getActiveCount(),
                    pool.getCompletedTaskCount());
        }

        System.out.println("""
                  排查思路：
                  1. 看队列大小是否持续增长（getQueue().size()）
                  2. 活跃线程是否长期等于 max（getActiveCount() == maxPoolSize）
                  3. 可能原因：任务中有慢查询/外部调用超时/死锁
                  4. 解决：加大线程数、减少任务耗时、加限流
                """);

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------
    // DEMO 3: 线程泄漏（任务永远不结束）
    // 现象：活跃线程数持续等于 max，任务完成数不增长
    // ---------------------------------------------------------------
    static void demo3_threadLeak() throws InterruptedException {
        System.out.println("\n===== DEMO3: 线程泄漏（任务永不结束）=====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("leak")
        );

        // 模拟任务泄漏：忘记处理 InterruptedException，线程永远等待
        pool.execute(() -> {
            System.out.println("  泄漏任务开始，将永远阻塞...");
            try {
                // 错误写法：捕获后什么都不做，导致线程永远阻塞
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // ❌ 错误：吞掉中断信号，线程无法被停止
                System.out.println("  收到中断但被忽略，继续阻塞");
                // ✅ 正确做法：Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(200);
        System.out.printf("  活跃线程: %d（本应为0，说明有线程泄漏）%n", pool.getActiveCount());

        System.out.println("""
                  排查步骤：
                  1. jstack <pid> 查看线程栈，找 WAITING/BLOCKED 的业务线程
                  2. 看线程在等什么（wait on / locked <...>）
                  3. 常见原因：
                     - 外部 HTTP/DB 调用没有超时设置
                     - 吞掉 InterruptedException 导致无法中断
                     - 循环里的条件永不满足
                  4. 解决：给所有外部调用加超时；正确处理 InterruptedException
                """);

        pool.shutdownNow(); // 发送中断信号
        pool.awaitTermination(1, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------
    // DEMO 4: 死锁检测（jstack 可以直接检测 deadlock）
    // ---------------------------------------------------------------
    static void demo4_deadlock() throws InterruptedException {
        System.out.println("\n===== DEMO4: 死锁检测 =====");

        Object lockA = new Object();
        Object lockB = new Object();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // 线程1：先锁A再锁B
        pool.execute(() -> {
            synchronized (lockA) {
                System.out.println("  线程1 持有 lockA，等待 lockB");
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                synchronized (lockB) {
                    System.out.println("  线程1 获得 lockB（不会打印，因为死锁）");
                }
            }
        });

        // 线程2：先锁B再锁A
        pool.execute(() -> {
            synchronized (lockB) {
                System.out.println("  线程2 持有 lockB，等待 lockA");
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                synchronized (lockA) {
                    System.out.println("  线程2 获得 lockA（不会打印，因为死锁）");
                }
            }
        });

        Thread.sleep(500);

        // 用 ThreadMXBean 检测死锁
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = mxBean.findDeadlockedThreads();
        if (deadlockedIds != null) {
            System.out.println("  ⚠️  检测到死锁！涉及线程：");
            ThreadInfo[] infos = mxBean.getThreadInfo(deadlockedIds, true, true);
            for (ThreadInfo info : infos) {
                System.out.println("    线程名: " + info.getThreadName()
                        + " 状态: " + info.getThreadState()
                        + " 等待锁: " + info.getLockName());
            }
        }

        System.out.println("""
                  排查步骤：
                  1. jstack <pid> | grep -A 20 "deadlock"
                  2. 或用 jconsole / VisualVM 的死锁检测功能
                  3. 看哪两个线程互相持有对方需要的锁
                  4. 解决：统一加锁顺序；使用 tryLock 加超时；减少锁粒度
                """);

        pool.shutdownNow();
    }

    // ---------------------------------------------------------------
    // DEMO 5: 线程数调优公式
    // ---------------------------------------------------------------
    static void demo5_tuningFormula() {
        System.out.println("\n===== DEMO5: 线程数调优公式 =====");

        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("当前 CPU 核心数: " + cpuCores);

        // CPU 密集型：任务几乎不等待，核心数+1 即可
        int cpuBound = cpuCores + 1;
        System.out.println("CPU 密集型推荐线程数: " + cpuBound + "（CPU核+1）");

        // IO 密集型：线程大部分时间在等待 IO
        // 公式：线程数 = CPU核 * (1 + 等待时间/计算时间)
        // 假设 IO 等待占 90%，计算占 10%
        double waitRatio = 9.0; // 等待时间/计算时间
        int ioBound = (int) (cpuCores * (1 + waitRatio));
        System.out.println("IO 密集型推荐线程数: " + ioBound + "（CPU核 * (1 + 等待/计算)）");

        // 混合型：通过压测找最优值
        System.out.println("""

                调优实践建议：
                  1. 公式只是起点，生产需要压测验证（JMeter / wrk）
                  2. 监控指标：吞吐量、P99 延迟、CPU 使用率、GC 频率
                  3. 线程数不是越多越好：过多导致上下文切换开销增大
                  4. 核心参数调优顺序：
                     corePoolSize → 队列容量 → maximumPoolSize → keepAliveTime
                  5. 建议通过配置中心（Apollo/Nacos）动态调整，无需重启

                常用排查命令：
                  jps                           # 查看 Java 进程 PID
                  jstack <pid>                  # 打印所有线程栈
                  jstack <pid> | grep "pool"    # 过滤线程池相关线程
                  jstat -gcutil <pid> 1000      # 查看 GC 情况
                  top -H -p <pid>               # 查看各线程 CPU 使用
                """);
    }
}
