package jvm;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高并发 API 网关 JVM 调优模拟
 *
 * 场景：模拟你项目的 API 网关，日均百万调用，多数据源查询
 *
 * 包含：
 *   - 线程池处理 API 请求
 *   - 本地缓存（减少 GC 压力和 RT）
 *   - 大对象分配（模拟 SQL 结果集）
 *   - 不同请求类型混合（轻量/重量/缓存）
 *
 * 推荐运行方式（java 源文件模式）：
 *
 *   # 1. G1 默认（4核机器推荐）- 平衡型
 *   java -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
 *        -Xlog:gc*:file=/tmp/gc-api-g1.log:time
 *        src/jvm/ApiGatewaySimulation.java
 *
 *   # 2. Parallel（追求吞吐量，堆较大时）
 *   java -Xms512m -Xmx512m -XX:+UseParallelGC -XX:ParallelGCThreads=4
 *        -Xlog:gc*:file=/tmp/gc-api-parallel.log:time
 *        src/jvm/ApiGatewaySimulation.java
 *
 *   # 3. 堆调大 + G1（减少 GC 频率，适合高并发场景）
 *   java -Xms1g -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=100
 *        -Xlog:gc*:file=/tmp/gc-api-1g.log:time
 *        src/jvm/ApiGatewaySimulation.java
 *
 *   # 4. 调小堆 + G1（观察 GC 压力对吞吐量的影响）
 *   java -Xms256m -Xmx256m -XX:+UseG1GC
 *        -Xlog:gc*:file=/tmp/gc-api-256m.log:time
 *        src/jvm/ApiGatewaySimulation.java
 *
 * 观察指标：
 *   - QPS（每秒请求数）
 *   - GC 频率 & 停顿时间
 *   - 请求成功/失败率
 *   - RT 分布（avg / p99）
 */
public class ApiGatewaySimulation {

    // ==================== 配置 ====================

    /** 运行时长（秒） */
    static final long RUN_SECONDS = 25;
    /** 核心线程数（模拟服务线程数，建议 <= CPU核数*2）*/
    static final int CORE_THREADS = 8;
    /** 最大线程数 */
    static final int MAX_THREADS = 16;
    /** 请求队列容量 */
    static final int QUEUE_CAPACITY = 200;
    /** 模拟 QPS（每秒发送请求数） */
    static final int TARGET_QPS = 2000;

    // ==================== 统计 ====================

    static final AtomicInteger successCount = new AtomicInteger(0);
    static final AtomicInteger failCount = new AtomicInteger(0);
    static final AtomicInteger cacheHitCount = new AtomicInteger(0);
    static final AtomicLong totalRtMs = new AtomicLong(0);
    static final AtomicLong totalRtSquared = new AtomicLong(0);
    static final AtomicInteger maxRtMs = new AtomicInteger(0);
    static volatile boolean running = true;
    static long startTime;

    // ==================== 缓存（模拟本地缓存） ====================

    /** 模拟本地缓存（缓存 5000 条查询结果） */
    static final List<CachedQuery> localCache = new ArrayList<>(5000);
    static final Object cacheLock = new Object();

    static class CachedQuery {
        final int queryId;
        final byte[] resultData;
        CachedQuery(int id, byte[] data) { this.queryId = id; this.resultData = data; }
    }

    // ==================== 线程池 ====================

    static ThreadPoolExecutor executor;

    // ==================== 请求类型 ====================

    /** 轻量查询（简单缓存查询，小结果集） */
    static final int TYPE_LIGHT = 0;
    /** 重量查询（复杂 SQL，大结果集，关联多表） */
    static final int TYPE_HEAVY = 1;
    /** 写入操作（产生临时对象） */
    static final int TYPE_WRITE = 2;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("============================================");
        System.out.println("  API 网关高并发 JVM 调优模拟");
        System.out.println("============================================");
        System.out.println("Java:     " + System.getProperty("java.version"));
        System.out.println("堆大小:   " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB");
        System.out.println("目标 QPS: " + TARGET_QPS);
        System.out.println("线程池:   " + CORE_THREADS + "/" + MAX_THREADS + ", 队列=" + QUEUE_CAPACITY);
        System.out.println("进程 PID: " + ProcessHandle.current().pid());
        System.out.println();

        // ---- 打印当前 GC ----
        System.out.println("当前 GC 收集器:");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.println("  - " + gc.getName());
        }
        System.out.println();

        // ---- 预热本地缓存 ----
        warmUpCache();
        System.out.println("本地缓存已预热: " + localCache.size() + " 条");
        System.out.println();

