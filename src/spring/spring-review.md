# Spring 面试复习手册

> 本文按照"背景 → 原理 → 案例 → 关键结论"组织，专为面试前快速复习使用。
> 随学习进度持续更新。

---

## 一、IOC 和 DI

### IOC（控制反转）

**背景：** 没有 IOC 时，对象依赖需要自己 new，改一处牵连所有用到它的地方。

**IOC = 把对象的创建和管理权交给 Spring 容器，你只管用，不管创建。**

```java
// ❌ 没有IOC：自己new，耦合严重
public class OrderController {
    private OrderService orderService = new OrderService();
}

// ✅ 有IOC：声明需要什么，Spring自动注入
@Service
public class OrderController {
    @Autowired
    private OrderService orderService;
}
```

Spring 容器本质是一个 Map：
```
Map<String, Object>：
  "orderService" → OrderService实例
  "userService"  → UserService实例
```

### DI 三种注入方式

```java
// 1. 字段注入（常用但不推荐）
@Autowired
private OrderService orderService;

// 2. 构造器注入（推荐）
public OrderController(OrderService orderService) {
    this.orderService = orderService;
}

// 3. Setter注入（少用）
@Autowired
public void setOrderService(OrderService orderService) {
    this.orderService = orderService;
}
```

**为什么推荐构造器注入：**
- 可以声明 final 字段，不可变更安全
- 对象创建时依赖就必须传入，不会有不完整状态
- 循环依赖时直接报错，问题暴露早

**字段注入的坑：**
```java
@Service
public class OrderService {
    @Autowired
    private StockService stockService;

    public OrderService() {
        stockService.doSomething(); // ❌ NPE！构造函数执行时依赖还没注入
    }
}
```

---

## 二、AOP（面向切面编程）

### 背景

100个接口都需要打日志、权限校验、记录耗时，每个都写一遍重复代码，改一处要改100个地方。

**AOP = 把通用逻辑抽出来，通过动态代理在方法执行前后插入，业务代码保持干净。**

### 三要素

```
切面（Aspect）  →  装通用逻辑的类
切点（Pointcut）→  在哪些方法上生效（execution表达式）
通知（Advice）  →  在什么时机执行
    @Before         方法执行前
    @After          方法执行后（无论成功失败）
    @AfterReturning 方法成功返回后
    @AfterThrowing  方法抛异常后
    @Around         前后都能控制（最强，包含上面所有）
```

### 底层原理：动态代理

```
你调用 orderService.placeOrder()
  ↓ 实际是代理对象
代理：执行切面前置逻辑（日志、权限）
  ↓
代理：调用真正的 placeOrder()
  ↓
代理：执行切面后置逻辑
```

```
JDK动态代理  →  目标类有接口，代理接口
CGLIB代理    →  目标类无接口，继承生成子类
Spring Boot 2.x 以后默认全用 CGLIB
```

**Spring 事务底层就是 AOP，`@Transactional` 本质是切面。**

### ⚠️ 经典坑：AOP 自调用失效

```java
@Service
public class OrderService {
    @Transactional
    public void placeOrder() {
        this.decreaseStock(); // ❌ this是原始对象，绕过代理，事务不生效
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void decreaseStock() { ... }
}

// 解决：注入自己的代理对象
@Autowired
private OrderService self;

self.decreaseStock(); // ✅ 通过代理调用，AOP生效
```

---

## 三、Bean 生命周期

**完整生命周期：**

```
1. 实例化（new）           ←  反射调用构造函数，属性全是默认值
2. 属性填充（DI）          ←  循环依赖发生在这里
3. Aware回调               ←  让Bean感知Spring容器信息
4. BeanPostProcessor前置
5. @PostConstruct（初始化）←  依赖已注入完毕，适合做缓存预热
6. BeanPostProcessor后置   ←  AOP代理在这里生成！
7. 放入一级缓存，对外服务
8. @PreDestroy（销毁）     ←  释放资源、关闭连接
```

**为什么需要 @PostConstruct，构造函数里不行吗？**

构造函数在第1步执行，属性填充在第2步，构造函数里依赖还是 null，用了会 NPE。@PostConstruct 在第5步执行，所有依赖已注入完毕，安全。

