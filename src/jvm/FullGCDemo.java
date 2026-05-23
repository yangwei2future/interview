package jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高并发触发频繁 Full GC Demo
 *
 * 推荐启动参数：
 *   java -Xms256m -Xmx256m -XX:+UseSerialGC "-Xlog:gc*:stdout:time" -cp out jvm.FullGCDemo
 *
 * 观察命令（另开终端）：
 *   jstat -gc <pid> 500
 */
public class FullGCDemo {

    // 模拟缓存：静态集合持有对象，保证对象晋升到老年代
    static List<byte[]> oldGenCache = new ArrayList<>();

    private static final AtomicInteger gcCount = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        System.out.println("进程 PID: " + ProcessHandle.current().pid());
        System.out.println("开始模拟高并发 Full GC...");
        System.out.println("用 jstat -gc <pid> 500 观察 FGC 列");
        System.out.println();

        // 线程1：模拟业务请求，持续创建短命对象（触发 Minor GC）
        ExecutorService pool = Executors.newFixedThreadPool(50);
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                while (true) {
                    // 大量创建短命对象，快速填满 Eden，触发 Minor GC
                    List<byte[]> tmp = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        tmp.add(new byte[10 * 1024]); // 10KB × 100 = 1MB
                    }
                    // tmp 出作用域后对象死亡，但频繁创建会让 Minor GC 不断触发
                }
            });
        }

        // 线程2：模拟对象晋升老年代（静态引用，GC 回收不掉）
        Thread oldGenThread = new Thread(() -> {
            while (true) {
                try {
                    // 每次往静态集合加 1MB，保证对象进老年代
                    synchronized (oldGenCache) {
                        oldGenCache.add(new byte[1024 * 1024]); // 1MB
                    }

                    int count = gcCount.incrementAndGet();
                    Runtime rt = Runtime.getRuntime();
                    long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                    long totalMB = rt.totalMemory() / 1024 / 1024;
                    System.out.printf("已累积 %dMB 到老年代，堆使用: %dMB/%dMB%n",
                            count, usedMB, totalMB);

                    Thread.sleep(100); // 每 100ms 加 1MB
                } catch (InterruptedException e) {
                    break;
                } catch (OutOfMemoryError e) {
                    System.out.println("OOM！堆已满，JVM 即将崩溃");
                    throw e; // 不兜底，直接崩
                }
            }
        }, "old-gen-filler");
        oldGenThread.setDaemon(false);
        oldGenThread.start();

        oldGenThread.join();
    }
}
