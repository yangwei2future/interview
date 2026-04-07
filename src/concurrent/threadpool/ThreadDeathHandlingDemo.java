package concurrent.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池中线程挂了怎么处理
 *
 * 核心结论：
 *   execute() → 异常导致线程销毁，线程池自动补新线程，但异常默认只打 stderr
 *   submit()  → 异常被包进 Future，线程不销毁，但不 get() 异常永远丢失
 *
 * 四种处理手段（由浅到深）：
 *   DEMO 1 - 观察线程死亡与自动补充
 *   DEMO 2 - execute 用 UncaughtExceptionHandler 捞异常
 *   DEMO 3 - submit 用 afterExecute 钩子捞 Future 里的异常
 *   DEMO 4 - 生产最佳实践：任务内部 try-catch，彻底不让线程挂
 */
public class ThreadDeathHandlingDemo {

    public static void main(String[] args) throws Exception {
        demo1_observeThreadDeath();
        demo2_uncaughtExceptionHandler();
        demo3_afterExecuteCatchFutureException();
        demo4_bestPracticeNeverLetThreadDie();
    }

    // ---------------------------------------------------------------
    // DEMO 1: 观察线程死亡 + 线程池自动补充
    //
    // execute() 提交的任务抛出异常后：
    //   1. 该线程被销毁（从线程池移除）
    //   2. 线程池发现线程数 < corePoolSize，自动创建新线程补上
    //   3. 线程名会变（新线程编号递增），说明是新创建的
    //
    // 代价：每次线程死亡都要重新创建，有 CPU/内存 开销
    //       如果任务频繁抛异常，线程池会频繁销毁/重建线程
    // ---------------------------------------------------------------
    static void demo1_observeThreadDeath() throws InterruptedException {
        System.out.println("\n===== DEMO 1: 观察线程死亡与自动补充 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("demo1")
        );

        for (int i = 1; i <= 6; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.printf("  任务%d → 线程[%s]%n", taskId, Thread.currentThread().getName());
                if (taskId % 2 == 0) {
                    // 偶数任务故意抛异常，模拟线程挂掉
                    throw new RuntimeException("任务" + taskId + " 崩了");
                    // 执行到这里后，该线程被销毁，线程池立刻创建新线程
                }
            });
            Thread.sleep(150);
        }

        Thread.sleep(300);
        // 观察：线程池大小仍是 2，但线程名的编号变了（说明是新建的线程）
        System.out.println("  线程池大小仍为: " + pool.getPoolSize());
        System.out.println("  → 线程死了会自动补，但频繁死亡意味着频繁创建，有性能代价");
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 2: execute() 场景 — UncaughtExceptionHandler 捞异常
    //
    // execute() 抛出的异常走 UncaughtExceptionHandler。
    // 如果不设置，异常只打到 stderr，生产上可能根本看不到！
    //
    // 正确做法：在 ThreadFactory 里为每个线程设置 UncaughtExceptionHandler，
    //           把异常交给 logger，确保进业务日志，方便排查。
    // ---------------------------------------------------------------
    static void demo2_uncaughtExceptionHandler() throws InterruptedException {
        System.out.println("\n===== DEMO 2: UncaughtExceptionHandler 捞 execute 的异常 =====");

        AtomicInteger recoveredCount = new AtomicInteger(0);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("handler-thread-" + t.getId());
                    t.setUncaughtExceptionHandler((thread, ex) -> {
                        // 生产这里换成 log.error(...)，确保异常进业务日志
                        System.err.printf("  [UncaughtHandler] 线程[%s] 异常: %s → 已上报告警%n",
                                thread.getName(), ex.getMessage());
                        recoveredCount.incrementAndGet();
                    });
                    return t;
                }
        );

        pool.execute(() -> { throw new RuntimeException("NPE：用户 ID 为空"); });
        pool.execute(() -> { throw new RuntimeException("超时：下游接口 3s 无响应"); });
        pool.execute(() -> System.out.println("  正常任务正常执行"));

        Thread.sleep(300);
        System.out.println("  共捕获异常线程数: " + recoveredCount.get());
        System.out.println("  → 线程虽然死了，但异常被感知、记录，不会悄悄丢失");
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 3: submit() 场景 — afterExecute 钩子捞 Future 里的异常
    //
    // submit() 不会让线程死亡，但异常被包进 Future 对象。
    // 如果调用方没有 get()，异常永远丢失，这是生产上最隐蔽的坑。
    //
    // 解法：重写 afterExecute，主动检查 Future 是否有异常，统一捞出来打日志。
    // ---------------------------------------------------------------
    static void demo3_afterExecuteCatchFutureException() throws InterruptedException {
        System.out.println("\n===== DEMO 3: afterExecute 钩子捞 submit 的异常 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()
        ) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);

                // execute() 的异常直接通过参数 t 传入
                if (t != null) {
                    System.err.println("  [afterExecute] execute 异常: " + t.getMessage());
                }

                // submit() 的异常藏在 Future 里，需要主动 get() 才能拿到
                if (t == null && r instanceof Future<?> future) {
                    try {
                        if (future.isDone()) future.get();
                    } catch (ExecutionException ee) {
                        System.err.println("  [afterExecute] submit Future 异常: "
                                + ee.getCause().getMessage() + " → 线程没死，但异常被捞出来了");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        // submit 提交的任务，异常被 Future 包裹，线程不会死
        pool.submit(() -> { throw new RuntimeException("数据库连接超时"); });
        pool.submit(() -> { throw new RuntimeException("反序列化失败"); });

        Thread.sleep(300);
        System.out.println("  → 线程没死，异常通过 afterExecute 钩子被捞出");
        pool.shutdown();
    }

    // ---------------------------------------------------------------
    // DEMO 4: 生产最佳实践 — 任务内部 try-catch，彻底不让线程死
    //
    // 以上 DEMO 都是在"线程死了之后"补救。
    // 最好的办法是：根本不让线程死。
    //
    // 做法：在提交任务时统一包一层 try-catch，任务内部异常自己消化：
    //   - 打日志记录业务现场
    //   - 上报监控告警
    //   - 线程不死，线程池不需要重建线程，性能最优
    //
    // 这也是 EnterpriseThreadPool 的 submit/execute 包装层做的事。
    // ---------------------------------------------------------------
    static void demo4_bestPracticeNeverLetThreadDie() throws InterruptedException {
        System.out.println("\n===== DEMO 4: 最佳实践 — 任务内部 try-catch，线程不死 =====");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("safe")
        );

        // 用 safeExecute 包装，保证任务无论如何不会让线程挂
        for (int i = 1; i <= 4; i++) {
            int taskId = i;
            pool.execute(safeWrap("task-" + taskId, () -> {
                System.out.printf("  任务%d → 线程[%s]%n", taskId, Thread.currentThread().getName());
                if (taskId % 2 == 0) {
                    throw new RuntimeException("任务" + taskId + " 内部异常");
                }
            }));
            Thread.sleep(100);
        }

        Thread.sleep(300);
        System.out.println("  线程池大小: " + pool.getPoolSize() + "（始终是 2，没有线程死亡重建）");
        System.out.println("  已完成任务: " + pool.getCompletedTaskCount());
        System.out.println("  → 线程一直活着，异常在 safeWrap 里被消化，不影响线程生命周期");
        pool.shutdown();
    }

    /**
     * 任务安全包装：异常在内部消化，线程不死
     * 对应 EnterpriseThreadPool 中 execute(taskName, runnable) 的实现思路
     */
    private static Runnable safeWrap(String taskName, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                // 生产这里换成 log.error + alertService.send(...)
                System.err.printf("  [SafeWrap] task=%s 异常: %s → 已记录，线程继续存活%n",
                        taskName, e.getMessage());
            }
        };
    }
}
