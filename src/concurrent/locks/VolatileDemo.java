package concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * volatile 可见性 & 禁止指令重排 演示
 *
 * DEMO 1 - 可见性问题：没有 volatile，线程可能永远看不到修改
 * DEMO 2 - volatile 修复可见性
 * DEMO 3 - volatile 不保证原子性（count++ 仍然线程不安全）
 * DEMO 4 - DCL 单例：volatile 禁止指令重排
 * DEMO 5 - 结合项目：服务优雅关闭的 volatile 开关
 */
public class VolatileDemo {

    // ==================== DEMO 1：没有 volatile，可见性问题 ====================

    static class NoVolatileDemo {
        // 没有 volatile
        private boolean flag = false;

        void run() throws InterruptedException {
            Thread worker = new Thread(() -> {
                System.out.println("[DEMO 1] 工作线程启动，等待 flag 变为 true...");
                long start = System.currentTimeMillis();
                // JIT 编译后可能把 flag 缓存到寄存器，永远读不到主内存的修改
                while (!flag) {
                    // 空转，模拟等待
                }
                System.out.println("[DEMO 1] 工作线程感知到 flag=true，耗时 "
                        + (System.currentTimeMillis() - start) + " ms");
            });

            worker.setDaemon(true); // 设置为守护线程，避免主线程退出后卡住
            worker.start();

            TimeUnit.MILLISECONDS.sleep(100);
            System.out.println("[DEMO 1] 主线程设置 flag=true");
            flag = true;

            // 等待一会儿，如果工作线程没退出说明没感知到
            worker.join(1000);
            if (worker.isAlive()) {
                System.out.println("[DEMO 1] ⚠️  工作线程 1 秒后仍未感知到，可见性问题！");
                worker.interrupt();
            }
        }
    }

    // ==================== DEMO 2：加 volatile，可见性修复 ====================

    static class WithVolatileDemo {
        // 加上 volatile
        private volatile boolean flag = false;

        void run() throws InterruptedException {
            Thread worker = new Thread(() -> {
                System.out.println("[DEMO 2] 工作线程启动，等待 flag 变为 true...");
                long start = System.currentTimeMillis();
                while (!flag) {
                    // volatile 保证每次从主内存读
                }
                System.out.println("[DEMO 2] ✅ 工作线程感知到 flag=true，耗时 "
                        + (System.currentTimeMillis() - start) + " ms");
            });

            worker.start();
            TimeUnit.MILLISECONDS.sleep(100);
            System.out.println("[DEMO 2] 主线程设置 flag=true");
            flag = true;
            worker.join(1000);
        }
    }

    // ==================== DEMO 3：volatile 不保证原子性 ====================

    static class VolatileAtomicityDemo {
        private volatile int count = 0;
        private final AtomicInteger atomicCount = new AtomicInteger(0);

        /**
         * count++ 分三步：读 → 加1 → 写，volatile 不能保证三步原子
         * 多线程并发 count++，结果一定小于预期
         */
        void run() throws InterruptedException {
            int threadNum = 10;
            int loopCount = 10_000;

            // volatile count++（不安全）
            count = 0;
            Thread[] threads1 = new Thread[threadNum];
            for (int i = 0; i < threadNum; i++) {
                threads1[i] = new Thread(() -> {
                    for (int j = 0; j < loopCount; j++) count++; // 线程不安全！
                });
                threads1[i].start();
            }
            for (Thread t : threads1) t.join();

            // AtomicInteger（安全）
            atomicCount.set(0);
            Thread[] threads2 = new Thread[threadNum];
            for (int i = 0; i < threadNum; i++) {
                threads2[i] = new Thread(() -> {
                    for (int j = 0; j < loopCount; j++) atomicCount.incrementAndGet();
                });
                threads2[i].start();
            }
            for (Thread t : threads2) t.join();

            int expected = threadNum * loopCount;
            System.out.printf("[DEMO 3] 期望值=%d%n", expected);
            System.out.printf("[DEMO 3] volatile count++  实际=%d %s（不安全，结果偏小）%n",
                    count, count == expected ? "✅" : "❌");
            System.out.printf("[DEMO 3] AtomicInteger     实际=%d %s（安全）%n",
                    atomicCount.get(), atomicCount.get() == expected ? "✅" : "❌");
        }
    }

