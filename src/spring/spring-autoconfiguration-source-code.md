# Spring Boot 自动装配原理（Why → What → How）

---

## 一、WHY：为什么需要自动装配？

### 没有 Spring Boot 的年代做了什么

假设你要用 MyBatis 访问数据库，纯 Spring 时代需要写一堆 XML：

```xml
<!-- 数据源 -->
<bean id="dataSource" class="com.alibaba.druid.pool.DruidDataSource">
    <property name="url" value="${jdbc.url}"/>
    <property name="username" value="${jdbc.username}"/>
    <property name="password" value="${jdbc.password}"/>
    <!-- 还有连接池、超时、心跳... -->
</bean>

<!-- SqlSessionFactory -->
<bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
    <property name="dataSource" ref="dataSource"/>
    <property name="mapperLocations" value="classpath:mapper/*.xml"/>
    <!-- 还有插件、类型处理器... -->
</bean>

<!-- MapperScanner -->
<bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
    <property name="basePackage" value="com.example.mapper"/>
</bean>
```

**每引入一个技术栈，都要手动写一大堆样板 Bean 定义。** 十个项目要配十遍，每次配置还不一样——版本升级、配置项变化，都是心智负担。

### 自动装配把"写 XML 配 Bean"这一步吞掉了

```
以前：加 Maven 依赖 → 写 XML 配 Bean → 配 PropertyPlaceholder → 调试 → 能用
现在：加 Maven 依赖 → 配 yml → 直接用
```

用 MyBatis 举例，对比一清二楚：

| 步骤 | 以前（Spring + XML） | 现在（Spring Boot） |
|------|---------------------|-------------------|
| 1. 引入依赖 | `pom.xml` 加 `mybatis-spring` | `pom.xml` 加 `mybatis-spring-boot-starter` |
| 2. 配 Bean | 手写 3 个 Bean（DataSource、SqlSessionFactory、MapperScanner） | **不需要，自动装配帮你建好了** |
| 3. 配属性 | `jdbc.properties` + `PropertyPlaceholder` | `application.yml` 写几行 |
| 4. 调试 | Bean 间依赖关系配错，启动报错排查半天 | 内部已经串好了，基本不出错 |
| 5. 使用 | `@Autowired` 注入 | `@Autowired` 注入（一样） |

**被吞掉的那一步刚好就是自动装配做的事：** jar 包里的 `xxx.AutoConfiguration.imports` 文件告诉 Spring"这个 jar 里有这些配置类"，Spring 启动时自动加载，条件满足就帮你把 Bean 建好放进容器。

### 自动装配要解决的核心问题

```
引入 jar → 自动配好 → 开箱即用
```

三个关键点：

| 痛点 | 自动装配怎么解决 |
|------|-----------------|
| 样板配置太多 | 引入 jar，Bean 自动创建 |
| 配置项记不住 | `@ConfigurationProperties` 绑定 yml，IDE 自动补全 |
| 不小心覆盖 | `@ConditionalOnMissingBean`，用户自己配了就不自动创建 |

**"配置项记不住"是怎么解决的——yml → Bean 完整链路：**

以 DeepSeek Starter 为例，你只需要在 yml 里写：

```yaml
deepseek:
  api-key: sk-xxxx
  model: deepseek-chat
```

就能注入一个配置好的 `DeepSeekClient`。这条链路是怎么走通的？

```
application.yml         ①  @ConfigurationProperties  ②  AutoConfiguration  ③     @Bean
   deepseek:       →       DeepSeekProperties          @Bean方法参数注入        new DeepSeekClient(properties)
     api-key: sk-xxx     getApiKey() = "sk-xxxx"    →  拿到 apiKey       →      放进客户端
```

拆开每一步：

**① yml → Properties 对象（@ConfigurationProperties）**

```java
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {
    private String apiKey;          // 对应 deepseek.api-key
    private String model = "deepseek-chat";  // 有默认值
}
```

Spring Boot 启动时会调用 `ConfigurationPropertiesBindingPostProcessor`，把 yml 里的值按字段名映射到 `DeepSeekProperties` 对象上。`api-key`（kebab-case）自动映射到 `apiKey`（camelCase）。

