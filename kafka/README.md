# Kafka 完整笔记

> 理论结合实践，覆盖 Kafka 核心原理、生产配置、常见问题 + 可运行 Demo。

---

## 环境准备

### 前置条件

- Docker（Kafka 3 节点集群，端口 9092/9093/9094）
- JDK 17+
- Maven 3.6+

### 启动 Kafka

```bash
docker start kafka1 kafka2 kafka3
```

### 编译

```bash
# 在项目根目录编译所有模块
mvn clean compile -pl kafka

# 或者只编译 kafka 模块
cd kafka && mvn clean compile
```

### 提前创建 Rebalance Demo 用的 Topic

```bash
docker exec kafka1 /opt/kafka/bin/kafka-topics.sh \
  --create --topic rebalance-demo-topic \
  --bootstrap-server localhost:9092 \
  --partitions 6 --replication-factor 2
```

---

## 运行流程（建议顺序）

| 顺序 | Demo | 说明 |
|------|------|------|
| 1 | `KafkaProducerDemo` | 体验 Producer 发送、Key 路由、回调 |
| 2 | `KafkaConsumerDemo` | 体验手动提交、poll 批量拉取 |
| 3 | `KafkaConsumerGroupDemo` | 开 3 个终端，看 Partition 分配和 Rebalance |
| 4 | `KafkaExactlyOnceDemo` | 幂等消费，用 partition+offset 去重 |
| 5 | `KafkaRebalanceDemo` | 模拟慢消费触发 Rebalance |

运行命令（在 kafka/ 目录下执行）：

```
# Producer
mvn exec:java -Dexec.mainClass="kafka.producer.KafkaProducerDemo"

# Consumer
mvn exec:java -Dexec.mainClass="kafka.consumer.KafkaConsumerDemo"
```

或者手动指定 classpath：

```
java -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) kafka.producer.KafkaProducerDemo
```

ConsumerGroupDemo 三个终端分别传入参数 C1 / C2 / C3：`kafka.consumer.KafkaConsumerGroupDemo`

---

## 一、核心架构

### 核心概念

```
Topic          → 消息分类（order-topic、sms-topic）
Partition      → Topic 分片，水平拆分实现并行消费
Broker         → 一台 Kafka 服务器节点
Producer       → 生产者
Consumer       → 消费者
Consumer Group → 消费者组，组内分工消费不同 Partition
```

### 关键规则

| # | 规则 | 影响 |
|---|------|------|
| 1 | 一个 Partition 同时只能被组内 1 个 Consumer 消费 | Consumer 数 > Partition 数 → 多余的空闲 |
| 2 | 不同 Consumer Group 互相独立 | 都能收到全量消息（广播） |
| 3 | Partition 数量决定并行度上限 | Consumer 扩到 Partition 数后无法再提升 |

### Broker / Leader / Follower / ISR

```
集群
└── Broker（物理服务器）
    └── Partition 副本（replication.factor=3 代表3个副本）
        ├── Leader   → 负责所有读写
        └── Follower → 同步备份，Leader挂了从ISR里选新Leader

ISR（In-Sync Replicas）= 跟上 Leader 进度的副本列表
→ 只有 ISR 里的 Follower 才有资格当新 Leader
→ Follower 落后太多会被踢出 ISR
```

不同 Partition 的 Leader 分散在不同 Broker，保证负载均衡。

### 消息结构：Key 和 Value

| | 作用 | 序列化器 | 举例 |
|------|------|------|------|
| **key** | 决定消息发到哪个分区（路由坐标） | `key.serializer` | `"user_123"` |
| **value** | 消息的内容/载荷（payload） | `value.serializer` | `{"orderId":1001}` |

**分区路由公式：** `hash(key) % 分区数`

- key=null → 随机轮询分区（不关心顺序）
- key=指定 → 相同 key 进同一分区（保证顺序）

---

## 二、消息不丢失（三级保障）

```
Producer → Broker → Consumer
```

### Producer：ACK 确认

| acks | 行为 | 适用 |
|------|------|------|
| 0 | 发出去不管 | 日志丢失可接受 |
| 1 | Leader 写入成功即确认 | 一般业务 |
| -1/all | 所有副本写入才确认 | 金融/支付 |

**生产配置：** `acks=-1 + retries=3 + enable.idempotence=true`

### Broker：副本机制

```
replication.factor=3    → 每个 Partition 3 副本
min.insync.replicas=2   → 至少 2 个副本写入才算成功
```

### Consumer：手动提交 offset

```
❌ 自动提交：拉到消息 5 秒后自动提交 → 没处理完宕机 → 消息丢失
✅ 手动提交：处理完 → commitSync() → 再拉下一批
```

**代价：** 可能重复消费（处理完但提交前宕机）→ 幂等兜底

### 幂等处理（4 种方案）

| 方案 | 实现 | 适用场景 |
|------|------|---------|
| 数据库唯一键 | INSERT ... ON DUPLICATE KEY UPDATE | 强一致性 |
| 乐观锁 | UPDATE ... WHERE version=5 | 库存扣减 |
| 状态机 | IF 状态 != "已支付" THEN 执行 | 订单状态流转 |
| Redis 去重 | SETNX msgId NX EX 3600 | 高并发去重 |

