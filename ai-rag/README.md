# AI-RAG 模块

> Agentic RAG + NL2SQL 技术体系，拆解为独立子模块，逐个实现。

## 模块总览

```
用户自然语言
    │
    ▼
┌──────────────┐
│ 1. 意图识别   │ ← 当前模块
│ intent-      │   判断问题是一次性规划还是动态规划
│ recognition  │
└──────┬───────┘
       │ 输出 plan_type
       ▼
┌──────────────┐
│ 2. 子问题拆解 │ ← 待实现
│ decompose    │   复杂问题拆成子查询 DAG
└──────┬───────┘
       │ 输出子问题列表
       ▼
┌──────────────┐
│ 3. 双层检索   │ ← 待实现
│ retrieval    │   指标知识库 RAG + MCP Schema 检索
└──────┬───────┘
       │ 上下文组装
       ▼
┌──────────────┐
│ 4. SQL 生成   │ ← 待实现
│ sql-gen      │   Prompt 组装 + LLM 调用
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ 5. 安全校验   │ ← 待实现
│ sql-guard    │   语法校验 + 只读账号 + 行数限制
└──────────────┘
```

## 子模块清单

| 序号 | 模块 | 文件 | 状态 |
|------|------|------|------|
| 1 | 意图识别 | [intent-recognition.md](intent-recognition.md) | ✅ 已完成 |
| 2 | 子问题拆解 | — | 待实现 |
| 3 | 双层检索 | — | 待实现 |
| 4 | SQL 生成 | — | 待实现 |
| 5 | 安全校验 | — | 待实现 |

## 参考资料

- 面试准备：`src/interview-records/2026-05-25-lixiang-round1-prep.md` Q8-Q13
- 网关项目：`/Users/yangwei6/dip-open-platform-fusion/dip-open-platform-api-gateway`
