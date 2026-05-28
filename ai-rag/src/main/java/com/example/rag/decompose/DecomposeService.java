package com.example.rag.decompose;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.deepseek.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子问题拆解服务。
 *
 * <p>输入复杂问题，输出子问题 DAG。
 * 通过 System Prompt 让 LLM 拆解，返回 JSON 格式的子问题列表。</p>
 */
@Service
public class DecomposeService {

    private static final Logger log = LoggerFactory.getLogger(DecomposeService.class);

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public DecomposeService(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 拆解用户问题为子问题 DAG。
     *
     * @param userQuery 用户自然语言问题
     * @return 子问题列表 + 依赖关系
     */
    public DecomposeResult decompose(String userQuery) {
        log.info("子问题拆解开始: query={}", userQuery);

        String llmResponse = deepSeekClient.chat(buildSystemPrompt(), userQuery);
        log.info("LLM 拆解返回: {}", llmResponse);

        return parseResponse(llmResponse);
    }

    private String buildSystemPrompt() {
        return """
                You are a data analysis agent. Your job is to decompose a complex user query into simpler sub-problems.

                ## Rules
                1. Each sub-problem should be answerable with a SINGLE SQL query OR a simple calculation.
                2. Identify dependencies: if step-B needs the output of step-A, list it in dependsOn.
                3. Independent sub-problems should have empty dependsOn (can run in parallel).
                4. Mark "query": true for database queries, "query": false for calculations/comparisons.
                5. If the user asks about growth rate/comparison, you MUST include the base-period data.

                ## Example
                User: "Which grew faster in 2026, L6 or L7?"
                Output:
                {
                  "subProblems": [
                    {"id": "step-1", "description": "Query Li Auto L6 sales in 2026", "dependsOn": [], "query": true},
                    {"id": "step-2", "description": "Query Li Auto L7 sales in 2026", "dependsOn": [], "query": true},
                    {"id": "step-3", "description": "Query Li Auto L6 sales in 2025", "dependsOn": [], "query": true},
                    {"id": "step-4", "description": "Query Li Auto L7 sales in 2025", "dependsOn": [], "query": true},
                    {"id": "step-5", "description": "Calculate L6 YoY growth rate", "dependsOn": ["step-1", "step-3"], "query": false},
                    {"id": "step-6", "description": "Calculate L7 YoY growth rate", "dependsOn": ["step-2", "step-4"], "query": false},
                    {"id": "step-7", "description": "Compare growth rates and determine winner", "dependsOn": ["step-5", "step-6"], "query": false}
                  ]
                }

                ## Output Format
                Respond with ONLY a valid JSON object, no markdown, no extra text:
                {"subProblems": [{"id": "step-N", "description": "...", "dependsOn": [...], "query": true/false}]}
                """;
    }

    @SuppressWarnings("unchecked")
    private DecomposeResult parseResponse(String llmResponse) {
        try {
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "")
                           .replaceAll("```\\s*", "")
                           .trim();
            }

            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) map.get("subProblems");

            List<SubProblem> subProblems = new ArrayList<>();
            for (Map<String, Object> raw : rawList) {
                String id = (String) raw.get("id");
                String desc = (String) raw.get("description");
                List<String> dependsOn = (List<String>) raw.get("dependsOn");
                boolean query = Boolean.TRUE.equals(raw.get("query"));

                subProblems.add(new SubProblem(id, desc, dependsOn, query));
            }

            return new DecomposeResult(subProblems);
        } catch (JsonProcessingException | ClassCastException e) {
            log.error("解析 LLM 拆解返回失败: {}", llmResponse, e);
            throw new RuntimeException("子问题拆解 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
