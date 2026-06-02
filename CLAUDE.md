# CLAUDE.md

## 项目

Java 知识库（"Java 葵花宝典"），面试准备和技能学习。

## 构建

纯 Java 文件，无 Maven/Gradle：
```bash
javac -d out src/concurrent/threadpool/ThreadPoolBasic.java
java -cp out concurrent.threadpool.ThreadPoolBasic
```

## 目录结构

```
src/
├── java-basics/        # 基础（集合、泛型、IO、反射）
├── data-structures/    # 数据结构（链表、树、堆、图）
├── algorithms/         # 算法（排序、搜索、DP）
├── design-patterns/    # 23种设计模式
├── spring/             # Spring/Spring Boot
├── jvm/                # JVM（内存、GC、类加载）
├── concurrent/         # 并发（线程、锁、JUC、线程池）
├── database/           # 数据库（MySQL、Redis）
├── system-design/      # 系统设计（分布式、微服务）
└── behavioral/         # 行为面试（STAR）
```

每个目录含 `.md` 知识笔记和 `.java` 代码示例。

## 关键约定

- 线程池 resize 顺序：扩容先 setMaxSize 再 setCoreSize，缩容反之
- submit() 异常被 Future 吞掉，必须 get() 才能感知；execute() 异常直接抛出
- 回答使用中文
