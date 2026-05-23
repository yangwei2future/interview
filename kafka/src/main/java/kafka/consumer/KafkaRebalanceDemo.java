package kafka.consumer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.List;

/**
 * Rebalance + 积压 Demo：故意让 Consumer 处理变慢触发 Rebalance，然后观察重复消费。
 *
 * 流程：
 *   1. Producer 发 100 条消息到 topic
 *   2. Consumer 每批拉 20 条，但每条处理 5 秒（故意慢）
 *   3. 看是否会触发 Rebalance（max.poll.records×单条耗时 > max.poll.interval.ms → Rebalance）
 *
 * 编译：javac -cp lib/kafka-clients-4.2.0.jar -d out src/kafka/KafkaConfig.java src/kafka/KafkaRebalanceDemo.java
 * 运行：
 *   先运行 Consumer（等待消息）：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaRebalanceDemo consume
 *   再开终端运行 Producer：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaRebalanceDemo produce
 */
public class KafkaRebalanceDemo {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "consume".equalsIgnoreCase(args[0])) {
            runConsumer();
        } else {
            runProducer();
        }
    }

    // ---------- Producer：发 100 条消息 ----------
    static void runProducer() throws Exception {
        System.out.println("===== Rebalance Demo - Producer =====");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaConfig.producerProps())) {
            String topic = "rebalance-demo-topic";
            for (int i = 1; i <= 100; i++) {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(topic, "msg-" + i, "第" + i + "条消息");
                producer.send(record, (m, e) -> {
                    if (e == null) System.out.printf("  [sent] offset=%d%n", m.offset());
                });
            }
            producer.flush();
        }
        System.out.println("===== 100 条消息已发送 =====");
    }

    // ---------- Consumer：拉 20 条一批，每条处理 5 秒 ----------
    static void runConsumer() {
        System.out.println("===== Rebalance Demo - Consumer（观察超时 Rebalance）=====\n");

        // 使用独立 group，避免影响其他 demo
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                KafkaConfig.consumerProps("rebalance-demo-group"));
        consumer.subscribe(List.of("rebalance-demo-topic"));

        try {
            int batchNo = 0;
            while (batchNo < 6) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(2000));
                if (records.isEmpty()) {
                    System.out.println("  等待消息...");
                    batchNo++;
                    continue;
                }

                batchNo++;
                System.out.printf("%n--- 第 %d 批，%d 条 --- 每条处理 5 秒（故意慢）%n",
                        batchNo, records.count());

                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("  [处理] partition=%d offset=%d value=%s%n",
                            r.partition(), r.offset(), r.value());
                    Thread.sleep(5000); // 每条 5 秒（故意慢）
                }

                System.out.printf("  <--> 这批处理完共耗时约 %d 秒%n", records.count() * 5);
                consumer.commitSync();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            consumer.close();
        }

        System.out.println("\n理想结果：20条×5秒=100秒 < 300秒(max.poll.interval.ms)，不会 Rebalance");
        System.out.println("如果超出 max.poll.interval.ms，Consumer 会被踢出 Group 触发 Rebalance");
    }
}
