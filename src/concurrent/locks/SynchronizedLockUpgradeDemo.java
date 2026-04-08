package concurrent.locks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * synchronized 锁升级演示
 *
 * DEMO 1 - 偏向锁场景：单线程重复进入同步块
 * DEMO 2 - 轻量级锁场景：多线程交替执行（无真正并发）
 * DEMO 3 - 重量级锁场景：多线程激烈竞争
 * DEMO 4 - 锁对象 vs 锁 Class 的区别
 * DEMO 5 - 结合项目：高并发计数器的正确姿势
 */
public class SynchronizedLockUpgradeDemo {

    // ==================== DEMO 1：偏向锁 ====================

    static class BiasedLockDemo {
        private final Object lock = new Object();
        private int count = 0;

        /**
         * 单线程反复进入同步块 → 偏向锁
         * Mark Word 记录线程 ID，后续进入只做 ID 比较，无 CAS
         */
        void run() throws InterruptedException {
            long start = System.nanoTime();
            for (int i = 0; i < 1_000_000; i++) {
                increment();
            }
            long cost = System.nanoTime() - start;
            System.out.printf("[DEMO 1] 偏向锁 - 单线程 100万次，count=%d，耗时=%d ms%n",
                    count, TimeUnit.NANOSECONDS.toMillis(cost));
        }

        private synchronized void increment() {
            count++;
        }
    }

    // ==================== DEMO 2：轻量级锁 ====================

    static class ThinLockDemo {
        private final Object lock = new Object();
        private int count = 0;

        /**
         * 两个线程交替执行，无真正并发竞争 → 轻量级锁（CAS）
         * 每次 CAS 写 Mark Word，但不阻塞线程
         */
        void run() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(2);

            Thread t1 = new Thread(() -> {
                for (int i = 0; i < 100_000; i++) {
                    synchronized (lock) { count++; }
                    // 主动 yield，让 t2 有机会执行，模拟交替
                    Thread.yield();
                }
                latch.countDown();
            }, "t1");

            Thread t2 = new Thread(() -> {
                for (int i = 0; i < 100_000; i++) {
                    synchronized (lock) { count++; }
                    Thread.yield();
                }
                latch.countDown();
            }, "t2");

