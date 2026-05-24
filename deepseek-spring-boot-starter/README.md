# DeepSeek Spring Boot Starter

> 封装 DeepSeek API 调用的自定义 Spring Boot Starter。

---

## 一、项目结构

```
deepseek-spring-boot-starter/
└── src/main/
    ├── java/com/interview/deepseek/
    │   ├── DeepSeekAutoConfiguration.java   ← 自动配置类
    │   ├── DeepSeekProperties.java           ← 配置属性（application.yml）
    │   ├── DeepSeekClient.java               ← API 调用客户端
    │   ├── DeepSeekChatRequest.java           ← 请求模型
    │   └── DeepSeekChatResponse.java          ← 响应模型
    └── resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## 二、自动装配生效流程

```
应用启动
    ↓
@SpringBootApplication → @EnableAutoConfiguration
    ↓
AutoConfigurationImportSelector 读取
  META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    ↓
找到 com.interview.deepseek.DeepSeekAutoConfiguration
    ↓
检查条件：
  ✅ @ConditionalOnClass(DeepSeekClient.class)   → 类存在
  ✅ @ConditionalOnProperty(prefix="deepseek", name="api-key") → 配置了 key
    ↓
创建 DeepSeekClient Bean → 注入容器
```

---

## 三、条件注解的作用

| 注解 | 作用 | 不满足时 |
|------|------|---------|
| `@ConditionalOnClass(DeepSeekClient.class)` | classpath 存在本类 | 不创建 Bean |
| `@ConditionalOnProperty(prefix="deepseek", name="api-key")` | yml 配置了 `deepseek.api-key` | 不创建 Bean |
| `@ConditionalOnMissingBean` | 容器中没有用户手动定义的 Bean | 用户可覆盖 |

---

## 四、如何使用

### 4.1 引入依赖

```xml
<dependency>
    <groupId>com.interview</groupId>
    <artifactId>deepseek-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 4.2 配置 application.yml

```yaml
deepseek:
  api-key: sk-xxxxxxxxxxxx
  base-url: https://api.deepseek.com   # 可选，默认官方地址
  model: deepseek-chat                  # 可选
  max-tokens: 2048                      # 可选
  temperature: 0.7                      # 可选
  connect-timeout: 30000                # 可选
  read-timeout: 60000                   # 可选
```

### 4.3 注入使用

```java
@RestController
public class ChatController {

    @Autowired
    private DeepSeekClient deepSeekClient;

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        // 简单对话
        return deepSeekClient.chat(message);
    }

    @GetMapping("/chat-with-prompt")
    public String chatWithPrompt(@RequestParam String message) {
        // 带 system prompt
        return deepSeekClient.chat("你是一个Java专家", message);
    }
}
```

### 4.4 高级用法：多轮对话

```java
DeepSeekChatRequest request = new DeepSeekChatRequest();
request.setModel("deepseek-chat");
request.setMaxTokens(4096);
request.setMessages(Arrays.asList(
    new DeepSeekChatRequest.Message("system", "你是一个Java面试官"),
    new DeepSeekChatRequest.Message("user", "什么是Spring的自动装配？"),
    new DeepSeekChatRequest.Message("assistant", "自动装配是..."),
    new DeepSeekChatRequest.Message("user", "那@Conditional注解呢？")
));

DeepSeekChatResponse response = deepSeekClient.chat(request);
System.out.println(response.getContent());
```

---

## 五、如何测试 Starter（在 demo-app 中使用）

### 5.1 创建测试应用

```bash
mkdir -p demo-app/src/main/java/com/example
mkdir -p demo-app/src/main/resources
```

**pom.xml：**
```xml
<dependency>
    <groupId>com.interview</groupId>
    <artifactId>deepseek-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**application.yml：**
```yaml
deepseek:
  api-key: sk-你的key
```

**DemoApplication.java：**
```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

### 5.2 验证自动装配是否生效

启动时观察日志，或者写一个 CommandLineRunner：

```java
@Bean
CommandLineRunner testDeepSeek(DeepSeekClient client) {
    return args -> {
        String reply = client.chat("什么是Java的volatile关键字？");
        System.out.println("DeepSeek 回复: " + reply);
    };
}
```

如果 `client` 注入成功 → 自动装配生效。
如果启动报 `NoSuchBeanDefinitionException` → 检查是否配置了 `deepseek.api-key`。

---

## 六、关键设计点总结

| 设计点 | 实现方式 | 效果 |
|--------|---------|------|
| 自动发现配置类 | `AutoConfiguration.imports` 文件 | Spring 启动时自动读取 |
| 条件激活 | `@ConditionalOnProperty` | 不配 key 不创建 Bean |
| 配置绑定 | `@ConfigurationProperties(prefix="deepseek")` | yml 属性自动映射 |
| 用户可覆盖 | `@ConditionalOnMissingBean` | 用户可自定义 DeepSeekClient |
| 配置提示 | `spring-boot-configuration-processor` | IDE 中写 yml 有自动补全 |
