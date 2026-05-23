package kafka.consumer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Rebalance 演示 - Consumer：故意消费得很慢，触发 max.poll.interval.ms 超时。
 *
 * 核心设计：
 *   max.poll.records  = 30 条
 *   max.poll.interval.ms = 30 秒
 *   每条处理 2 秒 → 30 × 2 = 60 秒 ＞ 30 秒 → 触发 Rebalance
 *
 * 现象：同一个 Consumer 被踢出 Group → 重新加入 → 又超时 → 又踢 → 死循环
 *
 * 如何观察：
 *   1. 开两个终端
 *   2. 终端1 运行这个 Consumer，观察它不断被踢出
 *   3. 终端2 反复执行命令看 LAG：
 *      docker exec kafka1 /opt/kafka/bin/kafka-consumer-groups.sh \
 *        --bootstrap-server localhost:29092 \
 *        --describe --group rebalance-demo-group
 *   4. 可以看到 LAG 降得非常慢，且 CURRENT-OFFSET 反复回退
 *
 * 运行：mvn exec:java -Dexec.mainClass="kafka.consumer.RebalanceConsumerDemo"
 */
public class RebalanceConsumerDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Rebalance Consumer：慢消费触发超时 =====\n");

        Properties props = KafkaConfig.consumerProps("rebalance-demo-group");
        // 关键配置：故意让一批消息的处理时间 > poll 间隔上限
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 30);      // 每批拉 30 条
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000); // 超时 30 秒
        // 30条 × 2秒 = 60秒 > 30秒 → 必然超时触发 Rebalance

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("rebalance-demo-topic"));

        try {
            int batchNo = 0;
            int rebalanceCount = 0;
            long lastOffset = 0;

            while (batchNo < 10) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                if (records.isEmpty()) {
                    System.out.println("  没有新消息...");
                    batchNo++;
                    continue;
                }

                batchNo++;
                long startMs = System.currentTimeMillis();
                System.out.printf("%n--- 第 %d 批，%d 条 --- 每条处理 2 秒%n",
                        batchNo, records.count());
                System.out.println("  ⚠ 这批预计耗时 " + (records.count() * 2) +
                        " 秒，max.poll.interval.ms=30秒，必然超时！");

                for (ConsumerRecord<String, String> r : records) {
                    // 检测 offset 回退：同一批消息被重新消费
                    if (lastOffset > 0 && r.offset() == lastOffset) {
                        rebalanceCount++;
                        System.out.printf("  [回退!] partition=%d offset=%d ← 被踢后重新消费，第 %d 次 Rebalance%n",
                                r.partition(), r.offset(), rebalanceCount);
                    }
                    lastOffset = r.offset();
                    System.out.printf("  [处理] partition=%d offset=%d key=%s%n",
                            r.partition(), r.offset(), r.key());
                    Thread.sleep(2000);
                }

                // 处理完这批 60 秒后，已经被踢出 Group，commit 会失败
                try {
                    consumer.commitSync();
                    System.out.printf("  => 提交成功，耗时 %d 秒%n",
                            (System.currentTimeMillis() - startMs) / 1000);
                } catch (Exception commitEx) {
                    System.out.printf("  [Rebalance!] commit 失败（已超时被踢出）: %s%n",
                            commitEx.getMessage().split("\n")[0]);
                    System.out.println("  => 下次 poll() 会重新 join Group，从上次 offset 重新消费");
                }
            }
        } finally {
            consumer.close();
        }

        System.out.println("\n===== 结论 =====");
        System.out.println("max.poll.records × 单条耗时 > max.poll.interval.ms");
        System.out.println("→ Consumer 被踢出 Group → Rebalance → 重复消费 → 死循环");
        System.out.println("解法：调小 max.poll.records / 调大 max.poll.interval.ms / 异步处理");
    }
}
