# 面试专题 02 —— ThreadLocal

> 日期：2026-05-13

---

## 1. 一句话理解

`ThreadLocal` 是**线程本地变量**。

它的作用不是给共享变量加锁，而是让每个线程都有一份自己的变量副本：

```text
线程 A -> userId = 1001
线程 B -> userId = 2002
```

线程 A 和线程 B 使用的是同一个 `ThreadLocal` 对象，但拿到的是各自线程里的值。

面试可以先这样说：

> ThreadLocal 用来保存当前线程的上下文数据。它可以让每个线程维护一份独立变量，线程之间互不影响。典型场景是保存当前登录用户、traceId、事务连接等。

---

## 2. 基本用法

常见封装方式：

```java
public class UserContextHolder {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
```

使用时必须配合 `try-finally`：

```java
try {
    UserContextHolder.setUserId(userId);

    // 执行业务逻辑
    Long currentUserId = UserContextHolder.getUserId();
} finally {
    UserContextHolder.clear();
}
```

核心习惯：

```text
set 之后，finally remove
```

---

## 3. 典型使用场景

### 3.1 保存当前登录用户

网关、Filter、Interceptor 解析 token 后，把 `userId` 放进 `ThreadLocal`：

```java
try {
    UserContextHolder.setUserId(userId);
    chain.doFilter(request, response);
} finally {
    UserContextHolder.clear();
}
```

后续 service 层不用每个方法都传 `userId`：

```java
Long userId = UserContextHolder.getUserId();
```

### 3.2 保存 traceId / requestId

一次请求进来生成一个 `traceId`，放到 `ThreadLocal` 中，日志打印时统一取出来。

这样排查线上问题时，可以根据同一个 `traceId` 串起整条请求链路。

### 3.3 Spring 事务上下文

Spring 事务底层会用 `ThreadLocal` 把数据库连接、事务状态等绑定到当前线程。

所以同一个请求线程中，多个 DAO 操作可以复用同一个事务上下文。

### 3.4 线程不安全对象隔离

早期常见例子是 `SimpleDateFormat`：

```java
private static final ThreadLocal<SimpleDateFormat> FORMATTER =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
```

不过现在更推荐使用线程安全的 `DateTimeFormatter`。

---

## 4. 底层原理

很多人误以为 value 是存在 `ThreadLocal` 对象里的，其实不是。

真实结构是：

```text
Thread
  -> ThreadLocalMap
       -> Entry
            key   = ThreadLocal 对象
            value = 业务对象
```

也就是说：

> 每个线程内部都有一个 `ThreadLocalMap`，`ThreadLocal` 只是访问这个 Map 的 key。

调用：

```java
threadLocal.set(value);
```

本质是：

```text
当前线程.threadLocalMap.put(threadLocal, value)
```

调用：

```java
threadLocal.get();
```

本质是：

```text
当前线程.threadLocalMap.get(threadLocal)
```

所以同一个 `ThreadLocal` 在不同线程里可以对应不同值：

```text
Thread-1.threadLocalMap:
  threadLocal -> value-1

Thread-2.threadLocalMap:
  threadLocal -> value-2
```

---

## 5. key 为什么会被回收

`ThreadLocalMap` 的 Entry 结构可以简化理解为：

```java
class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;
}
```

也就是：

```text
key   = ThreadLocal 的弱引用
value = 业务对象的强引用
```

弱引用的特点是：

> 如果一个对象只被弱引用指向，没有其他强引用，那么下一次 GC 就可以回收它。

例如：

```java
public void test() {
    ThreadLocal<byte[]> local = new ThreadLocal<>();
    local.set(new byte[1024 * 1024]);
}
```

方法执行结束后，局部变量 `local` 消失。

此时如果没有其他强引用指向这个 `ThreadLocal` 对象，`ThreadLocalMap` 里只剩弱引用：

```text
ThreadLocalMap
  key   --弱引用--> ThreadLocal
  value --强引用--> byte[]
```

GC 后可能变成：

```text
ThreadLocalMap
  key   = null
  value = byte[]
```

这就是为什么 key 会被回收。

注意：

```java
private static final ThreadLocal<User> USER = new ThreadLocal<>();
```

这种写法下，`ThreadLocal` 被静态变量强引用，key 通常不会被回收。

但仍然必须 `remove()`，因为 value 仍然会挂在当前线程上。

