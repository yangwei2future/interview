package com.example.rag.plan;

import com.example.rag.decompose.SubProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点执行器：根据 isQuery 走不同的执行链。
 *
 * <pre>
 * QUERY 路径:
 *   search_indicator → get_table_schema → LLM生成SQL → 执行SQL → 结果
 *
 * COMPUTE 路径:
 *   等待依赖结果 → LLM 基于结果计算/比较 → 结果
 * </pre>
 */
@Service
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    /** 已完成的节点结果缓存，供 COMPUTE 节点读取依赖 */
    private final Map<String, Object> results = new ConcurrentHashMap<>();

    /**
     * 执行单个节点，根据类型分发。
     *
     * @param node 子问题
     * @return 执行结果（查询结果 JSON / 计算结果字符串）
     */
    public Object execute(SubProblem node) {
        if (node.isQuery()) {
            return executeQuery(node);
        } else {
            return executeCompute(node);
        }
    }

    /**
     * QUERY 路径：检索 → Schema → 生成SQL → 执行SQL。
     *
     * <p>当前占位实现，下一模块（双层检索 + SQL 生成）完成后串联。</p>
     */
    private Object executeQuery(SubProblem node) {
        log.info("[QUERY] {}: {}", node.getId(), node.getDescription());

        // TODO: 串联双层检索 + SQL 生成 + 执行
        // 1. search_indicator(node.description)  → 指标定义
        // 2. get_table_schema(table)             → 表结构
        // 3. generate_sql(指标定义 + Schema + 问题) → SQL
        // 4. execute_sql(sql)                    → 结果

        Object result = "[QUERY-STUB] " + node.getId() + ": " + node.getDescription();
        results.put(node.getId(), result);
        return result;
    }

    /**
     * COMPUTE 路径：基于依赖结果做计算/推理。
     *
     * <p>当前占位实现，后续接入 LLM 做计算推理。</p>
     */
    private Object executeCompute(SubProblem node) {
        log.info("[COMPUTE] {}: {}, dependsOn={}", node.getId(),
                node.getDescription(), node.getDependsOn());

        // 收集依赖结果
        StringBuilder context = new StringBuilder();
        for (String depId : node.getDependsOn()) {
            Object depResult = results.get(depId);
            context.append(depId).append("=").append(depResult).append("; ");
        }

        // TODO: LLM 基于 context + description 做计算
        // String result = deepSeekClient.chat(computePrompt, context + "\n" + node.description);

        Object result = "[COMPUTE-STUB] " + node.getId() + ": " + node.getDescription()
                + " (inputs: " + context + ")";
        results.put(node.getId(), result);
        return result;
    }

    /** 清空结果缓存 */
    public void clear() { results.clear(); }
}
