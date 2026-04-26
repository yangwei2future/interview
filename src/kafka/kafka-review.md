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

*（持续更新中...）*
