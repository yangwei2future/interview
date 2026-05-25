# API 网关 AK/SK 签名认证原理

> 基于项目 `dip-open-platform-api-gateway` 实际实现（`SignUtil.java`）
> 签名协议：HMAC-SHA256，参考阿里云 ACS3 规范

---

## 一、先搞懂 HMAC 的输入和输出

在开始看整个签名流程之前，先搞懂 HMAC 的两个关键概念：

### HMAC 有两个输入，一个输出

```
HMAC( 密钥 , 消息 ) = 签名

     ↑         ↑          ↑
    SK      待签串      signature
```

| 角色 | 是什么 | 放在哪 |
|------|--------|--------|
| **密钥** | SK（Access Key Secret） | `mac.init(signingKey)` — 密钥层 |
| **消息** | 6 个请求要素拼成的规范串做 SHA256 | `mac.doFinal(stringToSign)` — 数据层 |
| **输出** | 签名（signature），Base64 编码后放 HTTP Header | `x-dip-signature` |

**密钥和消息是分离的——SK 不进签名字符串，只用来初始化 HMAC。**

### 为什么是 HMAC 而不是 SHA256？

```
SHA256("AK=xxx,timestamp=123,...")          ← 没有 SK：攻击者能算出来
HMAC-SHA256(SK, "AK=xxx,timestamp=123,...") ← 有 SK：攻击者算不出来
```

SHA256 是无密钥哈希，谁都能算。攻击者知道 AK、知道算法，改了参数重新 `SHA256(...)` 就能伪造签名。HMAC 必须知道 SK 才能算出正确签名，攻击者没有 SK 就无从下手。

---

## 二、签名总览：SHA256 压缩 → HMAC 签名（共两步）

整个流程分三步，看图：

```
                    客户端发请求前
                    ═══════════════
                    
  HTTP Method  ─┐
  URI          ─┤
  QueryString  ─┤  拼成              压缩
  Headers      ─┼──────────> 规范请求串 ──SHA256──> 64字符摘要
  SignedHdrs   ─┤
  Body(SHA256) ─┘
                                                    │
                                            HMAC-SHA256(SK, 摘要)
                                                    │
                                                    ↓
                                              Base64 编码
                                                    │
                                                    ↓
                                          x-dip-signature

                    服务端验证时
                    ═══════════
  收到请求 → 提取同样的 6 个要素 → 重算签名 → 比对 x-dip-signature
                                                 │
                                        一致 → 放行 / 不一致 → 401
```

**核心原则：客户端和服务端算签名的每一步必须完全一样，任何一个字节不同都会导致签名对不上。**

---

## 三、逐步拆解（结合你的代码）

### Step 0：构造签名请求对象

```java
// SignUtil.java:23-44 — SignatureRequest 内部类
SignatureRequest req = new SignatureRequest(httpMethod, path, timestamp, ak);

// 构造时做了什么：
// 1. 记录 HTTP Method（GET/POST/DELETE）
// 2. 记录请求 URI 路径
// 3. 记录 Timestamp（参与签名 + 防重放）
// 4. 设置扩展 headers：
//    - x-dip-key: AK（明文传输，让服务端知道用哪个 SK 来验签）
//    - x-dip-nonce: UUID 随机数（防重放）
//    - x-dip-timestamp: 时间戳（参与签名 + 时间窗口校验）
```

**注意：AK 是身份标识（相当于用户名），SK 是密钥（相当于密码）。AK 可以明文传，SK 绝不能。**

### Step 1：拼接规范请求串（Canonical Request）

规范请求串 = **6 个部分用换行符 `\n` 连接**：

```
格式：
POST                         ← ① HTTP Method
/api/v1/indicator/search    ← ② URI 路径
key1=val1&key2=val2         ← ③ QueryString（排序后 + URL编码）
x-dip-key:AK123\nx-dip-nonce:uuid\n... ← ④ 规范化的 Header（排序后）
x-dip-key;x-dip-nonce;...   ← ⑤ 签了哪些 Header（分号分隔）
abc123def456...             ← ⑥ Body 的 SHA256 哈希值
```

对应你的代码 `SignUtil.java:88`：

```java
String canonicalRequest = 
    signatureRequest.httpMethod + "\n" +          // ①
    signatureRequest.canonicalUri + "\n" +        // ②
    canonicalQueryString + "\n" +                 // ③
    canonicalHeaders + "\n" +                     // ④
    signedHeaders + "\n" +                        // ⑤
    hashedRequestPayload;                         // ⑥
```

#### 逐个解释每个部分

**① HTTP Method**：就是 `GET` / `POST` / `DELETE`。防方法篡改——攻击者拿 GET 的签名发 POST 请求，签名对不上。

**② URI 路径**：如 `/api/v1/indicator/search`。防路径篡改——拿 `/query` 的签名访问 `/delete` 会被拒绝。

