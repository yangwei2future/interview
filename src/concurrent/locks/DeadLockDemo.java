package concurrent.locks;

/**
 * 死锁 Demo
 * 运行后用 Arthas thread -b 可以看到阻塞线程
 */
public class DeadLockDemo {

    private static final Object lockA = new Object();
    private static final Object lockB = new Object();

    public static void main(String[] args) {
        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                System.out.println("线程1 拿到 lockA，等待 lockB...");
                sleep(500);
                synchronized (lockB) {
                    System.out.println("线程1 拿到 lockB");
                }
            }
        }, "thread-1-持有A等B");

        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                System.out.println("线程2 拿到 lockB，等待 lockA...");
                sleep(500);
                synchronized (lockA) {
                    System.out.println("线程2 拿到 lockA");
                }
            }
        }, "thread-2-持有B等A");

        t1.start();
        t2.start();

        // 主线程保持存活，方便 Arthas attach
        sleep(999999);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
