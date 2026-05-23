package kafka.consumer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;

/**
 * Consumer Group Demo：3 个 Consumer 同属一个 Group，观察 Partition 分配和 Rebalance。
 *
 * 运行方式：开 3 个终端窗口依次启动，观察控制台输出：
 *   终端1：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaConsumerGroupDemo C1
 *   终端2：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaConsumerGroupDemo C2
 *   终端3：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.consumer.KafkaConsumerGroupDemo C3
 *
 * 同时运行 Producer 发消息，观察每个 Consumer 消费的是哪几个 Partition 的消息。
 */
public class KafkaConsumerGroupDemo {

    public static void main(String[] args) throws InterruptedException {
        // 用命令行参数区分 Consumer 实例名
        String instanceName = args.length > 0 ? args[0] : "C0";

        System.out.printf("===== Consumer [%s] 启动 =====%n", instanceName);

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(KafkaConfig.consumerProps("group-demo"));
        consumer.subscribe(List.of(KafkaConfig.DEMO_TOPIC));

        // 优雅关闭：Ctrl+C 时触发 rebalance
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.printf("%n[%s] 正在关闭...%n", instanceName);
            consumer.wakeup();
        }));

        try {
            int batchNo = 0;
            while (batchNo < 20) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(3000));
                if (records.isEmpty()) {
                    batchNo++;
                    continue;
                }

                System.out.printf("%n[%s] --- 第 %d 批，收到 %d 条 ---%n",
                        instanceName, ++batchNo, records.count());
                for (ConsumerRecord<String, String> r : records) {
                    System.out.printf("[%s] partition=%d offset=%d key=%s value=%s%n",
                            instanceName, r.partition(), r.offset(), r.key(), r.value());
                    Thread.sleep(200); // 模拟处理
                }
                consumer.commitSync();
            }
        } catch (Exception e) {
            // wakeup() 会中断 poll，正常关闭流程
            if (e.getMessage() != null && e.getMessage().contains("WakeupException")) {
                System.out.printf("[%s] 被 wakeup 中断%n", instanceName);
            } else {
                e.printStackTrace();
            }
        } finally {
            consumer.close();
            System.out.printf("[%s] Consumer 已关闭%n", instanceName);
        }
    }
}
