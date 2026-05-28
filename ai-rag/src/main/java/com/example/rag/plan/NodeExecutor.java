package com.example.rag.plan;

import com.example.rag.decompose.SubProblem;
import com.interview.deepseek.DeepSeekClient;
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
 *   RAG检索(指标定义) + MCP检索(表Schema) 并行
 *   → LLM生成SQL → 安全校验 → 执行SQL → 结果
 *
 * COMPUTE 路径:
 *   从 results Map 拿依赖结果 → LLM 计算/推理 → 结果
 * </pre>
 */
@Service
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final DeepSeekClient llm;
    private final Map<String, Object> results = new ConcurrentHashMap<>();

    public NodeExecutor(DeepSeekClient llm) {
        this.llm = llm;
    }

    public Object execute(SubProblem node) {
        if (node.isQuery()) {
            return executeQuery(node);
        } else {
            return executeCompute(node);
        }
    }

    // ==================== QUERY 链 ====================

    private Object executeQuery(SubProblem node) {
        log.info("[QUERY] {}: {}", node.getId(), node.getDescription());

        // TODO: 接上双层检索 + SQL 执行后替换此占位
        // 1. RAG: search_indicator(description) → 指标定义
        // 2. MCP: get_table_schema(table)      → 字段列表
        // 3. LLM: generate_sql(指标+Schema+问题) → SQL
        // 4. 校验 + 执行 SQL

        // 当前用模拟数据让 COMPUTE 节点能跑通
        Object result = generateMockQueryResult(node);
        results.put(node.getId(), result);
        return result;
    }

    /** 模拟查询结果，让后续 COMPUTE 节点有数据可算 */
    private Object generateMockQueryResult(SubProblem node) {
        String desc = node.getDescription().toLowerCase();
        // L6 2026 → 120000, L7 2026 → 95000
        if (desc.contains("l6") && desc.contains("2026")) return Map.of("model", "L6", "year", 2026, "sales", 120000);
        if (desc.contains("l7") && desc.contains("2026")) return Map.of("model", "L7", "year", 2026, "sales", 95000);
        if (desc.contains("l6") && desc.contains("2025")) return Map.of("model", "L6", "year", 2025, "sales", 100000);
        if (desc.contains("l7") && desc.contains("2025")) return Map.of("model", "L7", "year", 2025, "sales", 70000);
        return Map.of("sales", 100000);
    }

    // ==================== COMPUTE 链 ====================

    private Object executeCompute(SubProblem node) {
        log.info("[COMPUTE] {}: {}, dependsOn={}",
                node.getId(), node.getDescription(), node.getDependsOn());

        // 从缓存拿依赖节点的结果
        StringBuilder ctx = new StringBuilder();
        for (String depId : node.getDependsOn()) {
            ctx.append(depId).append("=").append(results.get(depId)).append("\n");
        }

        // 让 LLM 基于依赖结果做计算
        String prompt = """
                你是一个数据分析助手。根据已有数据完成计算任务。

                已有数据：
                %s

                任务：%s

                只返回计算结果，不要解释过程。
                """.formatted(ctx, node.getDescription());

        String answer = llm.chat(prompt);
        log.info("[COMPUTE] {} 结果: {}", node.getId(), answer);
        results.put(node.getId(), answer);
        return answer;
    }

    // ==================== 工具方法 ====================

    public Map<String, Object> getAllResults() {
        return Map.copyOf(results);
    }

    public void clear() {
        results.clear();
    }
}
