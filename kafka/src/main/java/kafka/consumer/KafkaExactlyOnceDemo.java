package kafka.consumer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 幂等消费 Demo：演示 4 种去重方案保证消息不重复处理。
 *
 * 场景：Consumer 可能因 Rebalance 等原因收到重复消息，
 * 需要在消费端做幂等，同一条消息处理 N 次效果和处理 1 次一样。
 *
 * 编译：javac -cp lib/kafka-clients-4.2.0.jar -d out src/kafka/KafkaConfig.java src/kafka/KafkaExactlyOnceDemo.java
 * 运行：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaExactlyOnceDemo
 */
public class KafkaExactlyOnceDemo {

    public static void main(String[] args) {
        System.out.println("===== Kafka 幂等消费 Demo =====\n");
        System.out.println("核心思路：以 messageId（或 offset+partition）为唯一键去重\n");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(KafkaConfig.consumerProps("exactly-once-group"));
        consumer.subscribe(List.of(KafkaConfig.DEMO_TOPIC));

        // 内存去重集合（生产上用 Redis/DB）
        Set<String> processedIds = new HashSet<>();

        try {
            int batchNo = 0;
            while (batchNo < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(3000));
                if (records.isEmpty()) {
                    batchNo++;
                    continue;
                }

                System.out.printf("--- 第 %d 批，%d 条 ---%n", ++batchNo, records.count());
                for (ConsumerRecord<String, String> r : records) {
                    // 用 partition + offset 拼唯一 ID 去重
                    String msgId = r.partition() + "-" + r.offset();

                    if (processedIds.contains(msgId)) {
                        System.out.printf("  [重复!] %s 已处理过，跳过%n", msgId);
                        continue;
                    }

                    // 模拟业务处理
                    System.out.printf("  [处理]  %s key=%s value=%s%n", msgId, r.key(), r.value());
                    processedIds.add(msgId);

                    // 生产环境方案对照：
                    // 1. DB唯一键：INSERT ... ON DUPLICATE KEY UPDATE ...
                    // 2. Redis：SETNX msgId NX EX 3600
                    // 3. 乐观锁：UPDATE ... SET version=version+1 WHERE version=5
                    // 4. 状态机：IF 当前状态 != "已支付" THEN 执行
                }
                consumer.commitSync();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            consumer.close();
        }

        System.out.printf("%n已处理消息: %s%n", processedIds);
        System.out.println("===== 幂等 Demo 结束 =====");
    }
}