**② Properties → AutoConfiguration（@EnableConfigurationProperties）**

```java
@AutoConfiguration
@EnableConfigurationProperties(DeepSeekProperties.class)  // 注册 Properties 为 Bean
public class DeepSeekAutoConfiguration {
    
    @Bean
    DeepSeekClient deepSeekClient(DeepSeekProperties properties) {
        // properties.getApiKey() 已经拿到 yml 里的值了
        return new DeepSeekClient(properties);
    }
}
```

`@EnableConfigurationProperties` 做了两件事：把 `DeepSeekProperties` 注册为 Bean，然后它可以被注入到 `@Bean` 方法参数里。

**③ Properties → Bean 构造参数**

```java
public class DeepSeekClient {
    public DeepSeekClient(DeepSeekProperties properties) {
        this.apiKey = properties.getApiKey();     // → "sk-xxxx"
        this.baseUrl = properties.getBaseUrl();    // → "https://api.deepseek.com"
        this.model = properties.getModel();        // → "deepseek-chat"
    }
}
```

**④ IDE 自动补全怎么来的**

`spring-boot-configuration-processor` 编译时扫描 `@ConfigurationProperties` 类，生成 `spring-configuration-metadata.json`：

```json
{
  "properties": [
    {
      "name": "deepseek.api-key",
      "type": "java.lang.String",
      "description": "API Key（必填）"
    },
    {
      "name": "deepseek.model",
      "type": "java.lang.String",
      "description": "模型名称",
      "defaultValue": "deepseek-chat"
    }
  ]
}
```

IDEA 读到这个 JSON，你敲 `deepseek.` 时就弹出可选的属性名、类型和描述——不用再翻文档记配置项了。

**一句话白话：** 自动装配不止是"帮你创建 Bean"，还包括"帮你把 yml 里的值自动绑到 Bean 上"。`@ConfigurationProperties` 是 yml 和 Bean 之间的那座桥。

---

## 二、WHAT：自动装配是什么？

> 引入一个 jar 包后，Spring Boot 自动帮你创建好对应的 Bean，放进容器里等着你用。

用大白话说就是：你以前用 MyBatis 得自己写 SqlSessionFactory，现在引入 `mybatis-spring-boot-starter`，它自动帮你把 SqlSessionFactory 建好了，你只管注入用就行。

**和 @ComponentScan 的区别：**

| 机制 | 扫描范围 | 触发条件 |
|------|---------|---------|
| `@ComponentScan` | 启动类所在包及子包 | 类上有 @Component/@Service 等 |
| 自动装配 | **jar 包里的类** | classpath 有对应的 jar + 条件满足 |

@ComponentScan 扫的是你自己的代码，自动装配扫的是第三方 jar 里的配置类——这是本质区别。

---

## 三、HOW：自动装配底层是怎么做的

### 3.1 总览：一条完整的调用链

```
SpringApplication.run()
  └── refresh()                                    ← Spring 容器启动核心
      └── invokeBeanFactoryPostProcessors()         ← 执行所有 BeanFactoryPostProcessor
          └── ConfigurationClassPostProcessor       ← 处理 @Configuration 和 @Import
              └── @Import 的处理                    ← 这里串联起自动装配
                  ├── AutoConfigurationImportSelector.selectImports()
                  │   └── 从 xxx.AutoConfiguration.imports 加载候选类
                  │   └── ConditionEvaluator 筛选
                  │   └── 返回符合条件的配置类名
                  └── 被返回的类作为 @Configuration 继续解析
                      └── @Bean 方法 → 创建 Bean 实例
```

下面逐层拆解。

### 3.2 第一层：SpringApplication.run() → refresh()

```java
// SpringApplication.java 源码
public ConfigurableApplicationContext run(String... args) {
    // ... 省略准备阶段 ...
    refresh(context);  // ← 关键：调用 Spring 容器的 refresh()
    // ... 省略后续阶段 ...
}

private void refresh(ConfigurableApplicationContext applicationContext) {
    // 如果 ServletWebServerApplicationContext
    // → refresh() 最终调用 AbstractApplicationContext.refresh()
}
```