---

## 6. ThreadLocal 泄漏问题

ThreadLocal 常说的“泄漏”主要有两类：

1. **内存泄漏**
2. **上下文串用**

严格来说，更准确的说法是 `ThreadLocal` 的 value 泄漏，而不是线程对象本身泄漏。

### 6.1 内存泄漏是什么样子

示例：

```java
private static final ThreadLocal<byte[]> LOCAL = new ThreadLocal<>();

executor.submit(() -> {
    LOCAL.set(new byte[10 * 1024 * 1024]);
    // 没有 LOCAL.remove()
});
```

如果执行任务的是线程池线程，任务执行完以后线程不会销毁，而是回到线程池等待新任务。

此时线程里可能还残留着：

```text
pool-thread-1
  ThreadLocalMap
    Entry
      key   = ThreadLocal
      value = 10MB byte[]
```

只要线程还活着，`ThreadLocalMap` 就还在，value 就可能无法被回收。

如果线程池长期运行，且 value 比较大，就可能造成堆内存持续增长，最终 OOM。

### 6.2 key 为 null 后为什么 value 还在

当 `ThreadLocal` 对象没有外部强引用时，GC 后 Entry 可能变成：

```text
Entry
  key   = null
  value = 业务对象
```

但 value 的引用链还存在：

```text
Thread
  -> ThreadLocalMap
      -> Entry
          -> value
```

所以 value 仍然可能回收不了。

### 6.3 上下文串用是什么样子

线程池会复用线程。

请求 A 在线程 `pool-thread-1` 上执行：

```java
USER_ID.set(1001);
```

但是没有 `remove()`。

请求 B 后来也被分配到 `pool-thread-1`，如果 B 没有重新设置 `USER_ID`，就可能读到：

```java
USER_ID.get(); // 1001
```

这就是上下文污染。

在真实业务里可能造成：

- 用户信息串用
- 日志 traceId 错乱
- 数据写到错误用户下面
- 权限判断异常

所以线程池场景下，不 remove 的风险不仅是内存问题，也可能是业务安全问题。

---

## 7. 为什么线程池场景更危险

普通线程：

```text
线程执行完 -> 线程销毁 -> ThreadLocalMap 销毁 -> value 释放
```

线程池线程：

```text
任务执行完 -> 线程不销毁 -> ThreadLocalMap 继续存在 -> value 可能残留
```

所以 Web 容器、线程池、异步任务中使用 `ThreadLocal` 时，必须在任务结束时清理。

---

## 8. ThreadLocal 和 synchronized 的区别

两者解决的问题不同。

| 对比项 | synchronized / Lock | ThreadLocal |
|---|---|---|
| 核心思想 | 多线程共享同一份数据，通过加锁保证安全 | 每个线程一份数据，避免共享 |
| 是否共享变量 | 是 | 否 |
| 是否解决并发修改 | 是 | 不是直接解决，而是绕开共享 |
| 典型场景 | 扣库存、更新计数器、保护临界区 | 用户上下文、traceId、事务上下文 |

面试表达：

> synchronized 是通过加锁控制多个线程访问共享资源；ThreadLocal 是让每个线程拥有自己的变量副本，避免线程之间共享数据。它们不是替代关系，而是适用于不同场景。

---

## 9. InheritableThreadLocal

`InheritableThreadLocal` 可以让子线程继承父线程里的变量：

```java
private static final InheritableThreadLocal<String> LOCAL = new InheritableThreadLocal<>();
```

但是在线程池里要谨慎使用。

原因是线程池里的线程不是每次提交任务时新建的，父子线程关系不稳定，继承值可能不是你期望的。

如果要在线程池或异步任务中传递上下文，常见方案是：

- 显式传参
- 包装 `Runnable` / `Callable`
- 使用 `TransmittableThreadLocal`

---

## 10. 正确使用模板

### 10.1 Web 请求上下文模板

```java
public class RequestContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void remove() {
        USER_ID.remove();
    }
}
```

Filter / Interceptor：

```java
try {
    RequestContext.setUserId(userId);
    chain.doFilter(request, response);
} finally {
    RequestContext.remove();
}
```

业务代码：

```java
Long userId = RequestContext.getUserId();
```

### 10.2 线程池任务模板

