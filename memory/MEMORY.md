# Interview Project Memory

## 项目交互方式（重要）
- **不是直接生成 md 文档**，而是：
  1. 先介绍主题是什么
  2. 详细讲解原理（对话式）
  3. 出题让用户巩固
  4. 用户答完后，再把知识点整理成 md 文件 commit

## 项目进度
- 已完成：
  - `src/concurrent/threadpool/` — 线程池全套（含项目结合：异步文件上传链路）
  - `src/concurrent/locks/` — synchronized 锁升级、ReentrantLock、死锁（含 DeadLockDemo.java + Arthas 排查）
  - `src/database/redis.md` — Redis 全模块 Q1~Q6
  - `src/database/mysql-index.md` — 索引原理（B+树、失效场景、EXPLAIN）
  - `src/database/mysql-transaction.md` — 事务 ACID、并发问题、隔离级别、MVCC+ReadView
  - `src/database/mysql-lock.md` — 行锁三算法（记录锁/Gap Lock/Next-Key Lock）
  - `src/jvm/jvm.md` — JVM 内存结构、GC、类加载、调优
- 待完成（按优先级）：
  - P1: MySQL 日志体系（binlog/redo log/undo log）
  - P1: SQL 优化
  - P2: 主从 & 高可用
  - Spring IOC/AOP/事务
  - Java 基础（集合、并发）
  - Kafka
  - 系统设计

## 用户偏好
- 默认中文回复
- 代码封装成方法，其他地方调用
- 完成后自动提交远程仓库
- 不自动生成说明文档
- 字段值通过 Apollo 配置，不硬编码
