package concurrent.threadpool;

import java.util.concurrent.*;

/**
 * Arthas trace 演示
 *
 * 启动后用 Arthas 执行：
 *   trace concurrent.threadpool.ArthasTraceDemo$OrderService processOrder --depth 2 -n 3
 *
 * 然后触发任务，观察每一步的耗时输出。
 */
public class ArthasTraceDemo {

    // ========== 模拟业务服务 ==========

    static class DbService {
        public String queryOrder(long orderId) throws InterruptedException {
            Thread.sleep(80); // 模拟慢 SQL
            return "ORDER-" + orderId;
        }
    }

    static class RpcService {
        public boolean notifyDownstream(String orderNo) throws InterruptedException {
            Thread.sleep(200); // 模拟 RPC 调用（耗时最长）
            return true;
        }
    }

    static class CacheService {
        public void updateCache(String orderNo) throws InterruptedException {
            Thread.sleep(10); // 模拟缓存更新（很快）
        }
    }

    // ========== 核心业务方法（trace 追踪这里）==========

    static class OrderService {
        private final DbService db = new DbService();
        private final RpcService rpc = new RpcService();
        private final CacheService cache = new CacheService();

        /**
         * trace 这个方法：
         *   trace concurrent.threadpool.ArthasTraceDemo$OrderService processOrder
         *
         * 预期输出（耗时从大到小）：
         *   notifyDownstream ~200ms  ← 瓶颈
         *   queryOrder       ~80ms
         *   updateCache      ~10ms
         */
        public void processOrder(long orderId) throws InterruptedException {
            String orderNo = db.queryOrder(orderId);        // 第一步：查 DB
            rpc.notifyDownstream(orderNo);                  // 第二步：通知下游（最慢）
            cache.updateCache(orderNo);                     // 第三步：更新缓存
            System.out.println("订单处理完成: " + orderNo);
        }
    }

    // ========== 线程池模拟任务堆积 ==========

    public static void main(String[] args) throws InterruptedException {
        OrderService orderService = new OrderService();

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4,
                30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("order-async-" + t.getId());
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        System.out.println("=== 启动，现在用 Arthas attach 并执行 trace 命令 ===");
        System.out.println("trace concurrent.threadpool.ArthasTraceDemo$OrderService processOrder -n 5");
        System.out.println("=== 等待 10 秒后开始提交任务 ===\n");
        Thread.sleep(30_000); // 留时间 attach Arthas

        // 持续提交任务，模拟线上流量
        for (int i = 1; i <= 20; i++) {
            final long orderId = i;
            pool.submit(() -> {
                try {
                    orderService.processOrder(orderId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(500); // 每 500ms 提交一个任务
        }

        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        System.out.println("=== 全部任务完成 ===");
    }
}
