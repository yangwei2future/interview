# 面试学习进度追踪

> **学习理念**：项目驱动 + 知识点支撑，形成完整面试知识网络
> **更新时间**：2026-04-16
> **周期规划**：8周（可根据实际情况调整）

---

## 📊 总体进度概览

| 模块 | 状态 | 完成度 | 项目关联度 | 优先级 |
|------|:----:|:------:|:----------:|:------:|
| 并发编程/线程池 | ✅ | 100% | ⭐⭐⭐⭐ | 已完成 |
| 并发编程/锁 | ✅ | 100% | ⭐⭐⭐⭐ | 已完成 |
| 数据库/Redis | 🔄 | 90% | ⭐⭐⭐⭐⭐ | 进行中 |
| 数据库/MySQL | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 1 |
| Java基础 | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 1 |
| JVM | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 1 |
| 系统设计 | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 2 |
| Spring | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 2 |
| 设计模式 | ⬜ | 0% | ⭐⭐⭐⭐ | Phase 2 |
| AI应用专项 | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 3 |
| 数据结构 | ⬜ | 0% | ⭐⭐⭐⭐ | Phase 3 |
| 分布式系统 | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 3 |
| 算法 | ⬜ | 0% | ⭐⭐⭐ | Phase 4 |
| 行为面试 | ⬜ | 0% | ⭐⭐⭐⭐⭐ | Phase 4 |

---

## 🎯 核心项目技术栈

### 项目1：数据服务平台（开放平台）⭐⭐⭐⭐⭐

**项目背景**：管理端owner，100+应用接入，API日均百万级调用

**核心技术点**：
- 多数据库支持（MySQL/OB/MatrixDB/PG）
- API网关设计与限流熔断
- 自然语言转SQL（NL2SQL + Agentic RAG + MCP）
- 高并发架构（Redis缓存 + Kafka异步）
- 性能优化（索引优化 + 慢查询排查）

**面试必问**：
- Q: 如何实现多数据库动态切换？
- Q: 如何实现API限流？令牌桶算法？
- Q: NL2SQL如何保证准确性？
- Q: 如何优化查询性能？

---

### 项目2：指标知识库 ⭐⭐⭐⭐⭐

**项目背景**：项目owner，20+核心接口，757指标 + 931维度

**核心技术点**：
- 知识图谱构建（Neo4j）
- 智能推荐算法
- 数据同步与一致性（DataX/Flink CDC）
- OpenAPI服务模块
- 分布式任务调度

**面试必问**：
- Q: 知识图谱如何构建？
- Q: 如何保证多源数据一致性？
- Q: OpenAPI接口设计原则？

---

### 项目3：大数据平台（数智平台）⭐⭐⭐⭐

**项目背景**：后端开发，数据消费、数据指标、架构迭代

**核心技术点**：
- 大数据存储（Hive/ClickHouse）
- 数据仓库建模
- 数据质量保证
- 多系统协同

---

## 📅 Phase 1: 项目基础能力（Week 1-2）

### 1. 数据库 ⭐⭐⭐⭐⭐（项目最高频，优先学习）

#### MySQL索引优化（Week 1，预计2天）

**学习目标**：
- [ ] 理解B+树原理与查询效率
- [ ] 掌握聚簇索引与非聚簇索引区别
- [ ] 掌握覆盖索引与联合索引优化
- [ ] 能分析Explain执行计划
- [ ] 能优化项目慢查询

**项目应用**：
- 数据服务平台查询优化
- 指标知识库数据检索

**输出内容**：
- [ ] B+树原理笔记（`src/database/mysql-index-btree.md`）
- [ ] 索引优化案例代码（`src/database/IndexOptimizationDemo.java`）
- [ ] Explain执行计划分析示例
- [ ] 项目慢查询优化文档

**面试问答准备**：
- [ ] Q: 为什么MySQL使用B+树而不是B树？
- [ ] Q: 什么是覆盖索引？如何避免回表？
- [ ] Q: 联合索引的最左前缀原则？
- [ ] Q: 如何分析慢查询？

---

#### Redis缓存设计（Week 1，预计3天）

