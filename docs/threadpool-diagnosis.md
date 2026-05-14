# 线程池任务堆积 — 线上排查指南

## 一、确认是不是真的堆积了

### Arthas 查看线程池

```bash
# 1. 找到线程池 Bean（Spring 项目）
vmtool --action getInstances --className java.util.concurrent.ThreadPoolExecutor --limit 10

# 2. 拿到 hashcode 后查详情
vmtool --action getInstances --className java.util.concurrent.ThreadPoolExecutor --express 'instances[0]'
# 看这些字段：
#   poolSize       — 当前活跃线程数
#   activeCount    — 正在干活的线程数
#   queue.size()   — 队列里等着的任务数
#   completedTaskCount — 已完成的任务数
#   corePoolSize   — 核心线程数
#   maximumPoolSize — 最大线程数
```

### 看队列积压趋势

```bash
# 执行 OGNL 表达式拿队列大小
ognl '@com.example.ThreadPoolMonitor@pool.getQueue().size()'

# 或者直接看 Spring 管理的线程池
watch com.example.*Task execute '{params, target.getQueue().size()}' -n 5 -x 3
```

---

## 二、定位哪条线程在慢

### thread -n 找 CPU 最高的

```bash
thread -n 3    # 看 CPU 占用最高的 3 条线程
```

堆积时不一定是 CPU 高，通常是**线程在等**。

### 看线程状态分布

```bash
thread --state WAITING     # 看哪些线程在等待
thread --state BLOCKED     # 看哪些线程被锁
thread --state TIMED_WAITING  # 看哪些线程在超时等待（很可能是外部调用卡了）
```

### 看具体线程堆栈

```bash
thread <thread-id>   # 看某条线程当前在哪行代码，是否卡在外部调用
```

---

## 三、追根因

### 如果大量线程在 TIMED_WAITING

```bash
thread --state TIMED_WAITING | grep -A5 "http\|db\|redis\|rpc"
```

大概率是外部调用超时——HTTP 请求、数据库查询、Redis 连接等没设超时或者超时设太大，线程全在干等。

### 如果大量线程在 BLOCKED

```bash
thread --state BLOCKED
```

有锁争用——同步块或 synchronized 方法，所有线程在排队等一把锁。

### 如果大量线程在 RUNNABLE

```bash
thread -n 10
```

任务本身 CPU 重——正则匹配、大 JSON 解析、循环里的计算。

---

## 四、应急止血

```bash
# 1. 扩大线程数（临时救急，重启失效）
ognl '@com.example.ThreadPoolConfig@executor.setMaximumPoolSize(200)'
ognl '@com.example.ThreadPoolConfig@executor.setCorePoolSize(100)'

# 2. 加大队列（延缓拒绝，不解决根因）
ognl '@com.example.ThreadPoolConfig@executor.getQueue()'

# 3. 清空队列（丢任务保系统，下策）
ognl '@com.example.ThreadPoolConfig@executor.getQueue().clear()'
```

---

## 五、预防措施

| 层面 | 措施 |
|------|------|
| 任务本身 | 所有外部调用加超时（connect/read timeout） |
| 线程池 | 有界队列 + 拒绝策略有日志 + 埋点告警 |
| 监控 | `getQueue().size()` 接入 Prometheus/Grafana，超过阈值告警 |
| 入口 | 限流（网关层/业务层先拦一道） |
