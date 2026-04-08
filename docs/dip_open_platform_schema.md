# dip_open_platform 数据库表结构

> 数据库：dip_open_platform
> 导出时间：2026-04-07
> 共 24 张表

---

## 目录

| 表名 | 说明 |
|------|------|
| [alert_aggregations](#alert_aggregations) | 聚合告警表 |
| [alert_config](#alert_config) | 告警配置表 |
| [alert_raw_records](#alert_raw_records) | 原始告警记录表 |
| [alert_sent_history](#alert_sent_history) | 告警发送记录表 |
| [open_platform_api](#open_platform_api) | API信息 |
| [open_platform_api_plugin](#open_platform_api_plugin) | API插件 |
| [open_platform_app](#open_platform_app) | 应用 |
| [open_platform_app_key](#open_platform_app_key) | 密钥 |
| [open_platform_app_service](#open_platform_app_service) | 服务来源 |
| [open_platform_app_user_relation](#open_platform_app_user_relation) | 应用成员关系表 |
| [open_platform_assets_auth](#open_platform_assets_auth) | 应用拥有的资产权限 |
| [open_platform_assets_auth_log](#open_platform_assets_auth_log) | 资产权限申请记录 |
| [open_platform_mcp_server_tool](#open_platform_mcp_server_tool) | MCP Server 工具关联表 |
| [open_platform_mcp_session](#open_platform_mcp_session) | MCP Session 表 |
| [open_platform_message](#open_platform_message) | 消息信息 |
| [open_platform_theme](#open_platform_theme) | 主题表 |
| [open_platform_ypf_skill](#open_platform_ypf_skill) | Skill技能表 |
| [open_platform_ypf_user_preference](#open_platform_ypf_user_preference) | 用户偏好设置表 |
| [skill](#skill) | Skill主表 |
| [skill_download_log](#skill_download_log) | Skill下载记录表 |
| [skill_market](#skill_market) | Skill市场资产表 |
| [skill_version](#skill_version) | Skill版本表 |

---

## alert_aggregations
> 聚合告警表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键ID，自增 |
| api | tinyint(1) | NO | 0 | 聚合维度: 是否按接口 0-否 1-是 |
| app | tinyint(1) | NO | 0 | 聚合维度: 是否按应用 0-否 1-是 |
| error_code | tinyint(1) | NO | 0 | 聚合维度: 是否按错误码 0-否 1-是 |
| space | tinyint(1) | NO | 0 | 聚合维度: 是否按空间 0-否 1-是 |
| aggregation_key | varchar(255) | NO | - | 聚合键（维度拼接） |
| time_window_start | datetime | NO | - | 时间窗口开始 |
| time_window_end | datetime | NO | - | 时间窗口结束 |
| error_count | int(11) | NO | - | 错误总数 |
| affected_items | text | YES | - | 影响的项目 JSON |
| is_alerted | tinyint(1) | NO | 0 | 是否已发送告警 0-否 1-是 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| is_del | tinyint(1) | NO | 0 | 逻辑删除 0-正常 1-删除 |
| receivers | varchar(500) | YES | - | 通知接收人，逗号分割 |
| feishu | tinyint(1) | YES | 0 | 飞书告警 0-未开启 1-开启 |
| email | tinyint(1) | YES | 0 | 邮件告警 0-未开启 1-开启 |
| phone | tinyint(1) | YES | 0 | 电话告警 0-未开启 1-开启 |
| webhook | tinyint(1) | YES | 0 | 飞书机器人告警 0-未开启 1-开启 |
| webhook_url | varchar(500) | YES | - | 飞书机器人URL，逗号分割 |

**索引：** PRIMARY(id)、idx_time_window(time_window_start, time_window_end)、idx_dimension(api, app, error_code, space)

---

## alert_config
> 告警配置表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键ID，自增 |
| config_type | int(4) | NO | - | 配置类型: 0-接口 1-应用 |
| alert_key | int(8) | NO | - | 告警key，对应主键 |
| enabled | tinyint(1) | YES | 0 | 是否启用 0-否 1-是 |
| alert_rule | varchar(200) | YES | - | 告警规则，存储HTTP状态码，如: 500,401,403 |
| feishu | tinyint(1) | YES | 0 | 飞书告警 |
| email | tinyint(1) | YES | 0 | 邮件告警 |
| phone | tinyint(1) | YES | 0 | 电话告警 |
| webhook | tinyint(1) | YES | 0 | 飞书机器人告警 |
| webhook_url | varchar(500) | YES | - | 飞书机器人URL |
| notify_receivers | varchar(500) | YES | - | 通知接收人 |
| repeat_alert | tinyint(1) | YES | 0 | 是否开启重复告警 |
| min_interval_minutes | int(11) | YES | 5 | 最小告警间隔(分钟) |
| max_alerts_per_hour | int(11) | YES | 10 | 每小时最大告警数 |
| create_time | datetime | YES | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | YES | CURRENT_TIMESTAMP | 更新时间 |
| is_del | int(4) | YES | 0 | 是否删除 0-未删除 1-删除 |

**索引：** PRIMARY(id)、UNIQUE uniq_type_key(config_type, alert_key)

---

## alert_raw_records
> 原始告警记录表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) | NO | - | 主键ID，自增 |
| app_id | bigint(20) | NO | - | 应用ID |
| request_app_id | bigint(20) | YES | - | 请求应用ID |
| space_code | varchar(256) | YES | - | 空间编码 |
| api_id | bigint(20) | NO | - | 接口ID |
| start_time | datetime | NO | - | 开始时间 |
| end_time | datetime | NO | - | 结束时间 |
| response_code | int(11) | NO | - | 响应状态码 |
| error_count | int(11) | NO | - | 错误总数 |
| extracted_at | datetime | YES | CURRENT_TIMESTAMP | 提取时间 |
| is_alerted | tinyint(4) | YES | 0 | 是否已发送原始告警 0-否 1-是 |
| is_del | int(4) | YES | 0 | 是否删除 |
| receivers | varchar(500) | YES | - | 通知接收人 |
| feishu | tinyint(1) | YES | 0 | 飞书告警 |
| email | tinyint(1) | YES | 0 | 邮件告警 |
| phone | tinyint(1) | YES | 0 | 电话告警 |
| webhook | tinyint(1) | YES | 0 | 飞书机器人告警 |
| webhook_url | varchar(500) | YES | - | 飞书机器人URL |

**索引：** PRIMARY(id)、idx_app_api(app_id, api_id)

---

## alert_sent_history
> 告警发送记录表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) | NO | - | 主键ID，自增 |
| alert_type | varchar(50) | NO | - | 告警类型: RAW, AGGREGATION |
| alert_item_type | int(4) | YES | - | 告警类型枚举值 0-接口 1-应用 |
| alert_key | int(8) | YES | - | 告警key |
| alert_id | bigint(20) | YES | - | 告警记录ID |
| alert_message | text | NO | - | 告警内容 |
| notify_channels | json | YES | - | 通知渠道 |
| receivers | json | YES | - | 接收人 |
| sent_time | datetime | YES | CURRENT_TIMESTAMP | 发送时间 |

**索引：** PRIMARY(id)、idx_alert_key(alert_key)、idx_sent_time(sent_time)、idx_type_time(alert_type, sent_time)

---

## open_platform_api
> API信息

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| code | int(11) | NO | - | apiCode |
| app_id | bigint(20) | NO | - | 归属应用id |
| name | varchar(64) | NO | - | 名称 |
| description | varchar(255) | YES | - | 描述 |
| service_id | bigint(20) | YES | - | 服务来源id |
| theme_id | varchar(128) | YES | - | 主题id |
| security_level | int(11) | YES | 1 | 安全等级：1-免审 2-需要审批 |
| version | int(11) | YES | - | 当前版本 |
| status | int(11) | YES | 1 | 状态：1-开发中 2-发布中 3-已发布 4-已下线 |
| is_online | int(11) | YES | 0 | 正在对外服务：0-未提供 1-提供 |
| plugin_name | varchar(64) | YES | - | 插件名称 |
| qps_quota | int(11) | YES | - | QPS总配额 |
| api_timeout | int(11) | YES | - | API超时(毫秒) |
| api_path | varchar(255) | YES | - | API路径 |
| upstream_api_path | varchar(255) | YES | - | 上游API路径 |
| upstream_api_host | varchar(1024) | YES | - | 上游API Host或集群Id |
| http_method | varchar(64) | YES | - | HTTP Method，如POST/GET |
| content_type | varchar(64) | YES | - | ContentType |
| request_columns | mediumtext | YES | - | 请求参数 |
| request_body | mediumtext | YES | - | 请求body |
| response_columns | mediumtext | YES | - | 返回参数 |
| response_sample | mediumtext | YES | - | 返回示例 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| owner | varchar(64) | YES | - | 负责人 |
| white_list | varchar(2048) | YES | - | API白名单 |
| cache_enabled | int(11) | YES | - | 是否启用缓存 1-启用 0-不启用 |

**索引：** PRIMARY(id)

---

## open_platform_api_plugin
> API插件

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| plugin_name | varchar(64) | NO | - | 英文名称 |
| plugin_name_cn | varchar(64) | NO | - | 中文名称 |
| plugin_content | mediumtext | NO | - | 映射路径 |
| plugin_type | int(11) | YES | 2 | 类型：1-predicate 2-filter |
| api_id | bigint(20) | YES | - | 对应的api id |
| execute_order | int(11) | YES | - | 执行顺序 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |

**索引：** PRIMARY(id)

---

## open_platform_app
> 应用

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| name | varchar(64) | NO | - | 名称 |
| mapping_path | varchar(64) | YES | - | 映射路径 |
| description | varchar(255) | YES | - | 描述 |
| type | int(11) | YES | 1 | 类型：1-订阅 2-发布 3-订阅&发布 |
| sub_type | int(11) | YES | - | 子类型 1-API 2-消息 |
| status | int(11) | YES | 1 | 状态：1-启用 2-禁用 |
| space_code | varchar(64) | YES | - | 空间code |
| tenant_id | varchar(64) | YES | - | 租户code |
| owner | varchar(64) | YES | - | 应用负责人 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| auth_scope | tinyint(4) | NO | 0 | 授权范围：0-指定角色 1-全部角色 |
| space_role | varchar(2000) | NO | '' | 空间角色 |
| biz_owner | varchar(2000) | NO | '' | 业务负责人 |
| product_owner | varchar(2000) | NO | '' | 产品负责人 |
| source | int(11) | YES | 1 | 应用来源：1-开放平台 2-共享平台 |
| service_detail | text | YES | - | MCP Server服务详情（Markdown） |

**索引：** PRIMARY(id)

---

## open_platform_app_key
> 密钥

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| app_id | bigint(20) | NO | - | 归属应用id |
| key_type | int(11) | YES | 1 | 类型：1-appcode 2-appkey/appSecret |
| access_token | varchar(256) | YES | - | API token加密值 |
| refer | varchar(256) | YES | - | API key限定来源 |
| ak | varchar(256) | YES | - | accessKey值 |
| sk | varchar(267) | YES | - | accessSecret加密值 |
| expired_time | datetime | YES | - | 过期时间 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| name | varchar(64) | YES | - | key名称 |

**索引：** PRIMARY(id)

---

## open_platform_app_service
> 服务来源

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| app_id | bigint(20) | NO | - | 归属应用id |
| name | varchar(64) | NO | - | 名称 |
| service_type | int(11) | YES | 1 | 服务类型：1-路径 2-消息 |
| message_type | int(11) | YES | - | 消息类型：1-kafka 2-rocketmq |
| message_action | int(11) | YES | - | 消息动作：1-接收消息 2-提供消息 |
| env | varchar(64) | YES | - | 环境 |
| addr | varchar(512) | YES | - | 服务域名地址或消息地址 |
| extra_info | mediumtext | YES | - | 消息额外属性 |
| tenant_id | varchar(64) | YES | - | 创建者ID |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| topic | varchar(256) | YES | - | 消息消费的topic |

**索引：** PRIMARY(id)

---

## open_platform_app_user_relation
> 应用成员关系表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| app_id | bigint(20) | NO | - | 归属应用id |
| user_name | varchar(64) | NO | - | 用户域账号 |
| user_type | int(11) | YES | 1 | 类型：1-产品负责人 2-业务负责人 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |

**索引：** PRIMARY(id)

---

## open_platform_assets_auth
> 应用拥有的资产权限

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| app_id | bigint(20) | NO | - | 归属应用id |
| assets_type | int(11) | YES | 1 | 资产类型：1-api 2-消息 |
| assets_code | int(11) | YES | - | 资产code |
| assets_app_id | bigint(20) | YES | - | 资产所属的appid |
| auth_status | int(11) | YES | 1 | 权限状态：1-已授权 2-审批中 3-拒绝 4-撤销 |
| auth_time | datetime | YES | - | 授权时间 |
| auth_process_id | varchar(64) | YES | - | 申请流程id |
| purpose | varchar(256) | YES | - | MCP订阅申请用途 |
| auth_info_qps | int(11) | YES | - | QPS |
| auth_info_cqs | int(11) | YES | - | 并发数 |
| auth_info_dac | int(11) | YES | - | 日均调用量 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| service_id | bigint(20) | YES | - | 资产关联的服务 |
| consume_status | int(11) | YES | - | 消费状态：1-启用 0-禁用 |
| consume_type | int(11) | YES | - | 消费类型：0-从头开始 1-最新点位 |
| consume_offset_tag | bigint(20) | YES | - | 消费标记位 |

**索引：** PRIMARY(id)

---

## open_platform_assets_auth_log
> 资产权限申请记录

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| app_id | bigint(20) | NO | - | 归属应用id |
| assets_type | int(11) | YES | 1 | 资产类型：1-api 2-消息 |
| assets_code | int(11) | YES | - | 资产code |
| assets_app_id | int(11) | YES | - | 资产所属的appid |
| auth_status | int(11) | YES | - | 权限状态：1-已授权 2-审批中 3-拒绝 4-撤销 |
| auth_time | datetime | YES | - | 授权时间 |
| auth_process_id | varchar(64) | YES | - | 申请流程id |
| auth_info_qps | int(11) | YES | - | QPS |
| auth_info_cqs | int(11) | YES | - | 并发数 |
| auth_info_dac | int(11) | YES | - | 日均调用量 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |

**索引：** PRIMARY(id)

---

## open_platform_mcp_server_tool
> MCP Server 工具关联表（MCP Server 应用与接口的关联关系）

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| mcp_app_id | bigint(20) | NO | - | MCP Server 应用ID |
| api_id | bigint(20) | NO | - | 关联接口ID |
| is_delete | tinyint(4) | NO | 0 | 逻辑删除：0-有效 1-已删除 |
| create_username | varchar(64) | YES | - | 创建人 |
| update_username | varchar(64) | YES | - | 更新人 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 更新时间 |

**索引：** PRIMARY(id)、idx_mcp_app_id(mcp_app_id)、idx_api_id(api_id)

---

## open_platform_mcp_session
> MCP Session 表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| session_id | varchar(64) | NO | - | Session ID（UUID） |
| server_code | varchar(64) | NO | - | 关联的 MCP Server code |
| subscriber_app_id | bigint(20) | NO | 0 | 订阅者应用ID |
| client_info | varchar(500) | YES | - | 客户端信息 |
| last_access_time | datetime | NO | - | 最后活跃时间，用于超时判断 |
| is_deleted | tinyint(4) | NO | 0 | 逻辑删除：0-有效 1-已删除 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |

**索引：** PRIMARY(id)、idx_server_code(server_code)、idx_subscriber_app_id(subscriber_app_id)、idx_last_access_time(last_access_time)

---

## open_platform_message
> 消息信息

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| code | int(11) | NO | - | 消息code |
| app_id | bigint(20) | NO | - | 归属应用id |
| name | varchar(64) | NO | - | 名称 |
| description | varchar(255) | YES | - | 描述 |
| service_id | int(11) | YES | - | 服务来源id |
| theme_id | varchar(128) | YES | - | 主题id |
| security_level | int(11) | YES | 1 | 授权方式：1-免审 2-需要审批 |
| version | int(11) | YES | - | 当前版本 |
| status | int(11) | YES | 1 | 状态：1-开发中 2-发布中 3-已发布 4-已下线 |
| is_online | int(11) | YES | 0 | 正在对外服务：0-未提供 1-提供 |
| plugin_name | varchar(64) | YES | - | 插件名称 |
| topic | varchar(64) | YES | - | 消息topic |
| schema | mediumtext | YES | - | 消息结构 |
| sample | mediumtext | YES | - | 消息样例 |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |
| owner | varchar(64) | YES | - | 负责人 |

**索引：** PRIMARY(id)

---

## open_platform_theme
> 主题表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | int(11) unsigned | NO | - | 主键，自增 |
| parent_id | int(11) | YES | - | 父主题id |
| code | varchar(64) | YES | - | 主题编码 |
| name | varchar(64) | YES | - | 主题名称 |
| tenant_id | varchar(64) | YES | - | 创建者ID |
| create_username | varchar(64) | YES | - | 创建者域账号 |
| update_username | varchar(64) | YES | - | 修改者域账号 |
| create_time | datetime | YES | - | 创建时间 |
| update_time | datetime | YES | - | 修改时间 |
| is_delete | int(11) | YES | 0 | 0-未删除 1-已删除 |

**索引：** PRIMARY(id)

---

## open_platform_ypf_skill
> Skill技能表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键ID，自增 |
| name | varchar(50) | NO | - | Skill名称，全局唯一 |
| description | varchar(500) | NO | - | Skill描述 |
| icon | varchar(100) | YES | - | 图标标识 |
| category | int(11) | NO | - | 类别枚举值 |
| tags | varchar(200) | YES | - | 标签列表，逗号分隔 |
| source_type | int(11) | NO | 1 | 来源类型：1-内部开发 2-外部来源 |
| security_certified | tinyint(1) | NO | 0 | 安全认证状态 |
| view_count | bigint(20) | NO | 0 | 浏览数 |
| download_count | bigint(20) | NO | 0 | 下载数 |
| like_count | bigint(20) | NO | 0 | 点赞数 |
| comment_count | bigint(20) | NO | 0 | 评论数 |
| install_command | varchar(200) | NO | - | 安装命令 |
| git_path | varchar(200) | YES | - | Git仓库路径 |
| author_id | varchar(50) | NO | - | 作者域账号 |
| author_name | varchar(50) | NO | - | 作者显示名 |
| create_username | varchar(50) | YES | - | 创建者 |
| update_username | varchar(50) | YES | - | 更新者 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 更新时间 |
| is_delete | int(11) | NO | 0 | 逻辑删除标记 |

**索引：** PRIMARY(id)、UNIQUE uniq_name(name)、idx_category(category)、idx_author_id(author_id)、idx_create_time(create_time)

---

## open_platform_ypf_user_preference
> 用户偏好设置表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键ID，自增 |
| user_id | varchar(50) | NO | - | 用户域账号，唯一 |
| theme | varchar(10) | YES | light | 主题：light/dark |
| view_mode | varchar(10) | YES | grid | 视图模式：grid/list |
| show_guide | tinyint(1) | YES | 1 | 是否显示引导 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 更新时间 |

**索引：** PRIMARY(id)、UNIQUE uniq_user_id(user_id)

---

## skill
> Skill主表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| name | varchar(100) | NO | - | Skill名称，平台唯一 |
| short_description | varchar(300) | NO | - | 简短描述，用于卡片展示 |
| detail_description | text | YES | - | 详细描述（Markdown） |
| category | varchar(50) | NO | - | 分类编码 |
| tags | json | YES | - | 标签列表，如["sql","数据分析"] |
| icon_url | varchar(500) | YES | - | 图标文件URL |
| current_version | varchar(20) | YES | - | 当前最新已发布版本号（冗余） |
| status | tinyint(4) | NO | 0 | 0-DRAFT 1-REVIEWING 2-PUBLISHED 3-REJECTED 4-OFFLINE |
| is_certified | tinyint(1) | NO | 0 | 是否平台认证 |
| certified_at | datetime | YES | - | 平台认证授予时间 |
| creator_id | varchar(50) | NO | - | 创建者用户ID |
| creator_name | varchar(100) | NO | - | 创建者姓名（冗余） |
| download_count | bigint(20) | NO | 0 | 累计下载量 |
| view_count | bigint(20) | NO | 0 | 累计浏览量 |
| reject_reason | text | YES | - | 审核拒绝原因 |
| published_at | datetime | YES | - | 首次发布时间 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 最后更新时间 |
| create_username | varchar(100) | YES | - | 创建人 |
| update_username | varchar(100) | YES | - | 最后更新人 |
| is_delete | tinyint(1) | NO | 0 | 软删除：0-正常 1-已删除 |

**索引：** PRIMARY(id)、UNIQUE uniq_name(name)、idx_creator_id(creator_id)、idx_status(status)、idx_category(category)、idx_create_time(create_time)、idx_name_desc(name, short_description)

---

## skill_download_log
> Skill下载记录表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| skill_id | bigint(20) | NO | - | 关联skill.id |
| version | varchar(20) | NO | - | 下载的版本号 |
| user_id | varchar(50) | NO | - | 下载者用户ID |
| download_type | varchar(20) | NO | DIRECT | 下载方式：DIRECT-直接下载 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 下载时间 |

**索引：** PRIMARY(id)、idx_skill_id(skill_id)、idx_user_id(user_id)、idx_create_time(create_time)

---

## skill_market
> Skill市场资产表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| skill_key | varchar(100) | NO | '' | Skill唯一标识，kebab-case |
| type | tinyint(4) | NO | 1 | 类型：1-Skill 2-Plugin |
| name | varchar(100) | NO | - | Skill名称 |
| description | varchar(500) | YES | - | Skill简介 |
| tags | varchar(500) | YES | - | 标签列表，JSON数组字符串 |
| status | tinyint(4) | NO | 1 | 状态：1-草稿 3-已上架 5-已下架 |
| file_path | varchar(500) | YES | - | 已发布文件BOS Key |
| file_temp_key | varchar(200) | YES | - | 上传暂存BOS Key |
| download_count | int(11) | NO | 0 | 手动下载次数 |
| last_validation_error | varchar(500) | YES | - | 最近一次发布校验失败原因 |
| publish_time | datetime | YES | - | 首次上架时间 |
| ai_eval_status | tinyint(4) | NO | 0 | AI评估状态：0-待评估 1-评估中 2-已完成 3-失败 |
| ai_score | tinyint(4) | YES | - | AI综合质量分（1-10） |
| ai_level | varchar(20) | YES | - | AI质量等级 |
| ai_evaluation | text | YES | - | AI评估结果JSON |
| ai_eval_retry_count | tinyint(4) | NO | 0 | AI评估累计失败次数 |
| gitlab_sync_status | tinyint(4) | NO | 0 | GitLab同步状态：0-待同步 1-同步中 2-已同步 3-失败 |
| gitlab_sync_retry_count | tinyint(4) | NO | 0 | GitLab同步失败次数 |
| is_delete | tinyint(1) | NO | 0 | 逻辑删除：0-正常 1-已删除 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 更新时间 |
| principal | varchar(128) | NO | '' | 负责人 |
| create_username | varchar(128) | NO | '' | 创建人用户名 |
| update_username | varchar(128) | NO | '' | 更新人用户名 |
| ai_eval_error | varchar(500) | YES | - | AI评估失败原因 |
| extra_file_keys | text | YES | - | 文件包辅助文件OIS Key映射（JSON） |

**索引：** PRIMARY(id)

---

## skill_version
> Skill版本表

| 字段 | 类型 | 可空 | 默认值 | 说明 |
|------|------|------|--------|------|
| id | bigint(20) unsigned | NO | - | 主键，自增 |
| skill_id | bigint(20) | NO | - | 关联skill.id |
| version | varchar(20) | NO | - | 语义化版本号，如1.2.0 |
| file_url | varchar(500) | NO | - | ZIP包存储URL |
| file_size | bigint(20) | YES | - | 文件大小（字节） |
| skill_md_content | mediumtext | YES | - | SKILL.md文件原始内容 |
| file_tree | json | YES | - | 文件目录树结构（JSON） |
| status | tinyint(4) | NO | 1 | 1-REVIEWING 2-PUBLISHED 3-REJECTED 4-OFFLINE |
| reviewer_id | varchar(50) | YES | - | 审核人ID |
| reviewed_at | datetime | YES | - | 审核时间 |
| create_time | datetime | NO | CURRENT_TIMESTAMP | 创建时间 |
| update_time | datetime | NO | CURRENT_TIMESTAMP | 最后更新时间 |
| create_username | varchar(100) | YES | - | 创建人 |
| update_username | varchar(100) | YES | - | 最后更新人 |

**索引：** PRIMARY(id)、UNIQUE uniq_skill_version(skill_id, version)、idx_skill_id(skill_id)、idx_status(status)