**③ CanonicalQueryString**：Query 参数按 key **字母升序排列 + URL 编码**后拼接。

```java
// SignUtil.java:57-65
// 为什么要排序？
// 客户端传 ?b=2&a=1 → HashMap 可能是 a=1,b=2 或 b=2,a=1
// 不排序的话顺序不同 → 签名不同 → 验签失败
// TreeMap 天然按 key 排序 → 客户端和服务端算出来的顺序一致
```

**④ CanonicalHeaders**：签了什么**内容**（key:value 逐个列出）。

你的代码只签两类（`SignUtil.java:76-80`）：
- `x-dip-*` 自定义头（AK、Nonce、Timestamp、Token 等）
- `Content-Type` + `content-md5`

实际拼出来的内容：
```
content-md5:abc123...
content-type:application/json
x-dip-key:AK123
x-dip-nonce:550e8400-e29b-...
x-dip-timestamp:1716652800
```

**为什么要选择性签名？** HTTP 请求经过代理、网关时，`User-Agent`、`Accept-Encoding` 等通用头可能被中间件改写。如果把这些也签进去，请求到达服务端时签名就对不上了。

**⑤ SignedHeaders**：签了哪些**字段**（只列 header 名，分号分隔）。

```java
// SignUtil.java:87
// 如：content-md5;content-type;x-dip-key;x-dip-nonce;x-dip-timestamp
```

**④ 和 ⑤ 是一一对应的**——⑤ 里列了哪些 header 名，④ 里就必须有对应的 key:value。验签时服务端先从 ⑤ 知道"客户端签了哪几个 header"，再从请求里提取这些 header 的实际值，拼出 ④，最后算签名比对。

用处是向后兼容：未来加新 header 时，老客户端不签新 header，服务端通过 ⑤ 知道只验证列表里的那些。

**⑥ HashedRequestPayload**：请求 Body 的 SHA256 哈希，不是 Body 原文。

```java
// SignUtil.java:68-70
// GET 请求 Body 为空 → 算空字符串的 SHA256（固定值）
// POST 请求有 Body → 算 Body 的 SHA256
// 为什么是 SHA256(Body) 而不是 Body 原文？
// Body 可能很大（上传文件），哈希后固定 64 字符，性能稳定
```

### Step 2：拼接待签名字符串（String to Sign）

```java
// SignUtil.java:92-93
String hashedCanonicalRequest = sha256Hex(canonicalRequest.getBytes());
String stringToSign = "HmacSHA256" + "\n" + hashedCanonicalRequest;
```

**为什么还要再 SHA256 一次压缩规范请求串？**

规范请求串可能很长（header 多、query 多、Body 大 → 几百上千字符），直接拿长串做 HMAC 效率不高。**先 SHA256 压缩成固定 64 字符的摘要，再对摘要做 HMAC，这样 HMAC 处理的始终是固定长度。**

### Step 3：HMAC 计算签名

```java
// SignUtil.java:107-116
SecretKeySpec signingKey = new SecretKeySpec(sk.getBytes(), "HmacSHA256");
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(signingKey);                                    // SK 作为密钥
byte[] rawHmac = mac.doFinal(stringToSign.getBytes());   // 待签串作为消息
return Base64.getEncoder().encodeToString(rawHmac);      // Base64 输出
```

```
HMAC-SHA256( SK , "HmacSHA256\n" + SHA256(规范请求串) )

  密钥(只有持有者知道)      消息(可以从请求中提取)
```

**最后的 Base64 是什么？**

HMAC 输出是 `byte[]`（二进制），HTTP Header 只能传 ASCII 字符。Base64 编码把二进制转成可打印字符（`A-Za-z0-9+/=`），可以直接放进 `x-dip-signature` header。

---

## 四、防重放攻击：Nonce + Timestamp

签名只能防**篡改**，不能防**重放**——攻击者截获一个合法请求，原封不动重复发送，签名依然有效。

所以需要额外机制：

```
                    ┌─────────────────────────────────────┐
                    │         防重放攻击两层机制            │
                    │                                     │
                    │  Timestamp：请求时间戳               │
                    │  └─ 服务端时间 - Timestamp > 5分钟   │
                    │     → 拒绝（时间窗口过期）            │
                    │                                     │
                    │  Nonce：一次性随机数（UUID）          │
                    │  └─ Nonce 已存在缓存中               │
                    │     → 拒绝（重放攻击）                │
                    └─────────────────────────────────────┘
```

你的代码 `NonceCache.java`：

```java
// 用 Guava Cache 存已使用的 Nonce，5分钟 TTL
private static final Cache<String, Boolean> nonceCache = CacheBuilder.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)  // 跟 Timestamp 窗口一致
    .maximumSize(10000)
    .build();
```