**学习目标**：
- [x] 掌握Redis 5种基本数据类型
- [x] 理解键过期策略（惰性删除 + 定期删除）
- [x] 掌握内存淘汰策略（8种策略，allkeys-lru 最常用）
- [x] 理解持久化机制（RDB/AOF/混合模式）
- [x] 掌握缓存穿透/击穿/雪崩解决方案
- [x] 掌握分布式锁实现（演进过程 + Redisson）
- [x] 理解Redis为什么快（单线程+IO多路复用）
- [ ] 掌握分布式限流算法

**项目应用**：
- API限流（开放平台）
- 缓存一致性（数据服务平台）
- 分布式锁（指标知识库）

**输出内容**：
- [x] Redis核心笔记（`src/database/redis.md`）— 数据类型、过期策略、内存淘汰
- [ ] 分布式限流代码（`src/database/RedisRateLimiter.java`）
- [ ] 缓存一致性方案代码（`src/database/CacheConsistency.java`）
- [ ] 分布式锁代码（`src/database/RedisDistributedLock.java`）

**面试问答准备**：
- [ ] Q: Redis为什么快？（单线程+IO多路复用）
- [ ] Q: 如何实现分布式限流？令牌桶算法？
- [ ] Q: 缓存穿透/击穿/雪崩如何解决？
- [ ] Q: Redis如何保证分布式锁的可靠性？

---

#### 多数据库支持（Week 1-2，预计2天）

**学习目标**：
- [ ] 理解OceanBase分布式架构
- [ ] 掌握动态数据源切换原理
- [ ] 掌握SQL适配器模式
- [ ] 了解MatrixDB/PG特点

**项目应用**：
- 数据服务平台核心能力（支持6种数据库）

**输出内容**：
- [ ] OceanBase架构笔记（`src/database/oceanbase-architecture.md`）
- [ ] 动态数据源切换代码（`src/database/DynamicDataSourceDemo.java`）
- [ ] SQL适配器模式代码（`src/database/SQLAdapterDemo.java`）

**面试问答准备**：
- [ ] Q: 如何实现多数据库动态切换？
- [ ] Q: OB与MySQL的架构差异？
- [ ] Q: 如何处理SQL语法差异？

---

### 2. Java基础 ⭐⭐⭐⭐⭐（必备基础）

#### 集合框架（Week 2，预计2天）

**学习目标**：
- [ ] HashMap底层原理（数组+链表+红黑树）
- [ ] ConcurrentHashMap实现（JDK 1.7 vs 1.8）
- [ ] ArrayList vs LinkedList
- [ ] HashSet实现原理

**项目应用**：
- 数据存储与处理（所有项目）

**输出内容**：
- [ ] HashMap原理笔记（`src/java-basics/hashmap-principle.md`）
- [ ] ConcurrentHashMap原理笔记（`src/java-basics/concurrent-hashmap.md`）
- [ ] 集合框架对比代码（`src/java-basics/CollectionDemo.java`）

**面试问答准备**：
- [ ] Q: HashMap扩容机制？
- [ ] Q: ConcurrentHashMap如何保证线程安全？
- [ ] Q: ArrayList vs LinkedList性能对比？

---

#### 并发编程补充（Week 2，预计3天）

**学习目标**：
- [ ] synchronized原理（对象头+锁升级）
- [ ] volatile原理（可见性+禁止重排）
- [ ] CAS机制与ABA问题
- [ ] AQS原理

**项目应用**：
- 线程池已完成，补充并发基础

**输出内容**：
- [ ] synchronized原理笔记（`src/java-basics/synchronized-principle.md`）
- [ ] volatile原理笔记（`src/java-basics/volatile-principle.md`）
- [ ] AQS原理笔记（`src/java-basics/aqs-principle.md`）
- [ ] 并发编程代码示例（`src/java-basics/ConcurrencyDemo.java`）

**面试问答准备**：
- [ ] Q: synchronized锁升级过程？
- [ ] Q: volatile能否保证原子性？
- [ ] Q: CAS的ABA问题如何解决？

---

### 3. JVM ⭐⭐⭐⭐⭐（性能优化）

#### 内存模型与GC（Week 2，预计3天）

**学习目标**：
- [ ] JVM内存模型（堆、栈、方法区）
- [ ] GC算法（标记清除/复制/标记整理）
- [ ] 垃圾收集器（CMS/G1/ZGC）
- [ ] 类加载机制

**项目应用**：
- 性能优化（慢查询排查）
- 大数据量处理