    // ==================== DEMO 4：DCL 单例 + volatile 禁止重排 ====================

    /**
     * 错误的 DCL：没有 volatile，new Singleton() 可能被重排
     * 导致其他线程拿到未初始化完的对象
     */
    static class UnsafeSingleton {
        private static UnsafeSingleton instance; // ❌ 没有 volatile

        private UnsafeSingleton() {
            // 模拟初始化耗时
        }

        public static UnsafeSingleton getInstance() {
            if (instance == null) {
                synchronized (UnsafeSingleton.class) {
                    if (instance == null) {
                        instance = new UnsafeSingleton();
                        // new 的三步：① 分配内存 ② 初始化 ③ 赋引用
                        // CPU 可能重排为 ①③② → 其他线程看到 instance != null，但对象还没初始化完
                    }
                }
            }
            return instance;
        }
    }

    /**
     * 正确的 DCL：加 volatile 禁止 new 的指令重排
     */
    static class SafeSingleton {
        private static volatile SafeSingleton instance; // ✅ volatile

        private SafeSingleton() {}

        public static SafeSingleton getInstance() {
            if (instance == null) {
                synchronized (SafeSingleton.class) {
                    if (instance == null) {
                        instance = new SafeSingleton();
                        // volatile 写之前插入 StoreStore 屏障，保证初始化完成后才赋引用
                    }
                }
            }
            return instance;
        }
    }

    static void demoDCL() {
        SafeSingleton s1 = SafeSingleton.getInstance();
        SafeSingleton s2 = SafeSingleton.getInstance();
        System.out.println("[DEMO 4] DCL 单例：s1 == s2 → " + (s1 == s2) + " ✅");
        System.out.println("[DEMO 4] 关键：instance 必须加 volatile，防止 new 指令重排导致拿到未初始化对象");
    }

    // ==================== DEMO 5：服务优雅关闭的 volatile 开关 ====================

    /**
     * 结合项目场景：API 服务优雅关闭
     *
     * 场景：数据服务平台有多个工作线程持续处理 API 请求，
     * 收到关闭信号时，需要让所有工作线程感知到并停止接收新任务。
     *
     * volatile 保证主线程修改 running=false 后，
     * 所有工作线程立刻从主内存读到最新值，不再处理新请求。
     */
    static class ApiServiceDemo {
        private volatile boolean running = true;  // 关键：volatile

        void startWorkers(int workerCount) {
            for (int i = 0; i < workerCount; i++) {
                int id = i;
                new Thread(() -> {
                    int processed = 0;
                    while (running) {  // 每次循环都从主内存读 running
                        // 模拟处理 API 请求
                        try { TimeUnit.MILLISECONDS.sleep(10); } catch (InterruptedException e) { break; }
                        processed++;
                    }
                    System.out.printf("[DEMO 5] Worker-%d 感知到关闭信号，共处理 %d 个请求%n", id, processed);
                }, "api-worker-" + i).start();
            }
        }

        void shutdown() {
            System.out.println("[DEMO 5] 收到关闭信号，设置 running=false...");
            running = false;  // 立刻对所有工作线程可见（volatile 写）
        }

        void run() throws InterruptedException {
            startWorkers(3);
            TimeUnit.MILLISECONDS.sleep(200); // 让工作线程跑一会儿
            shutdown();
            TimeUnit.MILLISECONDS.sleep(100); // 等工作线程退出
            System.out.println("[DEMO 5] 所有工作线程已停止，服务关闭完成 ✅");
        }
    }

    // ==================== 入口 ====================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("===== DEMO 1：没有 volatile - 可见性问题 =====");
        new NoVolatileDemo().run();

        System.out.println("\n===== DEMO 2：加 volatile - 可见性修复 =====");
        new WithVolatileDemo().run();

        System.out.println("\n===== DEMO 3：volatile 不保证原子性 =====");
        new VolatileAtomicityDemo().run();

        System.out.println("\n===== DEMO 4：DCL 单例 + volatile 禁止指令重排 =====");
        demoDCL();

        System.out.println("\n===== DEMO 5：结合项目 - 服务优雅关闭的 volatile 开关 =====");
        new ApiServiceDemo().run();
    }
}
