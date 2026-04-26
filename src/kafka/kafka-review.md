# Kafka 核心知识点

> 本文按照"背景 → 原理 → 案例 → 关键结论"组织。
> 随学习进度持续更新。

---

## 一、为什么用 Kafka

没有 MQ 时，服务间直接同步调用有三个核心问题：

```
耦合：下单服务依赖N个下游，任何一个挂了影响下单
慢：  串行等待所有步骤完成，用户响应慢
峰值：高并发时所有下游同时被打爆
```

**Kafka 解决三个问题：**

```
解耦：发消息到 Kafka，不关心下游有几个消费者
异步：发完消息立刻返回，下游异步处理
削峰：下游按自己速度消费，不被瞬间流量打爆
```

**Kafka vs RocketMQ vs RabbitMQ：**

```
Kafka      → 高吞吐、大数据、日志收集（百万级 TPS）
RocketMQ   → 金融级可靠性、事务消息、阿里系首选
RabbitMQ   → 功能丰富、延迟队列、中小规模
```

---

## 二、核心架构

### 核心概念

```
Topic          → 消息的分类（order-topic、sms-topic）
Partition      → Topic 的分片，水平拆分实现并行消费
Broker         → 一台 Kafka 服务器节点
Producer       → 生产者
Consumer       → 消费者
Consumer Group → 消费者组，组内分工消费不同 Partition
```

### 关键规则

```
1. 一个 Partition 同一时刻只能被组内一个 Consumer 消费
2. Consumer 数 > Partition 数 → 多出的 Consumer 空闲浪费
3. Consumer 数 < Partition 数 → 一个 Consumer 负责多个 Partition
4. 不同 Consumer Group 互相独立，都能收到全量消息
```

### 为什么需要多 Partition

单 Partition 是单线程顺序消费，有性能上限。多 Partition = 水平拆分并行处理，整体 TPS 线性扩展。

```
Partition 数量估算：目标TPS / 单Consumer实际TPS（需压测得出）
```

### Broker / Leader / Follower / ISR

```
集群
└── Broker（物理服务器）
    └── Partition 副本（replication.factor=3 代表3个副本）
        ├── Leader   → 负责所有读写
        └── Follower → 同步备份，Leader挂了从ISR里选新Leader

ISR（In-Sync Replicas）= 跟上 Leader 进度的副本列表
→ 只有在 ISR 里的 Follower 才有资格当新 Leader
→ Follower 落后太多会被踢出 ISR
```

**不同 Partition 的 Leader 分散在不同 Broker 上，保证负载均衡。**

---

## 三、消息不丢失

消息经过三个环节，每个都可能丢：

```
Producer → Broker → Consumer
```

### Producer 丢失：ACK 确认机制

```
acks=0   → 发出去不管，最快，最容易丢
acks=1   → Leader 写入成功就确认，中等
acks=-1  → Leader + 所有 Follower 写入才确认，最安全

生产配置：acks=-1 + retries=3
```

### Broker 丢失：副本机制

```
replication.factor=3    → 每个 Partition 3个副本
min.insync.replicas=2   → 至少2个副本写入才算成功

配合 acks=-1，确保消息不只在一台机器
```

### Consumer 丢失：手动提交 offset

```
❌ 自动提交（默认）：
   拉到消息 → 5秒后自动提交 → 还没处理完宕机 → 消息丢失

✅ 手动提交：
   拉到消息 → 处理完 → 手动 commitSync() → 再拉下一批
```

```java
props.put("enable.auto.commit", "false");

for (Message msg : consumer.poll()) {
    process(msg);        // 先处理
}
consumer.commitSync();   // 处理完再提交
```

**代价：可能重复消费（处理完但提交前宕机）→ 用幂等处理兜底。**

### 幂等处理

同一条消息消费多次，结果和消费一次完全一样：

```
1. 数据库唯一键：messageId 唯一键，重复插入直接忽略
2. 乐观锁：UPDATE ... WHERE stock=10，条件不满足自动跳过
3. 状态机：已是"已支付"，再收到支付消息直接忽略
4. Redis去重：SET messageId EX 60 NX，失败说明已处理过
```

### 三环节总结

| 环节 | 问题 | 解决方案 |
|------|------|---------|
| Producer | 发送丢失 | acks=-1 + 重试 |
| Broker | 宕机丢失 | 副本机制（replication.factor=3） |
| Consumer | 提交丢失 | 手动提交 offset + 幂等处理 |

---

## 四、消费者组和 Rebalance

### 消费者组的本质

加大并发消费能力，一个 Consumer 消费太慢，多个 Consumer 分工消费不同 Partition。

**关键规则：同一条消息在同一个 Consumer Group 内只被消费一次，但不同 Consumer Group 都能收到全量消息。**

```
order-topic 一条消息
  → Consumer Group A（订单系统）某个 Consumer 消费
  → Consumer Group B（日志系统）某个 Consumer 也消费
  → 两组互相独立
```

### Rebalance 触发条件

```
1. Consumer 上线
2. Consumer 下线（宕机 or 正常关闭）
3. Partition 数量变化
4. Consumer 超过 max.poll.interval.ms 没有 poll ← 最容易踩坑
```

### Rebalance 的代价

Rebalance 期间整个 Consumer Group 停止消费（STW），重新分配完才恢复。

### ⚠️ 最容易踩的坑：max.poll.interval.ms

**poll() 是一批一批拉消息的，不是一条一条：**

```java
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(1000)); // 拉一批
    for (ConsumerRecord record : records) {
        process(record);  // 你再一条条处理
    }
    consumer.commitSync(); // 这批处理完提交一次
}
```

**坑：**

```
poll() 默认拉 500 条，每条处理 1 秒
→ 500秒 >> max.poll.interval.ms 默认5分钟（300秒）
→ Kafka 认为 Consumer 处理能力跟不上，踢出 Consumer Group
→ Rebalance，这批消息重新分配给别人再消费一遍
→ 又超时，又 Rebalance... 无限循环
```

**注意：心跳线程和消费线程是独立的，心跳正常不代表没问题，超时判断靠的是 max.poll.interval.ms。**

**解决：**

```
max.poll.records=50        ← 每批少拉点（最直接）
max.poll.interval.ms=600000 ← 调大超时（治标）
或把耗时逻辑扔线程池异步处理，poll() 快速返回
```

---

## 五、顺序消费

**Kafka 只保证单个 Partition 内有序，跨 Partition 不保证。**

### 解决方案：指定 Key，相同 Key 落同一个 Partition

```java
// 同一订单的所有消息用 orderId 作为 Key
producer.send(new ProducerRecord("order-topic", orderId, "订单创建"));
producer.send(new ProducerRecord("order-topic", orderId, "支付成功"));
producer.send(new ProducerRecord("order-topic", orderId, "开始发货"));
// orderId=1001 → hash → Partition 2，三条消息严格有序
```

同一个 Partition 只有一个 Consumer 消费，天然保证顺序。

### 全局有序 vs 分区有序

```
分区有序：同一 Key 的消息有序，不同 Key 之间不保证，性能好（生产常用）
全局有序：整个 Topic 只能1个Partition + 1个Consumer，彻底串行，性能极差（几乎不用）
```

### ⚠️ 坑：不要随便扩容 Partition

```
扩容前：orderId=1001 → hash % 3 → Partition 1
扩容后：orderId=1001 → hash % 6 → Partition 4  ← 变了！
→ 同一订单消息落到不同 Partition，顺序被打乱
```

**Partition 数量要提前规划好，上线后不要随意变更。**

---

*（持续更新中...）*
