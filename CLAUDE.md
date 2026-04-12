# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java knowledge repository ("Java 葵花宝典") containing organized knowledge, code examples, solutions, and learning notes for Java interview preparation and skill development.

## Build and Run Commands

This project uses plain Java source files without a build system (no Maven/Gradle). To compile and run Java files:

```bash
# Compile a single file
javac -d out src/concurrent/threadpool/ThreadPoolBasic.java

# Run the compiled class
java -cp out concurrent.threadpool.ThreadPoolBasic
```

For files with multiple classes or dependencies, compile all related files together:

```bash
# Compile all thread pool demos
javac -d out src/concurrent/threadpool/*.java

# Run specific demo
java -cp out concurrent.threadpool.ThreadPoolFailover
```

## Architecture and Structure

### Directory Layout

The repository is organized by Java knowledge domains:

```
src/
├── java-basics/        # Java fundamentals (collections, generics, IO, reflection)
├── data-structures/    # Data structures (linked lists, trees, heaps, graphs)
├── algorithms/         # Algorithms (sorting, searching, dynamic programming)
├── design-patterns/    # Design patterns (GoF 23 patterns)
├── spring/             # Spring/Spring Boot principles
├── jvm/                # JVM (memory model, GC, class loading)
├── concurrent/         # Concurrent programming (threads, locks, JUC)
├── database/           # Database (MySQL, Redis, index optimization)
├── system-design/      # System design (distributed, microservices)
└── behavioral/         # Behavioral interview questions (STAR method)
```

Each directory contains:
- `.md` files with knowledge notes (structured learning content)
- `.java` files with runnable code examples

### Concurrent/ThreadPool Module (Most Developed)

The `src/concurrent/threadpool/` directory is the most developed module:

**Key Files:**
- `EnterpriseThreadPool.java` - Enterprise-grade thread pool utility with Builder pattern, slow task alerts, rejection callbacks, health metrics, dynamic resizing (Apollo integration), and graceful shutdown
- `ThreadPoolBasic.java` - Core parameters, rejection policies, workflow verification
- `ThreadPoolFailover.java` - Exception handling, thread death recovery, UncaughtExceptionHandler, graceful shutdown
- `ThreadPoolBackpressureDemo.java` - Task backlog troubleshooting (monitoring, diagnosis)
- `ThreadPoolTroubleshoot.java` - Health checks, thread leaks, deadlocks, tuning formulas
- `ThreadPoolScenarios.java` - Use cases for Fixed/Cached/Single/Scheduled pools

**Knowledge Documentation:**
- `README.md` in threadpool directory contains comprehensive notes covering: 7 core parameters, workflow, 4 rejection policies, queue types, execute vs submit differences (critical: submit exceptions are trapped in Future), exception handling patterns, graceful shutdown, troubleshooting (task backlog, thread leaks, deadlocks), tuning formulas (CPU/IO-bound), daemon threads, UncaughtExceptionHandler, core thread timeout, runtime config changes, thread death handling

## Code Patterns

### EnterpriseThreadPool Usage

```java
EnterpriseThreadPool pool = EnterpriseThreadPool.builder("order-async")
    .coreSize(4)
    .maxSize(8)
    .queueCapacity(200)
    .slowTaskThresholdMs(500)
    .onSlowTask((name, costMs) -> log.warn("慢任务 task={} cost={}ms", name, costMs))
    .onRejected((task, executor) -> alertService.send("线程池已满"))
    .build();

pool.execute("place-order", () -> orderService.process(order));
Future<Result> future = pool.submit("query-stock", () -> stockService.query(sku));
Metrics metrics = pool.getMetrics();
```

### Dynamic Resizing (Apollo Integration)

```java
@ApolloConfigChangeListener
public void onChange(ConfigChangeEvent event) {
    int newCore = Integer.parseInt(event.getChange("threadpool.order-async.coreSize").getNewValue());
    int newMax  = Integer.parseInt(event.getChange("threadpool.order-async.maxSize").getNewValue());
    pool.resize(newCore, newMax);  // Handles order: expand → max first, shrink → core first
}
```

### Critical Thread Pool Concepts

**execute vs submit exception handling:**
- `execute()`: Exception thrown directly, thread dies, goes to UncaughtExceptionHandler
- `submit()`: Exception wrapped in Future, thread survives, must call `get()` to detect (otherwise silently lost)
- `afterExecute` hook receives different Throwable: execute gets exception directly, submit always receives null (must unwrap from Future)

**Thread death handling hierarchy:**
1. Task internal try-catch (best: thread never dies)
2. afterExecute hook (good for submit scenarios)
3. UncaughtExceptionHandler (fallback for execute scenarios)

**Resize order matters:**
- Expand: `setMaxSize()` then `setCoreSize()` (avoid core > max IllegalArgumentException)
- Shrink: `setCoreSize()` then `setMaxSize()`

## Documentation Style

Knowledge notes (`.md` files) use:
- Tables for parameter/behavior comparisons
- Code blocks for configuration patterns
- "三步法" / "四步法" numbered troubleshooting procedures
- Bold highlights for critical points (陷阱、必须、禁止)
- ASCII workflow diagrams for process visualization