```java
executor.submit(() -> {
    try {
        RequestContext.setUserId(userId);
        doBusiness();
    } finally {
        RequestContext.remove();
    }
});
```

---

## 11. 面试常见问题

### Q1：ThreadLocal 是什么？

**答：**

ThreadLocal 是线程本地变量。它可以让每个线程保存一份独立的数据副本，线程之间互不影响。

它常用于保存当前登录用户、traceId、事务连接等线程级上下文信息。

---

### Q2：ThreadLocal 底层原理是什么？

**答：**

每个 `Thread` 内部都有一个 `ThreadLocalMap`。

调用 `ThreadLocal.set()` 时，本质是把当前 `ThreadLocal` 对象作为 key，把业务对象作为 value，放到当前线程的 `ThreadLocalMap` 中。

不同线程有不同的 `ThreadLocalMap`，所以同一个 `ThreadLocal` 在不同线程里可以保存不同值。

---

### Q3：ThreadLocal 为什么会内存泄漏？

**答：**

`ThreadLocalMap` 的 Entry 中，key 是 `ThreadLocal` 的弱引用，value 是业务对象的强引用。

如果外部不再强引用这个 `ThreadLocal`，GC 后 key 可能变成 null，但 value 仍然被线程的 `ThreadLocalMap` 强引用。

如果这个线程是线程池线程，线程长期不销毁，value 就可能一直无法释放，造成内存泄漏。

解决方式是使用完后在 `finally` 中调用 `remove()`。

---

### Q4：key 不是 ThreadLocal 吗？为什么会被回收？

**答：**

key 确实是 `ThreadLocal` 对象，但 `ThreadLocalMap` 中的 key 是弱引用。

如果这个 `ThreadLocal` 对象没有被其他强引用持有，比如它只是方法里的局部变量，那么方法结束后只剩弱引用，下一次 GC 就可以把它回收。

回收后 Entry 里会出现 `key = null`，但 value 仍然是强引用，所以还可能泄漏。

---

### Q5：如果 ThreadLocal 是 static final，还会泄漏吗？

**答：**

`static final ThreadLocal` 通常不会出现 key 被回收的问题，因为它被类的静态变量强引用着。

但仍然可能出现 value 残留和上下文串用。

在线程池场景下，线程会被复用。如果不 remove，上一个任务设置的 value 可能残留在线程里，下一个任务可能读到旧数据。

所以即使是 `static final ThreadLocal`，也必须在使用完后调用 `remove()`。

---

### Q6：ThreadLocal 在线程池里有什么坑？

**答：**

线程池线程会复用，如果任务结束后没有清理 `ThreadLocal`，会有两个问题：

1. value 可能长期挂在线程的 `ThreadLocalMap` 中，导致内存泄漏。
2. 下一个复用该线程的任务可能读到上一个任务留下的上下文，导致上下文串用。

所以线程池里使用 `ThreadLocal` 必须 `try-finally remove`。

---

### Q7：ThreadLocal 和 synchronized 有什么区别？

**答：**

`synchronized` 是多个线程访问同一份共享数据时，通过加锁保证线程安全。

`ThreadLocal` 是让每个线程拥有自己的变量副本，从源头上避免共享。

一个是加锁保护共享变量，一个是避免变量共享，适用场景不同。

---

## 12. 最终背诵版

> ThreadLocal 是线程本地变量，用来保存当前线程独有的上下文信息，比如用户信息、traceId、事务连接等。它的底层是每个 Thread 内部维护一个 ThreadLocalMap，ThreadLocal 对象作为 key，业务对象作为 value。不同线程有不同的 ThreadLocalMap，所以线程之间互不影响。
>
> 需要注意内存泄漏问题。ThreadLocalMap 的 key 是弱引用，value 是强引用。如果 ThreadLocal 对象没有外部强引用，GC 后 key 会变成 null，但 value 仍然可能被线程持有。在线程池场景下，线程长期存活，value 就可能一直无法释放。
>
> 另外线程池复用线程，如果不 remove，还可能导致下一个任务读到上一个任务留下的用户信息或 traceId，造成上下文串用。因此 ThreadLocal 使用完必须在 finally 中调用 remove。

---

## 13. 记忆口诀

```text
ThreadLocal 存上下文；
每个线程一份值；
底层 ThreadLocalMap；
key 弱引用，value 强引用；
线程池必须 remove。
```
