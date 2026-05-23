package jvm;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GC 调优对比基准测试
 *
 * 功能：用一个固定的压力负载，在不同 GC 策略下运行，自动采集性能指标。
 * 用法：切换 JVM 参数中的 -XX:+UseSerialGC / UseParallelGC / UseG1GC / UseZGC 对比效果。
 *
 * 推荐运行方式（请使用 java 源文件模式，无需编译）：
 *
 *   # 1. Serial GC（客户端模式）
 *   java -Xms256m -Xmx256m -XX:+UseSerialGC -Xlog:gc*:file=/tmp/gc-serial.log:time
 *        src/jvm/GCTuningBenchmark.java cache
 *
 *   # 2. Parallel GC（吞吐量优先）
 *   java -Xms256m -Xmx256m -XX:+UseParallelGC -Xlog:gc*:file=/tmp/gc-parallel.log:time
 *        src/jvm/GCTuningBenchmark.java cache
 *
 *   # 3. G1 GC（默认，平衡型）
 *   java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=/tmp/gc-g1.log:time
 *        src/jvm/GCTuningBenchmark.java cache
 *
 *   # 4. 新生代调大（减少 Minor GC 频率）
 *   java -Xms256m -Xmx256m -Xmn128m -XX:+UseG1GC -Xlog:gc*:file=/tmp/gc-g1-128m.log:time
 *        src/jvm/GCTuningBenchmark.java cache
 *
 *   # 5. 无缓存模式（模拟批处理：对象全部朝生夕死）
 *   java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=/tmp/gc-g1-transient.log:time
 *        src/jvm/GCTuningBenchmark.java transient
 *
 * 参数：
 *   cache     - 模拟 Web 应用：部分对象长期存活（缓存），部分短期（请求）
 *   transient - 模拟批处理：对象全部朝生夕死
 *
 * 运行后观察：
 *   1. 控制台输出的 Summary 数据
 *   2. GC 日志文件（用 less 或 cat 查看）
 *   3. 可以用 jps / jstat 实时观察（如果环境有 JDK 工具）
 */
public class GCTuningBenchmark {

    // ==================== 场景配置 ====================

    /** 运行时长（秒） */
    static final long RUN_DURATION_SECONDS = 30;
    /** 工作线程数 */
    static final int WORKER_THREADS = 8;
    /** 每轮分配的对象数（控制压力大小） */
    static final int ALLOC_PER_ROUND = 80;

    // ==================== 监控指标 ====================

    static final AtomicLong totalAllocCount = new AtomicLong(0);
    static final AtomicLong totalGcPauseMs = new AtomicLong(0);
    static final AtomicLong totalGcCount = new AtomicLong(0);

    static volatile boolean running = true;
    static long startTime;

    // ==================== 模式：缓存模拟 ====================

    /** 模拟缓存（静态引用，对象晋升老年代，长期存活） */
    static final List<byte[]> simulatedCache = new ArrayList<>();
    static final int CACHE_SIZE_MB = 80; // 保留 80MB 缓存