**验证流程：**
1. 先检查 Timestamp 是否在有效窗口内（5 分钟）→ 过期直接拒绝
2. 再检查 Nonce 是否已存在缓存中 → 已存在 = 重放，拒绝
3. 都通过 → 把 Nonce 加入缓存 → 继续签名验证

**为什么 Timestamp 也要参与签名计算？** 攻击者截获请求后改了 Timestamp 想绕过过期校验 → Timestamp 变了 → 规范请求串变了 → 签名对不上 → 除非攻击者有 SK 重算签名。

---

## 五、完整时序图

```
  客户端                                              API Gateway
  ─────                                              ────────────
  
  AK = "your_ak"
  SK = "your_sk"  ← 只有客户端和服务端知道
  │
  │  ① 构造请求：
  │     method = POST
  │     uri = /api/v1/search
  │     query = {keyword: "test"}    → TreeMap 排序
  │     headers = {                       → 只取 x-dip-* + Content-Type
  │       x-dip-key: AK
  │       x-dip-timestamp: 1716652800
  │       x-dip-nonce: UUID
  │       Content-Type: application/json
  │     }                                   → 按 key 升序排列
  │     body = {"page": 1}
  │
  │  ② 拼规范请求串：
  │     POST\n
  │     /api/v1/search\n
  │     keyword=test\n
  │     content-type:application/json\n
  │     x-dip-key:AK\n
  │     x-dip-nonce:uuid\n
  │     x-dip-timestamp:1716652800\n
  │     content-type;x-dip-key;x-dip-nonce;x-dip-timestamp\n
  │     e3b0c44...（空串 SHA256）
  │
  │  ③ 计算：
  │     hashedReq = SHA256(规范请求串)
  │     toSign = "HmacSHA256\n" + hashedReq
  │     signature = Base64(HMAC-SHA256(SK, toSign))
  │
  │  ④ 发请求 ─────────────────────────>  ⑤ 提取 AK
  │     x-dip-key: AK                       │
  │     x-dip-signature: signature          │  ⑥ 根据 AK 查数据库取 SK
  │     x-dip-timestamp: 1716652800         │
  │     x-dip-nonce: uuid                   │  ⑦ 用相同的 6 个要素 + 相同的 SK
  │     ...                                 │     走相同的计算流程
  │                                         │
  │                                         │  ⑧ 比对：
  │                                         │     computed_sign == x-dip-signature ?
  │                                         │     Yes → 200 OK
  │                                         │     No  → 401 Unauthorized
```

---

## 六、复习：3 分钟面试话术

> "我们网关的 AK/SK 签名认证，核心流程分两步：先 SHA256 压缩规范请求串，再 HMAC-SHA256 签名。
>
> **第一步**，把请求的 6 个关键要素拼成规范请求串：HTTP Method、URI、排序后的 QueryString、关键 Header（x-dip-* 系列 + Content-Type、content-md5）、签了哪些 Header 的列表、以及 Body 的 SHA256 摘要。
>
> 这里面有两个设计要点：QueryString 按 key 升序排序是为了客户端和服务端算出来一致；Header 只签业务相关的、不签所有，是因为通用 Header 经过代理可能被改写。
>
> **第二步**，对规范请求串做 SHA256 压缩成 64 字符，然后 `HMAC-SHA256(SK, 压缩后的摘要)`。SK 作为 HMAC 密钥使用，不参与签名字符串拼接——攻击者没有 SK 就算知道算法也算不出签名。
>
> **防重放**用 Timestamp + Nonce 双重校验，Nonce 本地 Guava Cache 5 分钟 TTL，重复请求直接拒绝。Timestamp 也参与了签名，改了 Timestamp 必须重算签名。
>
> 整个方案确保了：请求的任何一个关键要素被篡改，签名都会对不上，攻击者没有 SK 无法伪造。"

---

## 七、考点速查

| 考点 | 你的答案 |
|------|---------|
| 为什么 HMAC 不是 SHA256 | HMAC 有密钥，无 SK 算不出 |
| 为什么 SHA256(Body) 不是 Body 原文 | 大 Body 性能，哈希后定长 |
| 为什么 QueryString 排序 | 客户端/服务端参数顺序可能不同 |
| 为什么不签全部 Header | 代理/中间件会改通用 Header |
| 为什么 SignedHeaders | 向后兼容，未来加 header |
| 为什么先 SHA256 再 HMAC | 压缩长串为定长 → HMAC 处理固定长度 |
| SK 放在哪里 | HMAC 密钥位置，不进字符串 |
| AK 可以明文吗 | 可以，AK 只是身份标识 |
| Nonce 为什么本地缓存 | 百万 QPS 下 Redis 调用太重 |

---

*基于 `SignUtil.java` + `NonceCache.java` + `Constants.java` 实际代码分析*
