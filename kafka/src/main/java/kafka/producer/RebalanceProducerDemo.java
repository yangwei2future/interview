package kafka.producer;

import kafka.KafkaConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Rebalance 演示 - Producer：往 rebalance-demo-topic 发送 200 条消息。
 *
 * 运行：mvn exec:java -Dexec.mainClass="kafka.producer.RebalanceProducerDemo"
 */
public class RebalanceProducerDemo {

    private static final String TOPIC = "rebalance-demo-topic";

    public static void main(String[] args) throws Exception {
        System.out.println("===== Rebalance Producer：发送 200 条消息 =====\n");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaConfig.producerProps())) {
            for (int i = 1; i <= 200; i++) {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(TOPIC, "msg-" + i, "第" + i + "条消息");
                producer.send(record, (m, e) -> {
                    if (e == null) {
                        System.out.printf("  [sent] partition=%d offset=%d%n",
                                m.partition(), m.offset());
                    }
                });
            }
            producer.flush();
        }

        System.out.println("\n===== 200 条消息已发送 =====");
    }
}