---

## 三、消费者组和 Rebalance

### 消费者组的本质

加大并发消费能力——多个 Consumer 分工消费不同 Partition。

**同一条消息在同一 Group 内只被消费一次，不同 Group 都能收到全量消息。**

### Rebalance 触发条件

1. Consumer 上线
2. Consumer 下线（宕机 or 正常关闭）
3. Partition 数量变化
4. **Consumer 超过 `max.poll.interval.ms` 没有 poll** ← 最容易踩的坑

Rebalance 期间整个 Group 停止消费（STW）。

### 高频踩坑：max.poll.interval.ms

```
poll() 默认拉 500 条，每条处理 1 秒
→ 500 秒 >> max.poll.interval.ms（默认 300 秒）
→ Kafka 认为 Consumer 跟不上，踢出 Group
→ Rebalance，消息重新分配
→ 又超时，又 Rebalance... 无限循环
```

**解决公式：** `max.poll.records × 单条耗时 < max.poll.interval.ms`

三条措施：
```
max.poll.records=50              ← 每批少拉（最直接）
max.poll.interval.ms=600000      ← 调大超时（治标）
耗时逻辑扔线程池异步处理          ← poll() 快速返回（治本）
```

**注意：心跳线程和消费线程是独立的，心跳正常不代表没超时。**

---

## 四、顺序消费

**Kafka 只保证单个 Partition 内有序，跨 Partition 不保证。**

### 方案：相同 Key 进同一 Partition

```java
producer.send(new ProducerRecord("order-topic", orderId, "创建订单"));
producer.send(new ProducerRecord("order-topic", orderId, "支付成功"));
// orderId=1001 → hash → Partition 2，严格有序
```

### 全局有序 vs 分区有序

```
分区有序：同一 Key 有序，不同 Key 不保证，性能好（生产常用）
全局有序：整个 Topic 仅 1 Partition + 1 Consumer，彻底串行（几乎不用）
```

### 不要随便扩 Partition

```
扩容前：orderId=1001 → hash%3 → Partition 1
扩容后：orderId=1001 → hash%6 → Partition 4  ← 变了！
→ 同一订单落到不同分区，顺序被打乱
```

---

## 五、高性能原理

单机百万级 TPS，四个核心设计：

| 设计 | 原理 | 一句话 |
|------|------|--------|
| 顺序写磁盘 | 只追加不修改，比随机写快 | PageCache 缓存后批量刷盘 |
| 页缓存（PageCache） | 写先写内存，Consumer 大概率从内存读 | 不碰磁盘，读吞吐极高 |
| 零拷贝（sendfile） | 数据从磁盘到网卡不经过用户空间 | 减少 CPU 拷贝，省 CPU |
| 批量+压缩 | Producer 攒 16KB/5ms 打包，lz4 压缩 | 减少网络请求和传输量 |

**面试答法：** 顺序写、页缓存、零拷贝、批量压缩，四点说清即可。

---

## 六、消息积压（Lag）

**Lag = Partition 最新 offset - Consumer 当前消费 offset**

```bash
docker exec kafka1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group demo-group
# 关注 LAG 列
```

### 处理四步

```
1. 扩 Consumer（最直接）
   → 但不能超过 Partition 数

2. 排查消费慢的原因
   → 调小 max.poll.records
   → 消费逻辑异步化（扔线程池）
   → 加机器

3. 紧急扩 Partition
   → 注意：会影响顺序消费

4. 限制 Producer（最后手段）
   → Sentinel 限流
```

### Rebalance 死循环判断公式

```
max.poll.records × 单条耗时 > max.poll.interval.ms
→ Kafka 踢出 Consumer → Rebalance → 重复消费 → 更慢 → 死循环
```

---

## 七、配置速查

### Producer（可靠性优先）

```properties
acks=all                           # 同步所有副本
retries=3                          # 重试
enable.idempotence=true            # 幂等（防重复发送）
compression.type=lz4               # 压缩
linger.ms=5                        # 攒批等待
batch.size=16384                   # 批量大小
```

### Consumer（不丢消息优先）

```properties
enable.auto.commit=false           # 手动提交
auto.offset.reset=earliest         # 首次从最早开始
max.poll.records=20                # 每批少拉
max.poll.interval.ms=300000        # 超时 5 分钟
```

---

## Demo 文件清单

| 文件 | 功能 |
|------|------|
| `KafkaConfig.java` | 共享配置（Producer/Consumer Properties） |
| `producer/KafkaProducerDemo.java` | 基本发送、Key 路由、异步回调 |
| `consumer/KafkaConsumerDemo.java` | 手动提交、poll 批量拉取 |
| `consumer/KafkaConsumerGroupDemo.java` | Consumer Group 分区分配演示 |
| `consumer/KafkaExactlyOnceDemo.java` | 幂等消费（partition+offset 去重） |
| `consumer/KafkaRebalanceDemo.java` | 慢消费触 Rebalance 观察 |