**输出内容**：
- [ ] JVM内存模型笔记（`src/jvm/memory-model.md`）
- [ ] GC原理笔记（`src/jvm/gc-principle.md`）
- [ ] 类加载机制笔记（`src/jvm/class-loading.md`）
- [ ] OOM排查案例代码（`src/jvm/OOMDemo.java`）

**面试问答准备**：
- [ ] Q: JVM内存模型各区域作用？
- [ ] Q: CMS vs G1垃圾收集器？
- [ ] Q: 如何排查OOM？

---

## 📅 Phase 2: 项目核心模块（Week 3-4）

### 4. 系统设计 ⭐⭐⭐⭐⭐（项目核心）

#### API网关设计（Week 3，预计2天）

**学习目标**：
- [ ] API网关核心功能（路由、限流、熔断、鉴权）
- [ ] 限流算法（令牌桶、漏桶、滑动窗口）
- [ ] 熔断降级（Circuit Breaker模式）
- [ ] 高并发架构设计

**项目应用**：
- 数据服务平台核心架构（API日均百万级调用）

**输出内容**：
- [ ] API网关架构笔记（`src/system-design/api-gateway.md`）
- [ ] 限流算法代码（`src/system-design/RateLimiterDemo.java`）
- [ ] 熔断降级代码（`src/system-design/CircuitBreakerDemo.java`）

**面试问答准备**：
- [ ] Q: API网关的核心功能？
- [ ] Q: 如何设计高并发架构？
- [ ] Q: 熔断降级如何实现？

---

#### 分布式事务（Week 3，预计2天）

**学习目标**：
- [ ] CAP理论、BASE理论
- [ ] 分布式事务方案（2PC/3PC/TCC/Saga）
- [ ] 最终一致性设计
- [ ] 补偿机制

**项目应用**：
- 数据同步一致性（指标知识库）
- 多源数据一致性

**输出内容**：
- [ ] 分布式事务笔记（`src/system-design/distributed-transaction.md`）
- [ ] 补偿机制代码（`src/system-design/CompensationDemo.java`）

**面试问答准备**：
- [ ] Q: CAP理论如何权衡？
- [ ] Q: TCC vs Saga？
- [ ] Q: 如何保证数据一致性？

---

#### 消息队列（Week 3，预计2天）

**学习目标**：
- [ ] Kafka架构（分区、副本、消费者组）
- [ ] RabbitMQ架构
- [ ] 消息可靠性保证
- [ ] 消息顺序性

**项目应用**：
- 异步解耦（数据服务平台）
- 大文件导出异步处理

**输出内容**：
- [ ] Kafka原理笔记（`src/system-design/kafka-principle.md`）
- [ ] 异步处理代码（`src/system-design/AsyncProcessingDemo.java`）

**面试问答准备**：
- [ ] Q: Kafka如何保证消息可靠性？
- [ ] Q: 如何保证消息顺序性？
- [ ] Q: 消息队列如何解耦？

---

### 5. Spring ⭐⭐⭐⭐⭐（项目框架）

#### Spring核心原理（Week 4，预计3天）

**学习目标**：
- [ ] Bean生命周期
- [ ] 循环依赖解决（三级缓存）
- [ ] AOP原理（JDK/CGLIB动态代理）
- [ ] 事务管理（@Transactional原理）

**项目应用**：
- 项目框架基础
- 事务管理（数据一致性）

**输出内容**：
- [ ] Spring原理笔记（`src/spring/spring-principle.md`）
- [ ] Bean生命周期代码（`src/spring/BeanLifeCycleDemo.java`）
- [ ] 事务管理代码（`src/spring/TransactionDemo.java`）

**面试问答准备**：
- [ ] Q: Spring如何解决循环依赖？
- [ ] Q: @Transactional失效场景？
- [ ] Q: AOP原理？

---

#### Spring Boot自动装配（Week 4，预计2天）

**学习目标**：
- [ ] 自动装配原理（@EnableAutoConfiguration）
- [ ] Starter机制
- [ ] 配置加载顺序

**输出内容**：
- [ ] Spring Boot原理笔记（`src/spring/spring-boot-principle.md`）
- [ ] 自定义Starter代码（`src/spring/CustomStarterDemo.java`）

---

### 6. 设计模式 ⭐⭐⭐⭐（项目实践）

