# Spring Boot 自动装配原理（源码级）

> 从 `@SpringBootApplication` 一路跟到 Bean 创建，拆解自动装配的每一环。

---

## 一、入口：@SpringBootApplication

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

`@SpringBootApplication` 是一个组合注解：

```java
// Spring Boot 源码
@SpringBootConfiguration  // = @Configuration
@EnableAutoConfiguration  // ← 自动装配的核心入口
@ComponentScan(
    excludeFilters = @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
)
public @interface SpringBootApplication { }
```

关键在 `@EnableAutoConfiguration`，它就是自动装配的"开关"。

---

## 二、核心：@EnableAutoConfiguration

```java
// Spring Boot 源码
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage       // ① 记录启动类所在包，供后续扫描
@Import(AutoConfigurationImportSelector.class)  // ② 核心：导入选择器
public @interface EnableAutoConfiguration { }
```

---

## 三、AutoConfigurationImportSelector：自动装配的"导演"

这是整个自动装配最核心的类，它决定加载哪些配置类。

### 3.1 入口方法：selectImports()

```java
// 源码位置：AutoConfigurationImportSelector.java
@Override
public String[] selectImports(AnnotationMetadata annotationMetadata) {
    if (!isEnabled(annotationMetadata)) {
        return NO_IMPORTS;
    }
    // ① 加载候选自动配置类
    AutoConfigurationEntry entry = getAutoConfigurationEntry(annotationMetadata);
    // ② 返回全限定类名数组
    return StringUtils.toStringArray(entry.getConfigurations());
}
```

### 3.2 getAutoConfigurationEntry()：加载 + 过滤

```java
// 源码位置：AutoConfigurationImportSelector.java
protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata metadata) {
    // 1. 加载所有候选配置类（从 META-INF/spring/xxx.imports 文件读取）
    List<String> configurations = getCandidateConfigurations(metadata, attributes);

    // 2. 去重
    configurations = removeDuplicates(configurations);

    // 3. 过滤：排除 @EnableAutoConfiguration(exclude = {...}) 指定的类
    Set<String> exclusions = getExclusions(metadata, attributes);
    configurations.removeAll(exclusions);

    // 4. 过滤：Conditional 条件注解（@ConditionalOnClass/@ConditionalOnBean 等）
    configurations = filter(configurations, autoConfigurationMetadata);

    // 5. 发布事件（可被监听）
    fireAutoConfigurationImportEvents(configurations, exclusions);

    return new AutoConfigurationEntry(configurations, exclusions);
}
```

### 3.3 配置源在哪里？

**Spring Boot 2.x：`META-INF/spring.factories`**

```properties
# spring-boot-autoconfigure.jar!/META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.boot.autoconfigure.aop.AopAutoConfiguration,\
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
...  # 100+ 个自动配置类
```

**Spring Boot 3.x：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**

```
# spring-boot-autoconfigure.jar!/META-INF/spring/...
org.springframework.boot.autoconfigure.aop.AopAutoConfiguration
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
...
```

每个配置类一行，更简洁。

---

## 四、条件注解：加载的"守门员"

每个自动配置类内部用 `@Conditional` 系列注解控制是否生效：

```java
// 源码示例：DataSourceAutoConfiguration.java
@AutoConfiguration
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })  // classpath 有这些类才加载
@EnableConfigurationProperties(DataSourceProperties.class)             // 绑定 application.yml 配置
@Import({ DataSourcePoolMetadataProvidersConfiguration.class, ... })
public class DataSourceAutoConfiguration {

    @Configuration
    @ConditionalOnMissingBean(DataSource.class)   // 用户没手动定义 Bean 时才创建
    static class EmbeddedDatabaseConfiguration {
        @Bean
        DataSource dataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().build();
        }
    }
}
```

### 常用条件注解一览

| 注解 | 条件 | 场景 |
|------|------|------|
| `@ConditionalOnClass` | classpath 存在指定类 | "引入依赖就自动配置"的关键 |
| `@ConditionalOnMissingClass` | classpath 不存在指定类 | 降级方案 |
| `@ConditionalOnBean` | 容器中存在指定 Bean | 依赖其他配置完成 |
| `@ConditionalOnMissingBean` | 容器中不存在指定 Bean | 允许用户覆盖 |
| `@ConditionalOnProperty` | 配置文件有指定属性 | 通过配置开关功能 |
| `@ConditionalOnResource` | 存在指定资源文件 | 按文件判断 |
| `@ConditionalOnWebApplication` | 当前是 Web 应用 | 区分 Web 和非 Web |

**条件判断发生在"配置类解析阶段"**，在 Spring 容器 refresh() 的 `invokeBeanFactoryPostProcessors()` 这一步。

---

## 五、完整流程串联（面试答法）

```
1. @SpringBootApplication 中的 @EnableAutoConfiguration
       ↓
2. @Import(AutoConfigurationImportSelector.class)
       ↓
3. selectImports()
       ↓
4. 从 META-INF/spring/xxx.imports 读取 100+ 候选配置类
       ↓
5. 按 @ConditionalOnXxx 过滤：classpath 有 jar？用户配了参数？容器里有其他 bean？
       ↓
6. 剩下满足条件的配置类 → 被 Spring 当做 @Configuration 类处理
       ↓
7. 里面的 @Bean 方法被调用 → 创建 Bean 实例放入容器
```

**一句话：** `AutoConfigurationImportSelector` 从 `xxx.imports` 文件中读取所有候选配置类，用 `@Conditional` 系列注解筛选，满足条件的被 Spring 当做 `@Configuration` 类处理。

---

## 六、自定义 Starter 的关键点

自定义 Starter 只需要做两步：

### 1. 创建自动配置类

```java
@AutoConfiguration
@EnableConfigurationProperties(XxxProperties.class)
@ConditionalOnClass(XxxClient.class)       // 引入 jar 才生效
public class XxxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean              // 用户可以自己覆盖
    public XxxService xxxService(XxxProperties properties) {
        return new XxxService(properties);
    }
}
```

### 2. 注册配置类

**Spring Boot 3.x：** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Spring Boot 2.x：** `META-INF/spring.factories`

### 3. 配置属性类

```java
@ConfigurationProperties(prefix = "xxx")
public class XxxProperties {
    private String apiKey;
    private String baseUrl = "https://api.xxx.com";
    // getters/setters
}
```

### 为什么 starter 是一个空 jar？

```
starter 项目结构：
  xxx-spring-boot-starter/
  ├── pom.xml          ← 依赖 autoconfigure 模块 + 第三方 jar
  └── (没有 Java 代码)

  xxx-spring-boot-autoconfigure/
  ├── pom.xml
  └── src/main/
      ├── java/.../XxxAutoConfiguration.java
      ├── java/.../XxxProperties.java
      └── resources/META-INF/spring/
          └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

**starter 只负责传递依赖**，自动配置代码在 autoconfigure 模块里。对于简单场景可以合并——代码直接写在 starter 里。