            long start = System.nanoTime();
            t1.start();
            t2.start();
            latch.await();
            long cost = System.nanoTime() - start;
            System.out.printf("[DEMO 2] 轻量级锁 - 两线程交替，count=%d，耗时=%d ms%n",
                    count, TimeUnit.NANOSECONDS.toMillis(cost));
        }
    }

    // ==================== DEMO 3：重量级锁 ====================

    static class HeavyLockDemo {
        private final Object lock = new Object();
        private int count = 0;

        /**
         * 多线程激烈竞争 → 轻量级锁 CAS 自旋失败 → 升级重量级锁
         * 未获锁线程进入 EntryList 阻塞（OS Mutex，涉及内核态切换）
         */
        void run() throws InterruptedException {
            int threadCount = 8;
            CountDownLatch latch = new CountDownLatch(threadCount);

            long start = System.nanoTime();
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    for (int j = 0; j < 100_000; j++) {
                        synchronized (lock) { count++; }
                    }
                    latch.countDown();
                }).start();
            }
            latch.await();
            long cost = System.nanoTime() - start;
            System.out.printf("[DEMO 3] 重量级锁 - %d线程激烈竞争，count=%d，耗时=%d ms%n",
                    threadCount, count, TimeUnit.NANOSECONDS.toMillis(cost));
        }
    }

    // ==================== DEMO 4：锁对象 vs 锁 Class ====================

    static class LockScopeDemo {
        private static int staticCount = 0;
        private int instanceCount = 0;

        /**
         * synchronized(this) → 锁的是当前实例，不同实例互不干扰
         * synchronized(Class) → 锁的是类对象，所有实例共享同一把锁
         */
        synchronized void instanceMethod() {
            // 等价于 synchronized(this)
            instanceCount++;
        }

        static synchronized void staticMethod() {
            // 等价于 synchronized(LockScopeDemo.class)
            staticCount++;
        }

        void run() throws InterruptedException {
            LockScopeDemo obj1 = new LockScopeDemo();
            LockScopeDemo obj2 = new LockScopeDemo();

            // obj1 和 obj2 锁的是不同对象，可以并发执行
            CountDownLatch latch = new CountDownLatch(2);
            new Thread(() -> { for (int i = 0; i < 10000; i++) obj1.instanceMethod(); latch.countDown(); }).start();
            new Thread(() -> { for (int i = 0; i < 10000; i++) obj2.instanceMethod(); latch.countDown(); }).start();
            latch.await();

            System.out.printf("[DEMO 4] 锁对象：obj1.count=%d, obj2.count=%d（互不干扰）%n",
                    obj1.instanceCount, obj2.instanceCount);
            System.out.println("[DEMO 4] 注意：如果改成 staticMethod，则两者共享一把锁，count 可能出错");
        }
    }

    // ==================== DEMO 5：高并发计数器的正确姿势 ====================

    /**
     * 结合简历场景：API 日均百万调用，统计调用次数
     *
     * 方案对比：
     * - synchronized 计数：重量级锁，高并发下吞吐低
     * - AtomicLong：CAS 无锁，适合低竞争
     * - LongAdder：分段累加，高竞争下吞吐最高（推荐）
     */
    static class ApiCounterDemo {
        private long synchronizedCount = 0;
        private final java.util.concurrent.atomic.AtomicLong atomicCount = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.LongAdder adderCount = new java.util.concurrent.atomic.LongAdder();

        void run() throws InterruptedException {
            int threadCount = 16;
            int loopCount = 100_000;

            // synchronized 计数
            synchronizedCount = 0;
            CountDownLatch l1 = new CountDownLatch(threadCount);
            long t1 = System.nanoTime();
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    for (int j = 0; j < loopCount; j++) {
                        synchronized (this) { synchronizedCount++; }
                    }
                    l1.countDown();
                }).start();
            }
            l1.await();
            long cost1 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

            // AtomicLong 计数
            CountDownLatch l2 = new CountDownLatch(threadCount);
            long t2 = System.nanoTime();
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    for (int j = 0; j < loopCount; j++) atomicCount.incrementAndGet();
                    l2.countDown();
                }).start();
            }
            l2.await();
            long cost2 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t2);

            // LongAdder 计数
            CountDownLatch l3 = new CountDownLatch(threadCount);
            long t3 = System.nanoTime();
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    for (int j = 0; j < loopCount; j++) adderCount.increment();
                    l3.countDown();
                }).start();
            }
            l3.await();
            long cost3 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t3);

            System.out.printf("[DEMO 5] %d线程 x %d次 计数对比：%n", threadCount, loopCount);
            System.out.printf("  synchronized : count=%d, 耗时=%d ms%n", synchronizedCount, cost1);
            System.out.printf("  AtomicLong   : count=%d, 耗时=%d ms%n", atomicCount.get(), cost2);
            System.out.printf("  LongAdder    : count=%d, 耗时=%d ms%n", adderCount.sum(), cost3);
            System.out.println("  结论：高并发计数推荐 LongAdder，读多写少推荐 AtomicLong");
        }
    }

    // ==================== 入口 ====================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("===== DEMO 1：偏向锁（单线程重复加锁）=====");
        new BiasedLockDemo().run();

        System.out.println("\n===== DEMO 2：轻量级锁（多线程交替执行）=====");
        new ThinLockDemo().run();

        System.out.println("\n===== DEMO 3：重量级锁（多线程激烈竞争）=====");
        new HeavyLockDemo().run();

        System.out.println("\n===== DEMO 4：锁对象 vs 锁 Class =====");
        new LockScopeDemo().run();

        System.out.println("\n===== DEMO 5：高并发计数器 - synchronized vs AtomicLong vs LongAdder =====");
        new ApiCounterDemo().run();
    }
}