#### 创建型模式（Week 4，预计1天）

**学习目标**：
- [ ] 单例模式（双重检查锁）
- [ ] 工厂模式（简单工厂、工厂方法、抽象工厂）
- [ ] Builder模式（链式构建）

**项目应用**：
- EnterpriseThreadPool Builder模式
- 数据源创建（工厂模式）

**输出内容**：
- [ ] 设计模式笔记（`src/design-patterns/creational-patterns.md`）
- [ ] 设计模式代码（`src/design-patterns/DesignPatternDemo.java`）

---

#### 结构型与行为型模式（Week 4，预计1天）

**学习目标**：
- [ ] 代理模式（JDK动态代理、CGLIB）
- [ ] 适配器模式（SQL适配器）
- [ ] 策略模式（限流算法切换）
- [ ] 观察者模式

**项目应用**：
- SQL适配器模式（多数据库）
- 策略模式（限流算法）

---

## 📅 Phase 3: 项目进阶能力（Week 5-6）

### 7. AI应用专项 ⭐⭐⭐⭐⭐（项目亮点）

#### RAG与NL2SQL（Week 5，预计3天）

**学习目标**：
- [ ] RAG原理（检索增强生成）
- [ ] Agentic RAG架构（规划-执行-反馈）
- [ ] MCP协议（Model Context Protocol）
- [ ] NL2SQL技术方案

**项目应用**：
- 自然语言转SQL（数据服务平台亮点）

**输出内容**：
- [ ] RAG原理笔记（`src/system-design/rag-principle.md`）
- [ ] NL2SQL技术方案文档（`src/system-design/nl2sql-solution.md`）
- [ ] Agentic RAG架构图

**面试问答准备**：
- [ ] Q: Agentic RAG vs 传统RAG？
- [ ] Q: NL2SQL如何保证准确性？
- [ ] Q: MCP协议如何工作？

---

#### 大模型落地实践（Week 5，预计2天）

**学习目标**：
- [ ] Prompt Engineering
- [ ] Few-shot Learning
- [ ] SQL生成与验证
- [ ] 大模型应用挑战

**输出内容**：
- [ ] 大模型应用笔记（`src/system-design/llm-application.md`）

---

### 8. 数据结构 ⭐⭐⭐⭐（知识图谱）

#### 图数据结构（Week 6，预计2天）

**学习目标**：
- [ ] 图的表示（邻接矩阵、邻接表）
- [ ] 图的遍历（DFS、BFS）
- [ ] Neo4j图数据库

**项目应用**：
- 知识图谱构建（指标知识库）

**输出内容**：
- [ ] 图数据结构笔记（`src/data-structures/graph-structure.md`）
- [ ] 图遍历代码（`src/data-structures/GraphDemo.java`）

---

#### 推荐算法与检索（Week 6，预计2天）

**学习目标**：
- [ ] 推荐算法（协同过滤、基于内容）
- [ ] ElasticSearch全文检索

**项目应用**：
- 智能推荐（指标知识库）

**输出内容**：
- [ ] 推荐算法笔记（`src/data-structures/recommendation-algorithm.md`）

---

### 9. 分布式系统 ⭐⭐⭐⭐⭐（项目必备）

#### 分布式基础（Week 6，预计3天）

**学习目标**：
- [ ] 分布式锁（Redis、Zookeeper）
- [ ] 分布式ID（雪花算法）
- [ ] 分布式任务调度（XXL-Job、ElasticJob）
- [ ] 分布式一致性

**项目应用**：
- 分布式锁（Redis）
- Job服务（分布式任务调度）

**输出内容**：
- [ ] 分布式系统笔记（`src/system-design/distributed-system.md`）
- [ ] 分布式锁代码（`src/system-design/DistributedLockDemo.java`）

---

## 📅 Phase 4: 项目实战总结（Week 7-8）

### 10. 算法 ⭐⭐⭐

#### LeetCode高频题（Week 7，预计10天）

**学习目标**：
- [ ] 数组与链表（双指针、滑动窗口）- 完成10题
- [ ] 二叉树（递归遍历、层序遍历）- 完成10题
- [ ] 动态规划（背包问题、最长子序列）- 完成10题
- [ ] 排序与查找（快速排序、二分查找）- 完成5题