`refresh()` 是 Spring IoC 容器初始化的入口，里面有十几个步骤，自动装配发生在 `invokeBeanFactoryPostProcessors()` 这一步。

### 3.3 第二层：invokeBeanFactoryPostProcessors()

```java
// AbstractApplicationContext.java 源码
@Override
public void refresh() throws BeansException, IllegalStateException {
    // 1. prepareRefresh()
    // 2. obtainFreshBeanFactory()
    // 3. prepareBeanFactory()
    // 4. postProcessBeanFactory()
    // 5. invokeBeanFactoryPostProcessors(beanFactory)  ← 自动装配的核心在这！
    // 6. registerBeanPostProcessors()
    // 7. initMessageSource()
    // ... 后面还有初始化 Bean 等步骤
}
```

`BeanFactoryPostProcessor` 是什么？它在**所有 Bean 创建之前**运行，可以修改 Bean 定义。

`ConfigurationClassPostProcessor` 是其中最核心的一个——它负责解析 `@Configuration`、`@Import`、`@ComponentScan`、`@Bean` 等注解。

### 3.4 第三层：ConfigurationClassPostProcessor —— 理解 @Import 如何被处理

这是连接 "Spring 框架" 和 "Spring Boot 自动装配" 的桥梁。

```java
// ConfigurationClassPostProcessor.java 源码
// 它的核心工作是：
// 1. 找到所有 @Configuration 类
// 2. 解析里面的 @ComponentScan、@Import、@Bean 等
// 3. 把解析结果注册到 BeanDefinitionMap

@Override
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    processConfigBeanDefinitions(registry);
}
```

处理过程中，遇到 `@Import(AutoConfigurationImportSelector.class)` 时：

```java
// ConfigurationClassParser.java 源码（简化）
private void processImports(ConfigurationClass configClass, ...) {
    // 1. 发现 @Import 引入的类是 ImportSelector 类型
    if (candidate.isAssignableFrom(ImportSelector.class)) {
        // 2. 实例化 AutoConfigurationImportSelector
        ImportSelector selector = BeanUtils.instantiateClass(candidate);
        // 3. 调用 selectImports() —— 这就是自动装配的入口！
        String[] imports = selector.selectImports(currentSourceClass.getMetadata());
        // 4. 递归处理返回的配置类 → 又回到 processConfigBeanDefinitions
        processConfigurationClass(imports);
    }
}
```

**关键理解：** `@Import` 是 Spring 框架原生的机制，`AutoConfigurationImportSelector` 是 Spring Boot 的实现。Spring 框架不认识自动装配，它只认识 `@Import`——Spring Boot 把"自动装配"这件事伪装成了 `@Import` 的一个实现。

### 3.5 第四层：AutoConfigurationImportSelector —— 自动装配的"导演"

#### 3.5.1 selectImports() 入口

```java
// AutoConfigurationImportSelector.java 源码
@Override
public String[] selectImports(AnnotationMetadata annotationMetadata) {
    if (!isEnabled(annotationMetadata)) {
        return NO_IMPORTS;  // 可通过 spring.boot.enableautoconfiguration=false 关闭
    }
    AutoConfigurationEntry entry = getAutoConfigurationEntry(annotationMetadata);
    return StringUtils.toStringArray(entry.getConfigurations());
}
```

#### 3.5.2 getAutoConfigurationEntry() —— 加载 + 多层过滤

```java
protected AutoConfigurationEntry getAutoConfigurationEntry(
        AnnotationMetadata metadata) {
    // ===== Step 1: 加载候选配置类 =====
    List<String> configurations = getCandidateConfigurations(metadata, attributes);

    // ===== Step 2: 去重 =====
    configurations = removeDuplicates(configurations);

    // ===== Step 3: 排除用户主动 Exclude 的 =====
    // 对应 @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    Set<String> exclusions = getExclusions(metadata, attributes);
    configurations.removeAll(exclusions);

    // ===== Step 4: 条件过滤（最关键的一步）=====
    configurations = filter(configurations, autoConfigurationMetadata);

    // ===== Step 5: 发布自动装配事件 =====
    fireAutoConfigurationImportEvents(configurations, exclusions);

    return new AutoConfigurationEntry(configurations, exclusions);
}
```

