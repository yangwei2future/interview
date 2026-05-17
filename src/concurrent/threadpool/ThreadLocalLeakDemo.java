package concurrent.threadpool;

import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ThreadLocal 内存泄漏演示
 *
 * 泄漏条件：
 * 1. ThreadLocal 对象用完就丢（没 static 长期持有）
 * 2. 线程池复用，线程不死
 *
 * 结果：key 被 GC 收走 → value 卡在 ThreadLocalMap → 越积越多
 */
public class ThreadLocalLeakDemo {

    public static void main(String[] args) throws Exception {
        // 固定 1 个线程的线程池，方便看同一个线程身上的脏 Entry 堆积
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        // 先提交 3 次任务，每次 new 新的 ThreadLocal（用完丢钥匙）
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.execute(() -> {
                // ❌ 每次在方法里 new ThreadLocal，用完钥匙就丢了
                ThreadLocal<byte[]> local = new ThreadLocal<>();
                local.set(new byte[10 * 1024 * 1024]); // 10MB 大对象，方便观察
                printThreadLocalMap("第" + idx + "次 任务执行中");
                // ← 方法结束，local 出栈，ThreadLocal 对象没人引用了
            });
        }

        TimeUnit.SECONDS.sleep(2); // 等任务跑完 + 等 GC

        // 手动触发 GC（让 key 被回收）
        System.gc();
        TimeUnit.SECONDS.sleep(1);

        // 再看一次：key 没了，value 还在！
        pool.execute(() -> {
            printThreadLocalMap("GC 之后");
        });

        pool.shutdown();
    }

    /**
     * 反射挖出 Thread 身上的 ThreadLocalMap，看里面的 Entry
     */
    private static void printThreadLocalMap(String phase) {
        try {
            Thread t = Thread.currentThread();
            Field field = Thread.class.getDeclaredField("threadLocals");
            field.setAccessible(true);
            Object map = field.get(t);
            if (map == null) {
                System.out.println("[" + phase + "] threadLocals = null");
                return;
            }

            Field tableField = map.getClass().getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(map);
            if (table == null) {
                System.out.println("[" + phase + "] table = null");
                return;
            }

            int aliveCount = 0;
            int dirtyCount = 0;
            for (Object entry : table) {
                if (entry == null) continue;
                // Entry extends WeakReference<ThreadLocal<?>>
                // referent = ThreadLocal 对象（key）
                Field referentField = java.lang.ref.Reference.class.getDeclaredField("referent");
                referentField.setAccessible(true);
                Object key = referentField.get(entry);

                Field valueField = entry.getClass().getDeclaredField("value");
                valueField.setAccessible(true);
                Object value = valueField.get(entry);

                if (key == null) {
                    dirtyCount++;
                    System.out.printf("  [脏格子] key=null, value=%s (%.2f MB)%n",
                            value != null ? value.getClass().getSimpleName() : "null",
                            value instanceof byte[] b ? b.length / 1024.0 / 1024.0 : 0);
                } else {
                    aliveCount++;
                    System.out.printf("  [正常]   key=%s%n", key.getClass().getSimpleName());
                }
            }

            System.out.printf("[%s] table.length=%d, 正常=%d, 脏格子=%d%n%n",
                    phase, table.length, aliveCount, dirtyCount);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
