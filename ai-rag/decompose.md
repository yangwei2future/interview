# 子问题拆解模块（Decompose）

## 职责

复杂问题 → 多个"简单问题" + 依赖关系 → 子问题 DAG。

不查数据，不生成 SQL，只做规划。

## 输入

```java
String userQuery,          // "2026年L6和L7哪个增长更快？"
IntentResult intentResult  // planType = MULTI_TURN
```

## 输出

```java
DecomposeResult {
    List<SubProblem> subProblems;
}

SubProblem {
    String id;              // "step-1"
    String description;     // 自然语言描述，下游可直接作为子查询输入
    List<String> dependsOn; // 依赖的步骤 ID 列表
    boolean isQuery;        // true = 查数据库, false = 纯计算/推理
}
```

## 输入 → 输出示例

```
输入："2026年L6和L7哪个增长更快？"

输出：
  step-1: "查询理想L6 2026年销量"  dependsOn=[]      isQuery=true
  step-2: "查询理想L7 2026年销量"  dependsOn=[]      isQuery=true
  step-3: "查询理想L6 2025年销量"  dependsOn=[]      isQuery=true
  step-4: "查询理想L7 2025年销量"  dependsOn=[]      isQuery=true
  step-5: "计算L6同比增长率"       dependsOn=[1,3]   isQuery=false
  step-6: "计算L7同比增长率"       dependsOn=[2,4]   isQuery=false
  step-7: "比较L6和L7增长率排名"   dependsOn=[5,6]   isQuery=false
```

## 执行调度（调用方逻辑）

```
1. 找出 dependsOn=[] 的节点 → 并行执行（step 1-4）
2. 检查依赖是否全部完成 → 执行 step-5, step-6（可并行）
3. 检查依赖 → 执行 step-7
```

## 在整体链路中的位置

```
意图识别（IntentRecognition）
    │
    ├─ SINGLE_TURN → 跳过拆解，直接走检索链
    │
    └─ MULTI_TURN  → 子问题拆解（当前模块）
                        │
                        ▼
                   每个子问题独立走：
                   search_indicator → get_table_schema → execute_sql
                        │
                        ▼
                   合并结果 → LLM 生成最终回答
```