    public static void main(String[] args) throws InterruptedException {
        // ---- 解析参数 ----
        final boolean cacheMode;
        if (args.length > 0 && "transient".equals(args[0])) {
            cacheMode = false;
        } else {
            cacheMode = true;
        }

        System.out.println("============================================");
        System.out.println("  JVM GC 调优对比基准测试");
        System.out.println("============================================");
        System.out.println("Java:     " + System.getProperty("java.version"));
        System.out.println("模式:     " + (cacheMode ? "cache（模拟 Web 应用，部分对象长期存活）"
                                                    : "transient（模拟批处理，全部朝生夕死）"));
        System.out.println("堆大小:   " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        System.out.println("进程 PID: " + ProcessHandle.current().pid());
        System.out.println("运行时长: " + RUN_DURATION_SECONDS + " 秒");
        System.out.println();

        // ---- 打印当前 GC 信息 ----
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        System.out.println("当前 GC 收集器:");
        for (GarbageCollectorMXBean gc : gcBeans) {
            System.out.println("  - " + gc.getName());
        }
        System.out.println();

        // ---- 初始化缓存（避免启动后分配影响） ----
        if (cacheMode) {
            fillCache();
            System.out.println("缓存已预热: " + CACHE_SIZE_MB + "MB（模拟老年代常驻对象）");
            System.out.println();
        }

        // ---- 启动工作线程 ----
        System.out.println("启动 " + WORKER_THREADS + " 个工作线程...");
        CountDownLatch latch = new CountDownLatch(WORKER_THREADS);
        for (int i = 0; i < WORKER_THREADS; i++) {
            final int threadId = i;
            new Thread(() -> runWorkload(cacheMode, threadId, latch), "worker-" + i).start();
        }

        // ---- 启动监控线程 ----
        startTime = System.currentTimeMillis();
        new Thread(GCTuningBenchmark::monitorLoop, "monitor").start();

        // ---- 等待结束 ----
        latch.await();
        running = false;

        Thread.sleep(500); // 等监控线程刷完

        // ---- 打印总结 ----
        printSummary();
    }

    // ==================== 工作负载 ====================

    static void runWorkload(boolean cacheMode, int threadId, CountDownLatch latch) {
        Random rand = new Random(threadId);
        int localCount = 0;

        while (running) {
            // 每轮分配 N 个对象
            for (int i = 0; i < ALLOC_PER_ROUND; i++) {
                int size = 256 + rand.nextInt(768); // 256B ~ 1KB
                byte[] data = new byte[size];
                // 写入一些数据，防止 JIT 优化掉分配
                data[0] = (byte) threadId;
                data[size - 1] = (byte) i;

                if (cacheMode) {
                    // 10% 概率模拟长生命周期对象（通过静态引用）
                    if (rand.nextInt(10) == 0) {
                        // 如果缓存未满，把对象加入缓存（promoted）
                        addToCache(data);
                    }
                }
            }

            localCount += ALLOC_PER_ROUND;
            if (localCount >= 10_000) {
                totalAllocCount.addAndGet(localCount);
                localCount = 0;
            }

            // 模拟 CPU 计算（防止纯内存分配导致的不真实结果）
            if (threadId % 2 == 0) {
                busyWork(rand);
            }

            // 偶尔 yield
            if (rand.nextInt(100) == 0) {
                Thread.yield();
            }
        }

        totalAllocCount.addAndGet(localCount);
        latch.countDown();
    }

    /** 模拟 CPU 计算（质数检测，给 GC 线程和业务线程真实 CPU 争用） */
    static void busyWork(Random rand) {
        int n = 1000 + rand.nextInt(5000);
        for (int i = 2; i < Math.sqrt(n); i++) {
            if (n % i == 0) break;
        }
    }

    // ==================== 缓存管理 ====================

    static synchronized void addToCache(byte[] data) {
        if (simulatedCache.size() < CACHE_SIZE_MB * 4) { // ~256KB each
            simulatedCache.add(data);
        }
    }

    static void fillCache() {
        while (simulatedCache.size() < CACHE_SIZE_MB * 4) {
            simulatedCache.add(new byte[256 * 1024]); // 256KB 块
        }
    }

    // ==================== 监控 ====================

    static void monitorLoop() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long lastGcCount = 0;
        long lastGcTime = 0;

        // 记录初始 GC 数据
        for (GarbageCollectorMXBean gc : gcBeans) {
            lastGcCount += gc.getCollectionCount();
            lastGcTime += gc.getCollectionTime();
        }

        long lastTime = System.currentTimeMillis();

        while (running) {
            try {
                Thread.sleep(2000); // 每 2s 采样一次
            } catch (InterruptedException e) {
                break;
            }

            long now = System.currentTimeMillis();
            long elapsed = now - startTime;

            // 采集 GC 数据
            long curGcCount = 0;
            long curGcTime = 0;
            for (GarbageCollectorMXBean gc : gcBeans) {
                curGcCount += gc.getCollectionCount();
                curGcTime += gc.getCollectionTime();
            }

            long gcCountDelta = curGcCount - lastGcCount;
            long gcTimeDelta = curGcTime - lastGcTime;

            totalGcCount.addAndGet(gcCountDelta);
            totalGcPauseMs.addAndGet(gcTimeDelta);

            // 内存使用
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memBean.getHeapMemoryUsage();
            long usedMB = heap.getUsed() / 1024 / 1024;
            long maxMB = heap.getMax() / 1024 / 1024;

            // 吞吐量（分配速率）
            long allocCount = totalAllocCount.get();
            double ratePerSec = allocCount / (elapsed / 1000.0);

            System.out.printf("[%4ds] 堆: %3dMB/%dMB | GC: +%d次 (共%d次) | 耗时: +%dms (共%dms) | 分配速率: %.0f obj/s%n",
                    elapsed / 1000, usedMB, maxMB,
                    gcCountDelta, curGcCount, gcTimeDelta, curGcTime, ratePerSec);

            lastGcCount = curGcCount;
            lastGcTime = curGcTime;
            lastTime = now;
        }

        // 最终采样
        long curGcCount = 0;
        long curGcTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            curGcCount += gc.getCollectionCount();
            curGcTime += gc.getCollectionTime();
        }
        totalGcCount.addAndGet(curGcCount - lastGcCount);
        totalGcPauseMs.addAndGet(curGcTime - lastGcTime);
    }

    // ==================== 总结报告 ====================

    static void printSummary() {
        long elapsedMs = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsedMs / 1000.0;
        long allocCount = totalAllocCount.get();

        System.out.println();
        System.out.println("============================================");
        System.out.println("  性能总结报告");
        System.out.println("============================================");

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();

        System.out.printf("  运行时长:        %.1f 秒%n", elapsedSec);
        System.out.printf("  总分配对象数:    %d 个%n", allocCount);
        System.out.printf("  分配速率:        %.0f obj/s%n", allocCount / elapsedSec);
        System.out.printf("  GC 总次数:       %d 次%n", totalGcCount.get());
        System.out.printf("  GC 总耗时:       %d ms%n", totalGcPauseMs.get());
        System.out.printf("  平均 GC 停顿:    %.2f ms%n", totalGcCount.get() > 0
                ? (double) totalGcPauseMs.get() / totalGcCount.get() : 0);
        System.out.printf("  GC 时间占比:     %.2f%%%n", totalGcPauseMs.get() * 100.0 / elapsedMs);
        System.out.printf("  当前堆使用:      %d MB / %d MB%n",
                heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024);
        System.out.println();

        System.out.println("  GC 收集器明细:");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("    %s: %d 次, 总耗时 %d ms%n",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }

        System.out.println();
        System.out.println("  GC 日志文件: 启动参数中的 -Xlog 指定路径");
        System.out.println("  观察命令示例: cat /tmp/gc-*.log | grep -E \"Pause|GC\\(\\d+\\)\"");
        System.out.println("============================================");
    }
}
