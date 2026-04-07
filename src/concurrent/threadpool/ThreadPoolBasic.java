package concurrent.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池基础示例
 * 知识点：
 *   1. ThreadPoolExecutor 七个核心参数
 *   2. 四种拒绝策略
 *   3. 线程池工作流程
 */
public class ThreadPoolBasic {

    public static void main(String[] args) throws InterruptedException {
        demo1_sevenParams();
        demo2_rejectPolicies();
        demo3_workflowVerification();
    }

    // ---------------------------------------------------------------
    // DEMO 1: 七个核心参数
    // ---------------------------------------------------------------
    static void demo1_sevenParams() throws InterruptedException {
        System.out.println("\n===== DEMO1: 七个核心参数 =====");

        /*
         * 参数说明：
         *
         * 1. corePoolSize     核心线程数
         *    - 线程池维持的最少线程数（即使空闲也不销毁）
         *    - 除非设置 allowCoreThreadTimeOut = true
         *
         * 2. maximumPoolSize  最大线程数
         *    - 队列满后才会创建超出 core 的线程
         *    - 非核心线程空闲超过 keepAliveTime 后销毁
         *
         * 3. keepAliveTime    非核心线程存活时间
         *
         * 4. unit             keepAliveTime 的时间单位
         *
         * 5. workQueue        任务队列（阻塞队列）
         *    - ArrayBlockingQueue  有界队列，需指定容量
         *    - LinkedBlockingQueue 无界队列（默认 Integer.MAX_VALUE）
         *    - SynchronousQueue    不存储，直接交接
         *    - PriorityBlockingQueue 带优先级
         *
         * 6. threadFactory    线程工厂（创建线程的方式，可自定义名称）
         *
         * 7. handler          拒绝策略（队列满 + 线程数达到 max 时触发）
         *    - AbortPolicy        抛异常（默认）
         *    - CallerRunsPolicy   调用者自己跑
         *    - DiscardPolicy      静默丢弃
         *    - DiscardOldestPolicy 丢弃队列最老的任务
         */
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                                      // corePoolSize
                4,                                      // maximumPoolSize
                60L,                                    // keepAliveTime
                TimeUnit.SECONDS,                       // unit
                new ArrayBlockingQueue<>(10),           // workQueue
                new NamedThreadFactory("demo1"),        // threadFactory
                new ThreadPoolExecutor.AbortPolicy()    // handler
        );

        for (int i = 1; i <= 5; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.printf("[%s] 执行任务 %d%n",
                        Thread.currentThread().getName(), taskId);
                sleep(100);
            });
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("DEMO1 完成");
    }

    // ---------------------------------------------------------------
    // DEMO 2: 四种拒绝策略对比
    // ---------------------------------------------------------------
    static void demo2_rejectPolicies() throws InterruptedException {
        System.out.println("\n===== DEMO2: 四种拒绝策略 =====");

        // 制造拒绝条件：core=1, max=2, queue=1，提交4个任务必然触发拒绝
        RejectedExecutionHandler[] policies = {
                new ThreadPoolExecutor.AbortPolicy(),         // 抛异常
                new ThreadPoolExecutor.CallerRunsPolicy(),    // 调用者执行
                new ThreadPoolExecutor.DiscardPolicy(),       // 静默丢弃
                new ThreadPoolExecutor.DiscardOldestPolicy()  // 丢最老的
        };
        String[] names = {"AbortPolicy", "CallerRunsPolicy", "DiscardPolicy", "DiscardOldestPolicy"};

        for (int p = 0; p < policies.length; p++) {
            System.out.println("\n-- 策略: " + names[p] + " --");
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    1, 2, 60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    new NamedThreadFactory(names[p]),
                    policies[p]
            );
            for (int i = 1; i <= 4; i++) {
                int taskId = i;
                try {
                    pool.execute(() -> {
                        System.out.printf("  [%s] 执行任务 %d%n",
                                Thread.currentThread().getName(), taskId);
                        sleep(200);
                    });
                    System.out.println("  任务 " + taskId + " 提交成功");
                } catch (RejectedExecutionException e) {
                    System.out.println("  任务 " + taskId + " 被拒绝: " + e.getMessage());
                }
            }
            pool.shutdown();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------
    // DEMO 3: 工作流程验证
    // 流程：core 未满 → 直接创建核心线程
    //       core 已满 → 放入队列
    //       队列已满 → 创建非核心线程（不超过 max）
    //       max 已满 → 触发拒绝策略
    // ---------------------------------------------------------------
    static void demo3_workflowVerification() throws InterruptedException {
        System.out.println("\n===== DEMO3: 工作流程验证 =====");

        AtomicInteger rejectedCount = new AtomicInteger(0);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                new NamedThreadFactory("flow"),
                (r, executor) -> {
                    rejectedCount.incrementAndGet();
                    System.out.println("  [拒绝] 任务被拒绝，当前线程数=" + executor.getPoolSize()
                            + " 队列大小=" + executor.getQueue().size());
                }
        );

        // 提交 9 个任务：core=2, queue=2, max=4，第9个必定被拒绝
        for (int i = 1; i <= 9; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.printf("  [%s] 任务%d 开始，当前线程数=%d%n",
                        Thread.currentThread().getName(), taskId, pool.getPoolSize());
                sleep(300);
            });
            System.out.printf("提交任务%d 后：线程数=%d 队列=%d%n",
                    i, pool.getPoolSize(), pool.getQueue().size());
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("被拒绝总数: " + rejectedCount.get());
    }

    // ---------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------
    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

/**
 * 自定义线程工厂（面试必考：给线程起有意义的名字）
 */
class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger count = new AtomicInteger(1);

    NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-thread-" + count.getAndIncrement());
        t.setDaemon(false);  // 非守护线程，JVM 不会强制退出
        return t;
    }
}