#### 3.5.3 filter() 的核心 —— ConditionEvaluator

```java
private List<String> filter(List<String> configurations,
        AutoConfigurationMetadata autoConfigurationMetadata) {
    List<String> result = new ArrayList<>(configurations);
    // 遍历每个配置类，逐个评估其 @Conditional 条件
    result.removeIf(configurationClassName -> {
        // 获取该配置类上的所有 @Conditional 条件
        String[] conditions = autoConfigurationMetadata
            .getConditions(configurationClassName);
        for (String condition : conditions) {
            // 调用 ConditionEvaluator 逐一判断
            if (!conditionEvaluator.shouldSkip(condition)) {
                return false; // 保留
            }
        }
        return true; // 移除（条件不满足）
    });
    return result;
}
```

#### 3.5.4 候选配置从哪里来？—— ImportCandidates

Spring Boot 3.x 使用 `ImportCandidates.load()` 读取配置文件：

```java
// AutoConfigurationImportSelector.java 源码
protected List<String> getCandidateConfigurations(
        AnnotationMetadata metadata, AnnotationAttributes attributes) {
    // 加载 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    List<String> configurations = ImportCandidates
        .load(AutoConfiguration.class, getBeanClassLoader())
        .getCandidates();
    return configurations;
}

// ImportCandidates.java 源码
public static ImportCandidates load(Class<?> annotation, ClassLoader classLoader) {
    // 拼接文件名
    String location = String.format(
        "META-INF/spring/%s.imports", annotation.getName());
    // 从所有 jar 包的 classpath 中读取这个文件
    Enumeration<URL> urls = classLoader.getResources(location);
    // 每个 jar 里的配置类拼成一个列表
    List<String> candidates = new ArrayList<>();
    while (urls.hasMoreElements()) {
        // 逐行读取，每行就是一个配置类的全限定名
        candidates.addAll(readLines(urls.nextElement()));
    }
    return new ImportCandidates(candidates);
}
```

**这里有三个关键点：**

1. **SPI 机制**：Spring Boot 通过 `classLoader.getResources()` 扫描所有 jar 包，拼成候选列表——和 Java SPI 思想一致
2. **每个 Starter 都可以贡献**：你的 `deepseek-spring-boot-starter` 里有 `AutoConfiguration.imports`，Spring Boot 就能扫到
3. **100+ 候选 → 最终只有几十个生效**：全靠 Condition 过滤

### 3.6 第五层：@Conditional 条件注解 —— 如何判断"要不要加载"

以 `@ConditionalOnClass` 为例，看条件判断的最低层：

```java
// OnClassCondition.java 源码（简化）
public class OnClassCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context,
            AnnotatedTypeMetadata metadata) {
        // 读取注解中指定的类名
        String[] classNames = (String[]) metadata
            .getAnnotationAttributes(ConditionalOnClass.class.getName())
            .get("value");
        // 尝试用 ClassLoader 加载这些类
        for (String className : classNames) {
            if (!isPresent(className, context.getClassLoader())) {
                // 类不存在 → 条件不满足 → 跳过这个配置类
                return ConditionOutcome.noMatch(
                    "required class not found: " + className);
            }
        }
        return ConditionOutcome.match(); // 所有类都存在 → 通过
    }

    // 到底层：直接用类加载器去 loadClass
    private boolean isPresent(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;  // 类不在 classpath → 条件不满足
        }
    }
}
```

**所以 `@ConditionalOnClass(DataSource.class)` 的本质就是：** 用 `Class.forName("javax.sql.DataSource")` 去 classpath 里找，找到了就继续配，找不到就跳过。

### 3.7 DataSourceAutoConfiguration 完整拆解

用一个真实的配置类，把上面所有环节串起来看：

