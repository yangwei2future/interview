package jvm;

/**
 * CPU 100% 模拟 Demo
 *
 * 包含两种场景：
 * 1. 死循环（最常见的 CPU 100% 原因）
 * 2. 死锁（线程互相等待，CPU 不高但线程卡死）
 *
 * 排查步骤：
 * 方式一（Arthas）：
 *   java -jar arthas-boot.jar
 *   thread -n 3          → 直接看 CPU 最高的线程和堆栈
 *   thread -b            → 直接找死锁
 *
 * 方式二（原始命令）：
 *   jps -l               → 找到进程 PID
 *   top -Hp <pid>        → 找到 CPU 最高的线程 TID（十进制）
 *   printf "%x\n" <tid>  → TID 转十六进制
 *   jstack <pid> | grep -A 30 "<tid十六进制>"  → 找到堆栈
 */
public class CpuHighDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("进程启动，PID: " + ProcessHandle.current().pid());
        System.out.println("选择场景：");
        System.out.println("  1. 死循环（会导致 CPU 100%）");
        System.out.println("  2. 死锁（线程卡死，CPU 不高）");
        System.out.println("默认 3 秒后启动死循环场景...");

        Thread.sleep(3000);

        // 场景一：启动一个死循环线程
        startInfiniteLoop();

        // 场景二：启动死锁
        startDeadLock();

        // 主线程保持运行
        Thread.sleep(Long.MAX_VALUE);
    }

    // ==================== 场景一：死循环 ====================

    static void startInfiniteLoop() {
        Thread t = new Thread(() -> {
            System.out.println("[死循环线程] 启动，线程名: " + Thread.currentThread().getName());
            long count = 0;
            // 模拟死循环：没有终止条件，CPU 会被这个线程吃满
            while (true) {
                count++;
                // 每隔一段时间打印，证明线程一直在跑
                if (count % 100_000_000 == 0) {
                    System.out.println("[死循环线程] 已执行 " + count + " 次");
                }
            }
        }, "infinite-loop-thread");
        t.setDaemon(false);
        t.start();
    }

    // ==================== 场景二：死锁 ====================

    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    static void startDeadLock() {
        // 线程1：先拿 A 锁，再拿 B 锁
        Thread t1 = new Thread(() -> {
            synchronized (LOCK_A) {
                System.out.println("[死锁线程1] 拿到了 LOCK_A，等待 LOCK_B...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                synchronized (LOCK_B) {
                    System.out.println("[死锁线程1] 拿到了 LOCK_B");
                }
            }
        }, "deadlock-thread-1");

        // 线程2：先拿 B 锁，再拿 A 锁（和线程1相反，必然死锁）
        Thread t2 = new Thread(() -> {
            synchronized (LOCK_B) {
                System.out.println("[死锁线程2] 拿到了 LOCK_B，等待 LOCK_A...");
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                synchronized (LOCK_A) {
                    System.out.println("[死锁线程2] 拿到了 LOCK_A");
                }
            }
        }, "deadlock-thread-2");

        t1.start();
        t2.start();
        System.out.println("[死锁] 两个线程已启动，互相等待，已死锁");
    }
}
