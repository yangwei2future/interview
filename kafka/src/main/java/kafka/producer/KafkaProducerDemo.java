package kafka.producer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Producer Demo：覆盖基本发送、Key 路由、异步回调、批量&压缩。
 *
 * 启动前确保 Docker Kafka 已运行：docker start kafka1 kafka2 kafka3
 * 编译：javac -cp lib/kafka-clients-4.2.0.jar -d out src/kafka/KafkaConfig.java src/kafka/KafkaProducerDemo.java
 * 运行：java -cp out:lib/kafka-clients-4.2.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-simple-2.0.13.jar kafka.producer.KafkaProducerDemo
 */
public class KafkaProducerDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Kafka Producer Demo =====\n");
        KafkaProducerDemo demo = new KafkaProducerDemo();
        demo.test03();
        System.out.println("\n===== Producer Demo 结束 =====");
    }

    public void test01(){
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaConfig.producerProps())) {

            // 1. 简单发送（key=null → 轮询分区）
            System.out.println("[1] 发送 3 条无 Key 消息（轮询分区）---");
            for (int i = 1; i <= 3; i++) {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(KafkaConfig.DEMO_TOPIC, null, "message-" + i);
                producer.send(record, (metadata, ex) -> {
                    if (ex == null) {
                        System.out.printf("  -> partition=%d offset=%d value=%s%n",
                                metadata.partition(), metadata.offset(), record.value());
                    } else {
                        System.err.println("发送失败: " + ex.getMessage());
                    }
                });
            }
            // 确保回调执行完
            producer.flush();
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void test02(){
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaConfig.producerProps())) {
            // 2. 指定 Key 发送（相同 Key → 同一分区 → 保证顺序）
            System.out.println("\n[2] 同一 orderId 的三条消息（Key 路由，保证顺序）---");
            String orderId = "ORDER-1001";
            String[] events = {"创建订单", "支付成功", "开始发货"};
            for (String event : events) {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(KafkaConfig.DEMO_TOPIC, orderId, event);
                producer.send(record, (metadata, ex) -> {
                    if (ex == null) {
                        System.out.printf("  key=%s -> partition=%d offset=%d | %s%n",
                                record.key(), metadata.partition(), metadata.offset(), record.value());
                    }
                });
            }
            // 确保回调执行完
            producer.flush();
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void test03(){
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaConfig.producerProps())) {

            // 3. 不同 Key → 路由到不同分区
            System.out.println("\n[3] 不同 Key 分散到不同分区---");
            for (int i = 1; i <= 5; i++) {
                String key = "user-" + i;
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(KafkaConfig.DEMO_TOPIC, key, "用户" + i + "的消息");
                producer.send(record, (metadata, ex) -> {
                    if (ex == null) {
                        System.out.printf("  key=%s partition=%d%n", record.key(), metadata.partition());
                    }
                });
            }

            // 确保回调执行完
            producer.flush();
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