**输出内容**：
- [ ] LeetCode题解笔记（`src/algorithms/leetcode-solutions.md`）

---

### 11. 行为面试 ⭐⭐⭐⭐⭐

#### 项目介绍与STAR案例（Week 8，预计4天）

**学习目标**：
- [ ] 数据服务平台项目介绍（STAR法则）
- [ ] 指标知识库项目介绍
- [ ] 技术亮点问答（至少10个）
- [ ] 困难挑战案例（至少5个）

**输出内容**：
- [ ] 项目介绍文档（`src/behavioral/project-introduction.md`）
- [ ] STAR案例库（`src/behavioral/star-cases.md`）

---

#### 项目架构文档（Week 8，预计1天）

**输出内容**：
- [ ] 数据服务平台架构图（`docs/architecture-data-service-platform.png`）
- [ ] 指标知识库系统设计图（`docs/architecture-knowledge-base.png`）
- [ ] 技术选型对比表（`docs/tech-selection.md`）

---

## 📝 学习方法论

### 每日学习流程

**理论学习（40%）**：
- 阅读知识点笔记
- 理解原理和概念
- 画思维导图

**代码实践（40%）**：
- 编写代码示例（结合项目场景）
- 运行验证结果
- 调试理解原理

**总结输出（20%）**：
- 整理笔记文档
- 编写面试问答
- 准备STAR案例

---

### 重点知识三遍法

- **第一遍**：理解概念，跑通代码
- **第二遍**：深入原理，调试源码
- **第三遍**：总结输出，教会他人

---

### 项目驱动学习原则

1. **优先项目高频知识点**（数据库、Redis、分布式）
2. **结合项目场景写代码**（每个示例关联项目）
3. **准备项目问答**（每个知识点准备面试追问）
4. **挖掘项目亮点**（NL2SQL、AI落地、知识图谱）

---

## ✅ 周检查清单

### Week 1 检查
- [ ] MySQL索引优化笔记 + 项目案例
- [ ] Redis限流代码示例
- [ ] 多数据库适配方案
- [ ] Java集合框架笔记

### Week 2 检查
- [ ] 并发编程补充（synchronized、volatile、AQS）
- [ ] JVM内存模型笔记
- [ ] GC原理笔记
- [ ] OOM排查案例

### Week 3 检查
- [ ] API网关架构图
- [ ] 分布式事务方案
- [ ] Kafka异步处理代码
- [ ] 高并发架构设计文档

### Week 4 检查
- [ ] Spring核心原理笔记
- [ ] Bean生命周期代码
- [ ] 事务管理代码
- [ ] 设计模式代码示例

### Week 5 检查
- [ ] NL2SQL技术方案文档
- [ ] RAG架构笔记
- [ ] 大模型应用笔记
- [ ] 图数据结构笔记

### Week 6 检查
- [ ] 推荐算法笔记
- [ ] 分布式系统笔记
- [ ] 分布式锁代码
- [ ] 分布式任务调度方案

### Week 7 检查
- [ ] LeetCode完成35题
- [ ] 算法题解笔记

### Week 8 检查
- [ ] 项目架构图（3个项目）
- [ ] STAR案例（至少10个）
- [ ] 技术亮点问答（至少20个）
- [ ] 准备开始投递简历

---

## 🎯 最终目标

### 能力目标
- [ ] 能流畅讲解所有项目（STAR法则）
- [ ] 能回答技术追问（至少3轮）
- [ ] 能展示完整技术栈深度
- [ ] 能体现AI落地实践经验

### 输出目标
- [ ] 完成所有模块笔记（12个模块）
- [ ] 完成所有代码示例（至少30个）
- [ ] 完成所有面试问答（至少50个）
- [ ] 完成项目架构图（3个项目）
- [ ] 完成STAR案例库（至少10个）

---

## 📚 资源清单

### 书籍
- 《深入理解Java虚拟机》- JVM
- 《高性能MySQL》- 数据库
- 《Redis设计与实现》- Redis
- 《分布式系统原理》- 系统设计
- 《Spring源码深度解析》- Spring

### 在线资源
- LeetCode - 算法练习
- 牛客网 - 面试题库
- GitHub - 源码学习

---

**核心理念**：技术知识点服务于项目，项目经验展示技术深度！

**下一步行动**：从 Phase 1 数据库模块开始，优先学习 MySQL索引优化！