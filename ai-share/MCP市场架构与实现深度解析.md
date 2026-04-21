# 从零到一：打造企业级 MCP 市场——DIP 开放平台 MCP 市场架构与实现深度解析

> 本文基于真实项目 dip-open-platform，深度拆解一个生产可用的 MCP 市场从设计到落地的完整方案，涵盖协议实现、工具注册、订阅鉴权、API 代理调用等核心环节。

---

## 一、为什么需要 MCP 市场？

随着 AI 大模型的快速普及，越来越多的企业开始思考：**如何让 AI 安全、可控地调用企业内部 API？**

MCP（Model Context Protocol，模型上下文协议）是 Anthropic 推出的开放协议，解决了 AI 与工具之间标准化互联的问题。但光有协议不够，企业还需要：

- **统一的服务发现**：AI 客户端怎么知道有哪些工具可以用？
- **细粒度的权限管控**：谁能调用哪些工具？
- **无缝的 API 复用**：已有的 REST API 怎么变成 MCP Tool？

**MCP 市场**正是解决这些问题的答案。它是一个 API 能力与 AI 工具之间的"中间层市场"，让开发者把 API 发布成 MCP Server，让 AI 应用按需订阅和调用。

---

## 二、整体架构

系统由三层构成：

```
┌──────────────────────────────────────────────────────────────┐
│                       前端控制台                              │
│   McpPublishApp  |  McpSubscribeApp  |  McpToolManagement     │
│          React 18 + Ant Design + TypeScript                   │
└──────────────────────┬───────────────────────────────────────┘
                       │ HTTP
┌──────────────────────▼───────────────────────────────────────┐
│                   后端控制台 (console-web)                    │
│   controller ─▶ service ─▶ dao  (Spring Boot + MyBatis-Plus) │
│         console-service / console-dao / console-common        │
└──────────────────────┬───────────────────────────────────────┘
                       │ Redis (工具缓存同步)
┌──────────────────────▼───────────────────────────────────────┐
│                  MCP 网关 (console-mcp)                       │
│     POST /{serverCode}/mcp  ←──── AI 客户端 (Claude/Cursor)  │
│     JSON-RPC 2.0 协议  |  Session 管理  |  Feign API 代理    │
└──────────────────────────────────────────────────────────────┘
```

三个模块各司其职：
- **前端控制台**：可视化管理 MCP Server 和订阅关系
- **后端控制台**：业务逻辑层，处理发布/订阅/工具管理
- **MCP 网关**：实现 MCP 协议，是 AI 客户端的直接入口

---

## 三、核心概念与数据模型

### 3.1 应用类型设计

整个平台复用了一套应用（`open_platform_app`）表，通过 `type` + `subType` 区分角色：

| type | subType | 含义 |
|------|---------|------|
| 2（PUBLISH） | 3（MCP） | MCP Server（工具发布方） |
| 1（SUBSCRIBE） | 3（MCP） | MCP 订阅方（AI 应用） |
| 2（PUBLISH） | 1（API） | 普通 API 发布应用 |

```java
// open_platform_app 关键字段
private String mappingPath;    // 路由标识，即 serverCode，如 "data-svc"
private String serviceDetail;  // MCP Server 服务说明（Markdown）
private Integer type;          // 1=订阅, 2=发布
private Integer subType;       // 1=API, 2=消息, 3=MCP
```

### 3.2 工具关联表

```sql
-- open_platform_mcp_server_tool
-- MCP Server 应用 与 OpenAPI 的多对多关联
CREATE TABLE open_platform_mcp_server_tool (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  mcp_app_id    BIGINT NOT NULL,  -- MCP Server 应用 ID
  api_id        BIGINT NOT NULL,  -- 关联的 API ID
  is_delete     INT DEFAULT 0,
  create_username VARCHAR(64),
  create_time   DATETIME,
  update_time   DATETIME
);
```

一个 MCP Server 可以关联多个 API，每个 API 对应一个 MCP Tool。

### 3.3 订阅关系表

```sql
-- open_platform_assets_auth（复用资产授权表）
-- 订阅方 app ←→ 发布方 app 的授权关系
-- assets_type = 3 (MCP), auth_status: 1=已授权, 2=审批中, 3=拒绝, 4=已撤销
```

---

## 四、核心业务流程

### 4.1 发布流程：API → MCP Tool

```
开发者
  │
  ├─ 1. 创建 MCP Server 应用（type=2, subType=MCP）
  │         ↓
  ├─ 2. 从已有 API 应用中选择已发布的 API 导入
  │         ↓
  └─ 3. 系统自动将 API 元信息转换为 JSON Schema Tool
           ↓
       刷新 Redis 工具缓存
```

**API → Tool 转换**是整个链路的精华。`ApiToMcpToolConverter` 负责将 REST API 的参数定义转为 MCP 标准的 `inputSchema`（JSON Schema 格式）：

