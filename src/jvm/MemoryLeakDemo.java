package jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存泄漏 Demo
 *
 * 包含三种常见场景：
 * 1. 静态集合无限增长
 * 2. ThreadLocal 没有 remove（配合线程池）
 * 3. 缓存没有淘汰机制
 *
 * 排查步骤：
 *   Arthas: dashboard → memory → heapdump /tmp/heap.hprof
 *   然后用 MAT 打开 heap.hprof，看 Leak Suspects 报告
 *
 * 启动参数（建议加上，OOM 时自动 dump）：
 *   java -Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -cp out jvm.MemoryLeakDemo
 */
public class MemoryLeakDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("进程启动，PID: " + ProcessHandle.current().pid());
        System.out.println("启动内存泄漏场景，用 Arthas memory 命令观察堆内存持续增长...");
        System.out.println("建议启动参数：-Xmx256m 限制堆大小，方便更快触发 OOM");
        System.out.println();

        // 场景一：静态集合无限增长
        startStaticListLeak();

        // 场景二：ThreadLocal 没有 remove
        startThreadLocalLeak();

        // 主线程保持运行
        Thread.sleep(Long.MAX_VALUE);
    }

    // ==================== 场景一：静态集合无限增长 ====================

    // 静态 List，生命周期和类一样长，一直往里加对象，永远不清理
    static List<byte[]> staticList = new ArrayList<>();

    static void startStaticListLeak() {
        Thread t = new Thread(() -> {
            int count = 0;
            while (true) {
                // 每次往静态 List 里加 1MB 数据，永远不删
                staticList.add(new byte[1024 * 1024]);
                count++;
                System.out.println("[静态集合泄漏] 已加入 " + count + "MB，当前堆已用: "
                        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB");
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }
        }, "static-list-leak-thread");
        t.setDaemon(false);
        t.start();
    }

    // ==================== 场景二：ThreadLocal 没有 remove ====================

    static ThreadLocal<byte[]> threadLocal = new ThreadLocal<>();

    static void startThreadLocalLeak() {
        // 模拟线程池：固定几个线程反复执行任务
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    // 模拟每次请求都往 ThreadLocal 放数据，但没有 remove
                    // 正确做法：finally 块里调用 threadLocal.remove()
                    threadLocal.set(new byte[1024 * 512]);  // 每次 set 512KB

                    // 模拟业务处理
                    try { Thread.sleep(200); } catch (InterruptedException e) {}

                    // ❌ 错误：没有 remove，value 一直留在 ThreadLocalMap 里
                    // ✅ 正确：finally { threadLocal.remove(); }
                }
            }, "threadlocal-leak-thread-" + i);
            t.setDaemon(false);
            t.start();
        }
        System.out.println("[ThreadLocal泄漏] 3个线程已启动，ThreadLocal 没有 remove");
    }
}
