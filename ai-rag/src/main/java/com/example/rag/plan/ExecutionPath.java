package com.example.rag.plan;

/**
 * 子问题的执行路径类型。
 *
 * <p>不同路径走不同的执行链，Z 路线有本质区别。</p>
 */
public enum ExecutionPath {

    /**
     * 查询路径：需要查数据。
     * 流程：检索指标定义 → 获取表Schema → LLM生成SQL → 执行SQL → 返回结果
     */
    QUERY,

    /**
     * 计算路径：只做计算/推理，不查数据库。
     * 流程：等待依赖结果 → LLM 基于结果计算/比较 → 返回结果
     */
    COMPUTE
}