```java
// 工具名称生成规则：{serverCode}_{apiPath}_{httpMethod}
// 例: mappingPath="data-svc", apiPath="/api/v1/users/{id}", method="GET"
// → "data_svc_api_v1_users_get"
private String buildToolName(String mappingPath, String apiPath, String httpMethod) {
    String normalized = (mappingPath + "/" + apiPath)
            .replaceAll("\\{[^}]+\\}", "")    // 去掉路径参数 {xxx}
            .replaceAll("[/\\-]", "_")
            .replaceAll("_+", "_")
            .toLowerCase(Locale.ROOT);
    return normalized + "_" + httpMethod.toLowerCase();
}
```

生成的 Tool 描述格式符合 MCP 规范：

```json
{
  "name": "data_svc_api_v1_users_get",
  "description": "查询用户信息",
  "inputSchema": {
    "type": "object",
    "properties": {
      "userId": { "type": "integer", "description": "用户ID" },
      "name":   { "type": "string",  "description": "用户名" }
    },
    "required": ["userId"]
  }
}
```

**只有满足条件的 API 才能导入**：`status=3`（已发布）且 `isOnline=1`（已上线），保证工具质量。

---

### 4.2 订阅流程

订阅方（AI 应用）申请订阅某个 MCP Server 时，系统写入 `open_platform_assets_auth` 记录：

```
订阅方应用                    发布方 MCP Server
     │                              │
     ├── applySubscription ─────────►
     │   (subscribeAppId,           │
     │    publishMappingPath)       │
     │                              │
     │   写入 assets_auth 记录      │
     │   auth_status = 1 (直接授权) │
     │◄──────────── authId ─────────┤
```

目前是 **P0 直接授权**（生产环境可切换为审批流），后续支持发布方主动回收（`revokeSubscription`）。

---

### 4.3 调用流程：AI → MCP Gateway → 企业 API

这是整个系统最核心的运行时链路：

```
AI 客户端 (Claude/Cursor)
    │
    │  POST /data-svc/mcp
    │  Headers: X-DIP-TOKEN: {accessToken}
    │  Body: { "jsonrpc": "2.0", "method": "initialize", "id": 1 }
    │
    ▼
McpEndpointController
    │
    ├─ initialize ──► InitializeHandler
    │                    ├─ 验证 AccessToken（查 open_platform_app_key）
    │                    ├─ 验证订阅关系（查 open_platform_assets_auth）
    │                    └─ 创建 Session，返回 Mcp-Session-Id
    │
    ├─ tools/list ──► ToolsListHandler
    │                    └─ 从 Redis 读取该 serverCode 的工具列表
    │
    └─ tools/call ──► ToolsCallHandler
                         ├─ 按 serverCode + toolName 从 Redis 查找工具
                         ├─ 调用 McpOpenApiCallService
                         │    ├─ 解析参数定义（query/body/path/header）
                         │    ├─ 构建 AK/SK 签名请求
                         │    └─ Feign 动态调用下游 API 网关
                         └─ 返回 MCP content 格式结果
```

---

## 五、MCP 协议实现细节

### 5.1 JSON-RPC 2.0 协议处理

MCP 协议基于 JSON-RPC 2.0，系统区分了三类请求：

```java
// 判断是否为 notification（无响应体的通知消息）
boolean isNotification = request.getId() == null
        || (request.getMethod() != null
            && request.getMethod().startsWith("notifications/"));

// notification → HTTP 202，无 body
// 普通请求    → HTTP 200 + JSON-RPC response
```

`McpMessageDispatcher` 按 method 路由到对应 Handler：

| method | Handler | 说明 |
|--------|---------|------|
| `initialize` | InitializeHandler | 鉴权 + 建立 Session |
| `tools/list` | ToolsListHandler | 返回工具列表 |
| `tools/call` | ToolsCallHandler | 执行工具调用 |
| `ping` | 内置 | 心跳检测 |

### 5.2 Session 管理

Session 绑定了 `serverCode` 和 `subscriberAppId`，避免在每次请求都重新鉴权：

- `initialize` 时创建 Session，在响应头 `Mcp-Session-Id` 返回给客户端
- 后续请求携带 `Mcp-Session-Id` Header，服务端从缓存恢复 Session
- `DELETE /{serverCode}/mcp` 显式关闭 Session

### 5.3 工具注册表：DB → Redis 分布式缓存

多实例部署下，工具数据用 Redis 统一缓存，避免每次请求查数据库：

```java
// 定时全量同步（间隔由 Apollo 配置，默认 5 分钟）
@Scheduled(fixedDelayString =
    "#{${mcp.gateway.tool-refresh-interval-minutes:5} * 60000}")
public void scheduledRefresh() {
    refreshAllTools();
}
```

核心 SQL 是一个 **4 表 JOIN**，关联 MCP Server 应用、工具关联表、API 表、API 所属应用表：

