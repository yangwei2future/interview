package kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * 共享的 Kafka 连接配置。
 * 本地 Docker 环境：3 个 broker 在 9092/9093/9094。
 */
public final class KafkaConfig {

    public static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9093,localhost:9094";
    public static final String DEMO_TOPIC = "demo-topic";

    private KafkaConfig() {}

    public static Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // 可靠性配置
        props.put(ProducerConfig.ACKS_CONFIG, "all");           // acks=-1，等所有副本确认
        props.put(ProducerConfig.RETRIES_CONFIG, 3);            // 重试 3 次
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 幂等，防重复
        // 批量&压缩（高性能关键）
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);          // 等 5ms 攒批
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);     // 16KB 一批
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4"); // 压缩
        return props;
    }

    public static Properties consumerProps(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // 手动提交（防丢消息）
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // 从最早开始消费（首次加入 group）
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // 每批拉 20 条（控制处理时间在 max.poll.interval.ms 内）
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20);
        // 加大超时，留足处理时间
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        return props;
    }
}
