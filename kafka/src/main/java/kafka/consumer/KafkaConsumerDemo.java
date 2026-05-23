package kafka.consumer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;

/**
 * Consumer Demo：手动提交、poll 批量拉取、验证 offset 提交机制。
 *
 * 编译：javac -cp lib/kafka-clients-4.2.0.jar -d out src/kafka/KafkaConfig.java src/kafka/KafkaConsumerDemo.java
 * 运行：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaConsumerDemo
 */
public class KafkaConsumerDemo {

    public static void main(String[] args) {
        System.out.println("===== Kafka Consumer Demo（手动提交）=====\n");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(KafkaConfig.consumerProps("demo-group3"));
        consumer.subscribe(List.of(KafkaConfig.DEMO_TOPIC));

        try {
            int batchNo = 0;
            while (batchNo < 5) { // 拉 5 批后退出
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(2000));
                if (records.isEmpty()) {
                    System.out.println("  没有新消息，继续等...");
                    batchNo++;
                    continue;
                }

                System.out.printf("--- 第 %d 批，拉取 %d 条 ---%n", ++batchNo, records.count());
                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("  partition=%d offset=%-5d key=%-12s value=%s%n",
                            r.partition(), r.offset(), r.key(), r.value());

                    // 模拟处理耗时（每条 100ms）
                    Thread.sleep(100);
                }

                // 处理完这批才提交（手动提交，防丢消息）
                consumer.commitSync();
                System.out.printf("  => offset 已提交%n%n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            consumer.close();
            System.out.println("Consumer 已关闭");
        }
    }
}
