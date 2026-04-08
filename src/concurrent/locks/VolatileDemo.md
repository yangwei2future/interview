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

## 二、"写完不就改回去了吗" —— 为什么这个直觉是错的？

### 你的直觉模型（错误的）

```
线程A：flag = true  →  立刻写回主内存
线程B：                              ↓ 立刻读到 true
```

### 真实的 CPU 内存架构

```
┌─────────────────────────────────────────────┐
│                  主内存 (RAM)                 │
│                  flag = false                │
└──────────────┬──────────────────┬────────────┘
               │                  │
       ┌───────┴──────┐    ┌──────┴───────┐
       │   CPU 核心1   │    │   CPU 核心2   │
       │  L1缓存/寄存器 │    │  L1缓存/寄存器│
       │  flag = true  │    │  flag = false │  ← 各自缓存，互不感知！
       │   (线程A)      │    │   (线程B)     │
       └──────────────┘    └──────────────┘
```

### 问题的三个根源

**根源1：写操作不保证"立刻"刷回主内存**

线程A 执行 `flag = true`，这个写操作**只更新了 CPU 核心1 自己的 L1 缓存/寄存器**。
什么时候刷回主内存？由 CPU、操作系统、JVM 决定，**可能很久以后，也可能永远不刷**。

```
线程A 写 flag=true
    → 只改了 CPU核心1 的 L1缓存
    → 主内存里 flag 还是 false  ← B 读到的是这个！
    → 什么时候刷回？不知道
```

**根源2：即使刷回了主内存，线程B 的 CPU 核心也不会主动"刷新"缓存**

线程B 在循环开始前已经把 `flag=false` 缓存到了 CPU核心2 的 L1 缓存里。
即使线程A 把 `true` 刷回了主内存，CPU核心2 也不知道它的缓存"过期"了，仍然用旧值。

```
线程B：
  第一次循环：读 flag → 从L1缓存拿到 false → 继续循环
  第二次循环：读 flag → 还从L1缓存拿 → 还是 false！（根本不去主内存读）
  第N次循环：永远拿的缓存里的旧值
```

**根源3（最致命）：JIT 编译器的激进优化 —— 寄存器提升（Hoisting）**

JIT 看到这段代码：
```java
while (!flag) { }  // 循环体里没有任何同步操作
```

JIT 分析："`flag` 在这个循环里从未被修改，也没有同步点，那我直接把它提升到寄存器常量！"

JIT 优化后等价于：
```java
boolean temp = flag;  // 只读一次，放进寄存器
while (!temp) { }     // 永远用寄存器里的值，flag 怎么变都没用！
```

**这才是"可能永远死循环"的真正原因。**

### 形象的比喻

把它想成办公室传话：

- **你（线程A）** 在自己的便签纸（L1缓存）上把数字改成了 `true`
- **便签纸什么时候贴到公告栏（主内存）上？** 不确定，也许下午，也许永远放在抽屉里
- **同事（线程B）** 上午来上班时，已经从公告栏抄了一份到自己的便签纸：`false`
- 即使你把便签贴到公告栏了，**同事也不会主动重新去抄**，他一直看自己便签上的旧值
- 更糟糕的是：老板（JIT编译器）发现同事一直在抄自己便签，**直接告诉他"别看公告栏了，永远用你便签上的 false"**

### volatile 如何解决这三个问题

```
volatile 写（线程A）：
    1. 写完立刻强制刷回主内存（解决根源1）
    2. 通过 StoreLoad 屏障，让其他核心的缓存行失效（解决根源2 —— MESI协议）
    3. 禁止 JIT 把变量提升到寄存器（解决根源3）

volatile 读（线程B）：
    每次都强制从主内存读，不用 L1 缓存的旧值
```

---

## 三、可见性问题演示

### 没有 volatile：线程可能永远看不到修改（完整可运行示例）

> 完整代码见 [`VolatileDemo.java → NoVolatileDemo`](VolatileDemo.java:19)

```java
static class NoVolatileDemo {
    private boolean flag = false;  // ❌ 没有 volatile

    void run() throws InterruptedException {
        Thread worker = new Thread(() -> {
            System.out.println("工作线程启动，等待 flag 变为 true...");
            long start = System.currentTimeMillis();

            // JIT 热点编译后，把 flag 提升到寄存器：
            //   boolean temp = flag;   ← 只读一次
            //   while (!temp) { }      ← 之后永远不去主内存读
            while (!flag) { }

            // 没有 volatile 时，这行可能永远不会打印！
            System.out.println("感知到 flag=true，耗时 " + (System.currentTimeMillis() - start) + " ms");
        });

        worker.setDaemon(true);
        worker.start();

        // 故意等 100ms，目的：让 JIT 充分优化上面那段热循环代码
        // 如果太快写 flag=true，JIT 还没来得及优化，解释执行时可能偶发正常
        Thread.sleep(100);

        System.out.println("主线程设置 flag = true");
        flag = true;        // 写进 CPU核心1 的 L1缓存，不保证立刻刷回主内存！

        worker.join(1000);  // 最多等 1 秒
        if (worker.isAlive()) {
            System.out.println("⚠️  1 秒后工作线程仍未感知到！可见性问题");
        }
    }
}
```

**典型运行输出：**
```
工作线程启动，等待 flag 变为 true...
主线程设置 flag = true
⚠️  1 秒后工作线程仍未感知到！可见性问题
```

---

### 加上 volatile：立刻可见

> 完整代码见 [`VolatileDemo.java → WithVolatileDemo`](VolatileDemo.java:53)

```java
static class WithVolatileDemo {
    private volatile boolean flag = false;  // ✅ 加 volatile

    void run() throws InterruptedException {
        Thread worker = new Thread(() -> {
            System.out.println("工作线程启动，等待 flag 变为 true...");
            long start = System.currentTimeMillis();

            // volatile 变量被 JVM 标记为"不可提升"：
            //   禁止 JIT 做寄存器提升
            //   每次循环都强制从主内存读最新值
            while (!flag) { }

            System.out.println("✅ 感知到 flag=true，耗时 " + (System.currentTimeMillis() - start) + " ms");
        });

        worker.start();
        Thread.sleep(100);
        System.out.println("主线程设置 flag = true");
        flag = true;  // volatile 写：① 刷回主内存 ② 使其他 CPU 核心缓存行失效

        worker.join(1000);
    }
}
```

**典型运行输出：**
```
工作线程启动，等待 flag 变为 true...
主线程设置 flag = true
✅ 感知到 flag=true，耗时 101 ms
```

---

### 两种情况对比

| 场景 | 没有 volatile | 加了 volatile |
|------|-------------|--------------|
| JIT 对 `while(!flag)` 的处理 | 提升到寄存器 → `while(true)` 死循环 | 禁止提升 → 每次从主内存读 |
| 主线程写 `flag=true` 的效果 | 只改 L1 缓存，不保证刷主内存 | 立刻刷主内存 + 使其他核心缓存失效 |
| 工作线程能否退出 | ❌ 永远卡住 | ✅ 约 100ms 后正常退出 |
| 对应三个根源 | 根源1 + 根源2 + 根源3 同时存在 | 三个根源全部解决 |

---

## 四、禁止指令重排

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

## 五、happens-before 规则

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

## 六、volatile vs synchronized

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

## 七、volatile 的底层实现：内存屏障

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

## 八、高频面试题

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