```java
// DataSourceAutoConfiguration.java 源码（简化版）
@AutoConfiguration  // ① 标记为自动配置类，会被 ImportCandidates 扫描到
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
// ② 第一道门：classpath 有 DataSource 类吗？
//    本质是 Class.forName("javax.sql.DataSource")
//    引入 spring-boot-starter-jdbc 就有 → 通过
//    没引入 → 跳过整个类
@EnableConfigurationProperties(DataSourceProperties.class)
// ③ 把 application.yml 里的 spring.datasource.* 绑定到 DataSourceProperties
@Import({ DataSourcePoolMetadataProvidersConfiguration.class, ... })
public class DataSourceAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    // ④ 第二道门：用户已经手动定义了 DataSource 吗？
    //    scan 容器里有没有 DataSource 类型的 BeanDefinition
    //    有 → 跳过（尊重用户配置）
    //    没有 → 继续往下走
    static class EmbeddedDatabaseConfiguration {

        @Bean
        DataSource dataSource(DataSourceProperties properties) {
            // ⑤ 真正创建 Bean
            return properties.initializeDataSourceBuilder().build();
        }
    }
}
```

**执行顺序：**

```
1. ClassLoader：classpath 有 DataSource.class 吗？→ 有 → 继续
2. BeanDefinitionMap：容器里有用户定义的 DataSource 吗？→ 没有 → 继续
3. @Bean 方法执行 → new DataSource() → 放进容器
```

---

## 四、两张图总结

### 纵向：时间线

```
SpringApplication.run()
  → refresh()
    → invokeBeanFactoryPostProcessors()
      → ConfigurationClassPostProcessor 解析 @Configuration
        → 遇到 @Import(AutoConfigurationImportSelector.class)
          → selectImports()
            → ImportCandidates.load()     ← 扫所有 jar 的 xxx.imports
            → ConditionEvaluator.shouldSkip()  ← 逐类判断 @Conditional
            → 返回符合条件的配置类名
        → Spring 把返回的类当 @Configuration 处理
          → 执行里面的 @Bean 方法
            → Bean 实例进入容器
```

### 横向：分层架构

```
┌─────────────────────────────────────────┐
│              应用层                      │
│  @SpringBootApplication                 │
│  spring.datasource.url=jdbc:mysql://...  │
├─────────────────────────────────────────┤
│              Spring Boot 层              │
│  @EnableAutoConfiguration               │
│  AutoConfigurationImportSelector        │
│  xxx.AutoConfiguration.imports          │
│  ConditionEvaluator                     │
├─────────────────────────────────────────┤
│              Spring Framework 层         │
│  @Import 机制                            │
│  ConfigurationClassPostProcessor        │
│  ConfigurationClassParser               │
│  refresh() → invokeBeanFactoryPostPro.. │
├─────────────────────────────────────────┤
│              JVM 层                      │
│  ClassLoader.getResources()  ← 扫描 jar │
│  Class.forName()            ← 条件判断   │
└─────────────────────────────────────────┘
```

---

## 五、面试回答模板

问："Spring Boot 自动装配是什么原理？"

答：

> Spring Boot 通过 `@EnableAutoConfiguration` 注解上的 `@Import(AutoConfigurationImportSelector.class)` 触发自动装配。
>
> 启动时，Spring 的 `ConfigurationClassPostProcessor` 解析 `@Import`，调用 `selectImports()` 方法。内部通过 `ImportCandidates.load()` 扫描所有 jar 包里的 `META-INF/spring/xxx.AutoConfiguration.imports` 文件，拿到 100+ 个候选自动配置类。
>
> 然后用 `ConditionEvaluator` 逐一评估 `@ConditionalOnClass`、`@ConditionalOnBean` 等条件注解——底层其实就是 `Class.forName()` 检查类在不在 classpath，或者扫 BeanDefinitionMap 检查 Bean 存不存在。
>
> 满足条件的配置类被 Spring 当做 `@Configuration` 处理，里面的 `@Bean` 方法被执行，实例进入容器。没满足条件的直接跳过。
>
> 自定义 Starter 时，我只需要写一个 `@AutoConfiguration` 类，配上 `@ConditionalOnXxx`，然后在 `AutoConfiguration.imports` 文件里注册它就行。