        // ---- 创建线程池 ----
        executor = new ThreadPoolExecutor(
                CORE_THREADS, MAX_THREADS,
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "api-worker");
                    t.setDaemon(false);
                    return t;
                },
                new RejectedExecutionHandler() {
                    private final AtomicInteger rejectCount = new AtomicInteger(0);
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        int n = rejectCount.incrementAndGet();
                        if (n % 100 == 1) {
                            System.out.println("[告警] 请求被拒绝！已拒绝: " + n + " 次（队列满或线程池满）");
                        }
                        failCount.incrementAndGet();
                    }
                }
        );

        // ---- 启动监控 ----
        startTime = System.currentTimeMillis();
        Thread monitorThread = new Thread(ApiGatewaySimulation::monitorLoop, "monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        // ---- 启动压测 ----
        System.out.println("开始压测，目标 QPS=" + TARGET_QPS + "，运行 " + RUN_SECONDS + " 秒...");
        System.out.println();

        CountDownLatch latch = new CountDownLatch(1);
        Thread loadThread = new Thread(() -> runLoad(latch), "load-generator");
        loadThread.start();

        // 运行指定时长
        Thread.sleep(RUN_SECONDS * 1000);
        running = false;
        loadThread.join(5000);

        // 等待线程池完成已提交任务
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        // ---- 打印总结 ----
        printSummary();
    }

    // ==================== 缓存预热 ====================

    static void warmUpCache() {
        Random rand = new Random(42);
        for (int i = 0; i < 5000; i++) {
            int size = 1024 + rand.nextInt(4096); // 1KB ~ 5KB 结果集
            byte[] data = new byte[size];
            rand.nextBytes(data);
            localCache.add(new CachedQuery(i, data));
        }
    }

    // ==================== 负载生成 ====================

    static void runLoad(CountDownLatch latch) {
        Random rand = new Random(12345);
        long intervalNs = 1_000_000_000L / TARGET_QPS; // 每次请求间隔
        long nextTime = System.nanoTime();

        while (running) {
            // 控制 QPS（以固定速率发送请求）
            long now = System.nanoTime();
            if (now < nextTime) {
                // busy spin 一小段（精度高于 Thread.sleep）
                continue;
            }
            nextTime += intervalNs;

            // 如果落后太多，追赶但不积压
            if (nextTime < now - intervalNs * 10) {
                nextTime = now;
            }

            // 随机请求类型
            int type = rand.nextInt(100);
            final int requestType;
            if (type < 60) requestType = TYPE_LIGHT;   // 60% 轻量查询
            else if (type < 85) requestType = TYPE_HEAVY; // 25% 重量查询
            else requestType = TYPE_WRITE;                // 15% 写入

            // 提交到线程池
            final long submitTime = System.currentTimeMillis();
            executor.submit(() -> handleRequest(requestType, submitTime));
        }

        latch.countDown();
    }

    // ==================== 请求处理 ====================

    static void handleRequest(int type, long submitTime) {
        Random rand = ThreadLocalRandom.current();
        long startNs = System.nanoTime();

        // 模拟缓存命中（约 40% 概率，轻量查询命中率高）
        boolean cacheHit = false;
        if (type == TYPE_LIGHT && rand.nextInt(100) < 70) {
            cacheHit = true;
            cacheHitCount.incrementAndGet();
            // 从缓存读（不产生 GC 压力）
            synchronized (cacheLock) {
                int idx = rand.nextInt(localCache.size());
                CachedQuery q = localCache.get(idx);
                // touch 数据防止 JIT 优化掉
                if (q.resultData.length > 0) {
                    // 模拟缓存读取
                }
            }
            // 模拟 cache hit RT（2-5ms）
            busySleep(2 + rand.nextInt(4));
        } else {
            // 缓存未命中 → 执行查询
            processQuery(type, rand);
        }

        long rtMs = (System.nanoTime() - startNs) / 1_000_000;

        // 记录统计
        successCount.incrementAndGet();
        totalRtMs.addAndGet(rtMs);
        totalRtSquared.addAndGet(rtMs * rtMs);

        // 更新最大 RT
        while (true) {
            int curMax = maxRtMs.get();
            if (rtMs <= curMax) break;
            if (maxRtMs.compareAndSet(curMax, (int) rtMs)) break;
        }
    }

    static void processQuery(int type, Random rand) {
        switch (type) {
            case TYPE_LIGHT:
                // 轻量查询：小结果集 8-32KB
                byte[] smallResult = new byte[8192 + rand.nextInt(24576)];
                // 模拟 CPU 计算（JSON 序列化等）
                busySleep(5 + rand.nextInt(10));
                break;

            case TYPE_HEAVY:
                // 重量查询：大结果集 64-512KB（模拟复杂 SQL + 多表关联）
                byte[] largeResult = new byte[65536 + rand.nextInt(458752)];

                // 模拟多数据源聚合（产生中间对象）
                List<byte[]> intermediateResults = new ArrayList<>();
                int parts = 3 + rand.nextInt(3);
                for (int i = 0; i < parts; i++) {
                    intermediateResults.add(new byte[16384 + rand.nextInt(32768)]);
                }
                // 聚合后释放中间结果
                intermediateResults.clear();

                // 模拟 CPU 密集（序列化 + 计算）
                busySleep(20 + rand.nextInt(40));
                break;

            case TYPE_WRITE:
                // 写入操作：创建日志对象后丢弃
                for (int i = 0; i < 5; i++) {
                    byte[] logEntry = new byte[2048 + rand.nextInt(4096)];
                }
                busySleep(3 + rand.nextInt(5));
                break;
        }
    }

    /** 忙等待（模拟 CPU 处理，不触发 sleep 导致的上下文切换） */
    static void busySleep(int ms) {
        long targetTime = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < targetTime) {
            // busy spin
        }
    }

    static class ThreadLocalRandom {
        private static final ThreadLocal<Random> RANDOM = ThreadLocal.withInitial(Random::new);
        static Random current() { return RANDOM.get(); }
    }

    // ==================== 监控 ====================

    static void monitorLoop() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long lastGcCount = 0, lastGcTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            lastGcCount += gc.getCollectionCount();
            lastGcTime += gc.getCollectionTime();
        }

        while (running) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { break; }

            long elapsed = System.currentTimeMillis() - startTime;
            int success = successCount.get();
            int failed = failCount.get();
            int cacheHits = cacheHitCount.get();

            // GC 数据
            long curGcCount = 0, curGcTime = 0;
            for (GarbageCollectorMXBean gc : gcBeans) {
                curGcCount += gc.getCollectionCount();
                curGcTime += gc.getCollectionTime();
            }
            long gcDelta = curGcCount - lastGcCount;
            long gcTimeDelta = curGcTime - lastGcTime;

            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            double qps = success / (elapsed / 1000.0);
            long avgRt = success > 0 ? totalRtMs.get() / success : 0;

            System.out.printf("[%4ds] QPS: %.0f | 成功: %d | 失败: %d | 缓存命中: %d | avgRT: %dms | 堆: %dMB/%dMB | GC: +%d次 %dms%n",
                    elapsed / 1000, qps, success, failed, cacheHits, avgRt,
                    heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024,
                    gcDelta, gcTimeDelta);

            lastGcCount = curGcCount;
            lastGcTime = curGcTime;
        }
    }

    static void printSummary() {
        long elapsed = System.currentTimeMillis() - startTime;
        double elapsedSec = elapsed / 1000.0;
        int success = successCount.get();
        int failed = failCount.get();

        System.out.println();
        System.out.println("============================================");
        System.out.println("  性能总结报告");
        System.out.println("============================================");
        System.out.printf("  运行时长:        %.1f 秒%n", elapsedSec);
        System.out.printf("  总请求:          %d (成功 %d / 失败 %d)%n",
                success + failed, success, failed);
        System.out.printf("  平均 QPS:        %.0f req/s%n", success / elapsedSec);
        System.out.printf("  平均 RT:         %d ms%n",
                success > 0 ? totalRtMs.get() / success : 0);
        System.out.printf("  最大 RT:         %d ms%n", maxRtMs.get());
        System.out.printf("  缓存命中率:      %.1f%%%n",
                success > 0 ? cacheHitCount.get() * 100.0 / success : 0);
        System.out.println();

        System.out.println("  GC 统计:");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("    %s: %d 次, 总耗时 %d ms%n",
                    gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }

        long totalGcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            totalGcTime += gc.getCollectionTime();
        }
        System.out.printf("  GC 时间占比:    %.2f%%%n", totalGcTime * 100.0 / elapsed);

        System.out.println();
        System.out.println("  线程池状态:");
        System.out.printf("    活跃线程: %d / %d%n",
                executor.getActiveCount(), executor.getPoolSize());
        System.out.printf("    队列积压: %d%n",
                executor.getQueue().size());
        System.out.println("============================================");
    }
}