**Aware 的作用：** 让 Bean 感知 Spring 容器，最常用是 `ApplicationContextAware`，用于运行时动态获取 Bean（策略模式场景）。

---

## 四、循环依赖与三级缓存

### 什么是循环依赖

A依赖B，B又依赖A，Spring创建时互相等待，无法完成。

### 三级缓存结构

```
一级缓存 singletonObjects      →  完整Bean（实例化+DI+初始化全部完成）
二级缓存 earlySingletonObjects →  早期对象（已确定是否代理，但未完成初始化）
三级缓存 singletonFactories    →  工厂Lambda（还不知道要不要AOP代理）
```

### 完整流程（A依赖B，B依赖A）

```
1. new A → 得到A原始对象 → 基于原始对象生成工厂Lambda → 工厂放三级缓存
2. A做DI → 发现需要B
3. new B → 得到B原始对象 → 基于原始对象生成工厂Lambda → 工厂放三级缓存
4. B做DI → 发现需要A
5. 调用A的工厂 → 判断A要不要AOP代理 → 返回早期A → 放二级缓存
6. B拿到早期A，B完成DI → B走初始化 → B放一级缓存
7. A拿到完整B，A完成DI → A走初始化 → A放一级缓存
```

**B先进一级缓存，A后进一级缓存。**

### 为什么需要三级，二级不够？

```
如果直接放二级（存原始A）：
  B拿到原始A（0x1234）
  A后续生成代理A（0x5678）放一级缓存
  → B持有原始A，容器里是代理A，引用不一致，AOP失效

三级缓存（存工厂Lambda）：
  B来取A时调工厂 → 工厂判断要不要代理
  → 需要代理：生成代理A，放二级缓存，返回给B
  → 不需要代理：返回原始A，放二级缓存，返回给B
  → B和容器拿到的是同一个对象 ✅
```

**工厂的本质：延迟决策"给原始A还是代理A"，等B真正来取的时候再判断。**

### 面试追问链路

```
循环依赖 → 三级缓存 → 为什么三级（AOP代理）→ AOP代理什么时候生成（BeanPostProcessor后置）
```

### 构造器注入为什么解决不了循环依赖

构造器注入要求 new 和 DI 同一步完成，没有机会先 new 出原始对象放入三级缓存，无法提前暴露，Spring 直接报错。

---

## 五、事务传播机制

### 背景

两个有 @Transactional 的方法互相调用，事务怎么算？用同一个还是各自开新的？

### 7种传播级别（记住前3个）

```
REQUIRED（默认）
→ 有事务就加入，没有就新建
→ A调B，B加入A的事务，一起提交一起回滚

REQUIRES_NEW
→ 不管有没有，都新建独立事务
→ A调B，B挂起A的事务，自己开新事务，互不影响

NESTED
→ 在当前事务里创建嵌套事务（保存点）
→ B失败只回滚到保存点，不影响A
```

### REQUIRED vs REQUIRES_NEW 对比

| | REQUIRED | REQUIRES_NEW |
|--|----------|--------------|
| 有外部事务 | 加入外部事务 | 挂起外部事务，新建 |
| 外层回滚 | 一起回滚 | 不受影响 |
| 自己回滚 | 外层也回滚 | 不影响外层 |
| 适合场景 | 需要原子性 | 需要独立提交（如日志记录） |

### 经典反例

```java
@Transactional
public void placeOrder() {
    orderMapper.insert(order);        // 事务1
    stockService.decreaseStock();     // REQUIRES_NEW，独立事务2，已提交
    throw new RuntimeException();     // 事务1回滚
}
// 结果：订单没创建，但库存扣了 → 数据不一致！
// 正确做法：用默认REQUIRED，两个操作在同一事务
```

### ⚠️ 事务不生效的四种情况

```
1. 自调用：this.xxx() 绕过代理 → 用 self.xxx()
2. 方法非public：AOP代理不到
3. 异常被吞：catch后没抛出，Spring感知不到，不回滚
4. 异常类型不对：默认只回滚RuntimeException
   → 加 @Transactional(rollbackFor = Exception.class)
```

---

*（持续更新中...）*
