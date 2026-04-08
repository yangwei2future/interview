# volatile 可见性 & 禁止指令重排

## 一、volatile 解决什么问题？

Java 内存模型（JMM）规定：每个线程有自己的**工作内存**（CPU 缓存/寄存器），
共享变量先从主内存拷贝到工作内存，修改后再写回主内存。

```
主内存（Main Memory）
    ↕          ↕
线程A工作内存   线程B工作内存
  count=0       count=0
```

**问题**：线程A 修改了 count，还没写回主内存，线程B 读到的还是旧值 → **可见性问题**

`volatile` 的作用：
1. **强制可见性**：写操作立刻刷回主内存，读操作从主内存读，不用缓存
2. **禁止指令重排**：通过内存屏障阻止 JIT 编译器/CPU 乱序优化

---

## 二、可见性问题演示

### 没有 volatile：线程可能永远看不到修改

```java
// flag 没有 volatile，线程B 可能永远读到 false（JIT 把 flag 缓存到寄存器了）
boolean flag = false;

// 线程A：
flag = true;

// 线程B：
while (!flag) { }  // 可能死循环！
```

### 加上 volatile：立刻可见

```java
volatile boolean flag = false;
// 线程A 写 flag=true → 立刻刷主内存
// 线程B 读 flag → 强制从主内存读，一定能看到 true
```

---

## 三、禁止指令重排

### 什么是指令重排？

CPU 和 JIT 编译器为了性能，会对**没有依赖关系**的指令进行重排序：

```java
int a = 1;   // ①
int b = 2;   // ②
int c = a + b; // ③ 依赖①②，不能重排到①②前面
// ①② 之间没依赖，CPU 可能先执行②再执行①
```

单线程下重排不影响结果，但**多线程下会出问题**。

### 经典案例：双重检查锁（DCL 单例）

```java
// 错误写法（没有 volatile）
public class Singleton {
    private static Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {              // 第一次检查
            synchronized (Singleton.class) {
                if (instance == null) {      // 第二次检查
                    instance = new Singleton(); // 问题在这里！
                }
            }
        }
        return instance;
    }
}
```

`new Singleton()` 实际分三步：
```
① 分配内存空间
② 初始化对象
③ 将引用指向内存地址
```

CPU 可能重排为 **① → ③ → ②**，即先把引用指向内存，再初始化。

```
线程A：执行到③，instance 已经不为 null，但对象还没初始化完
线程B：第一次检查 instance != null → 直接返回 → 拿到未初始化的对象 → 崩溃！
```

**正确写法：加 volatile 禁止重排**

```java
private static volatile Singleton instance; // 加 volatile
```

---

## 四、happens-before 规则

happens-before 是 JMM 定义的**可见性保证规则**：
如果操作 A happens-before 操作 B，则 A 的结果对 B 可见。

### 8 条核心规则

| 规则 | 说明 |
|------|------|
| **程序顺序规则** | 同一线程内，前面的操作 hb 后面的操作 |
| **volatile 写读规则** | volatile 写 hb 后续对同一变量的读 |
| **监视器锁规则** | unlock hb 后续对同一锁的 lock |
| **线程启动规则** | `Thread.start()` hb 该线程内的所有操作 |
| **线程终止规则** | 线程所有操作 hb `Thread.join()` 返回 |
| **传递性** | A hb B，B hb C → A hb C |
| **中断规则** | `interrupt()` hb 被中断线程检测到中断 |
| **对象终结规则** | 构造函数结束 hb `finalize()` 开始 |

### 最重要的记法

```
volatile 写 → happens-before → volatile 读
锁释放      → happens-before → 锁获取
线程 start  → happens-before → 线程内所有操作
```

---

## 五、volatile vs synchronized

| 对比项 | volatile | synchronized |
|--------|----------|--------------|
| 可见性 | ✅ | ✅ |
| 原子性 | ❌（复合操作不保证） | ✅ |
| 禁止重排 | ✅ | ✅（隐含） |
| 阻塞 | 不阻塞线程 | 可能阻塞 |
| 使用场景 | 状态标志、DCL | 复合操作、临界区 |

**volatile 不能替代 synchronized 的原因：**

```java
volatile int count = 0;

// count++ 分三步：读 → 加1 → 写，volatile 只保证每步可见，但三步不是原子的
// 两个线程同时执行 count++，仍然会丢失更新
count++;  // ❌ 线程不安全
```

---

## 六、volatile 的底层实现：内存屏障

JVM 在 volatile 写/读前后插入内存屏障（Memory Barrier）：

```
volatile 写：
    StoreStore 屏障    ← 屏障前的写不能重排到 volatile 写之后
    [volatile 写]
    StoreLoad 屏障     ← volatile 写不能重排到后面的读之前

volatile 读：
    [volatile 读]
    LoadLoad 屏障      ← volatile 读后面的读不能重排到 volatile 读之前
    LoadStore 屏障     ← volatile 读后面的写不能重排到 volatile 读之前
```

---

## 七、高频面试题

### Q1：volatile 能保证线程安全吗？

不能完全保证。volatile 只保证**可见性**和**禁止重排**，不保证**原子性**。
`count++` 这类复合操作需要用 `synchronized` 或 `AtomicInteger`。

### Q2：单例模式为什么需要 volatile？

防止 `new` 操作的指令重排（① 分配内存 → ③ 赋引用 → ② 初始化），
避免其他线程拿到未初始化完的对象。

### Q3：结合你的项目 —— 有没有用到 volatile 的场景？

有。比如服务优雅关闭的开关标志：

```java
// 线程池关闭时，用 volatile 让所有线程立刻感知到
private volatile boolean running = true;

// 工作线程：
while (running) {
    // 处理任务
}

// 主线程/关闭钩子：
running = false;  // 立刻对所有工作线程可见
```

类似场景：API 服务的流量开关、熔断标志、配置热更新标志（Apollo 推送后更新）。