```sql
SELECT app.mapping_path AS server_code, a.id AS api_id,
       a.name, a.api_path, a.http_method, a.description,
       a.request_body, a.request_columns,
       p.mapping_path AS api_app_mapping_path
FROM open_platform_app app
INNER JOIN open_platform_mcp_server_tool t ON app.id = t.mcp_app_id
INNER JOIN open_platform_api a ON t.api_id = a.id
INNER JOIN open_platform_app p ON a.app_id = p.id
WHERE app.type = 2 AND app.sub_type = 3  -- MCP Server
  AND a.status = 3 AND a.is_online = 1    -- 已发布已上线
```

**增量刷新**：工具变更时只刷新对应 MCP Server 的缓存，避免全量刷新的开销。

### 5.4 动态 API 代理调用

`McpOpenApiCallService` 负责将 MCP `tools/call` 的参数路由到下游 REST API：

```java
// 参数按位置路由
for (ApiParamTreeDTO paramDef : paramDefs) {
    switch (paramDef.getPosition()) {
        case QUERY:  signatureRequest.queryParams.put(name, value); break;
        case BODY:   bodyParams.put(name, value);                   break;
        case PATH:   uri = uri.replace("{" + name + "}", value);    break;
        case HEADER: signatureRequest.headers.put(name, value);     break;
    }
}
```

同时携带**订阅者的 AccessToken**（`X-DIP-TOKEN`）和**服务签名**（AK/SK），让下游 API 网关能识别调用来源，实现精细鉴权。

Feign 客户端按 gatewayUrl 缓存实例（`ConcurrentHashMap`），避免重复创建连接：

```java
private final Map<String, McpOpenApiFeignClient> feignClientCache
    = new ConcurrentHashMap<>();
```

---

## 六、前端设计

前端三个核心页面：

| 页面 | 路径 | 功能 |
|------|------|------|
| `McpPublishApp` | MCP Server 管理 | 创建/管理 MCP Server，查看订阅统计 |
| `McpSubscribeApp` | MCP 订阅管理 | 创建订阅应用，申请订阅 MCP Server |
| `McpToolManagement` | 工具管理 | 从 API 导入工具，查看/删除工具 |
| `McpPublishAppDetail` | Server 详情 | 工具列表、服务说明（Markdown 渲染） |
| `McpSubscribeAppDetail` | 订阅详情 | 查看已订阅的 Server，获取 MCP Endpoint 和 Token |

订阅方在"订阅详情"页能看到：
- **MCP Endpoint**：`https://dip-open-platform-mcp.example.com/{serverCode}/mcp`
- **AccessToken**：用于 `X-DIP-TOKEN` Header 鉴权
- **工具数量**：该 Server 当前可用的 Tool 数量

这两个信息直接复制到 Claude Desktop、Cursor 等 AI 客户端配置即可完成接入。

---

## 七、运维接口

```http
# 手动触发全量工具刷新
POST /mcp/admin/refresh-tools

# 按应用刷新（工具变更后调用）
POST /mcp/admin/refresh-tools/{mcpAppId}
```

---

## 八、设计亮点总结

| 设计点 | 方案 |
|--------|------|
| **协议标准化** | 完整实现 JSON-RPC 2.0 + MCP 协议规范，支持 initialize/tools/list/tools/call/ping |
| **零侵入接入** | 已有 REST API 无需改造，一键导入为 MCP Tool，JSON Schema 自动生成 |
| **多实例一致性** | Redis 工具缓存 + 定时全量同步，所有实例工具数据一致 |
| **细粒度鉴权** | Session 级鉴权 + 订阅关系校验，订阅方 Token 透传到下游 API |
| **弹性刷新** | 全量刷新（定时）+ 增量刷新（工具变更触发）两种策略 |
| **配置化** | 刷新间隔、网关地址、AK/SK 全走 Apollo，支持热更新，不硬编码 |

---

## 九、后续规划

- **SSE 长连接**：当前网关返回 405 让客户端降级为纯 POST，后续可接入 SSE 实现流式推送
- **订阅审批流**：当前直接授权，后续接入工单系统实现审批
- **工具调用监控**：接入调用链追踪，统计每个 Tool 的调用量和延迟
- **多版本 Tool**：支持 Tool 按 API 版本管理，避免大模型提示词失效

---

## 总结

这套 MCP 市场的核心价值在于：**把 API 资产的发布-订阅能力，平滑延伸到了 AI 工具调用领域**。发布方把已有 API 包装成 MCP Server，订阅方一次配置即可让 AI 客户端调用企业内部能力，整个过程安全、可控、可审计。

从架构上看，它并没有引入全新的复杂组件，而是在现有开放平台的基础上增加了一层 MCP 协议适配层，充分复用了应用管理、API 管理、权限管控等基础设施，这也是企业级系统演进的正确姿势——**渐进式扩展，而非推倒重来**。
