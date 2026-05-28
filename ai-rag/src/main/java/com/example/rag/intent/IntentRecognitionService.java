package com.example.rag.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.deepseek.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 意图识别服务。
 *
 * <p>输入自然语言 query，输出 IntentResult（plan_type + reasoning）。
 * 通过 System Prompt 让 LLM 做分类判断，返回 JSON 格式结果。</p>
 */
@Service
public class IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionService.class);

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public IntentRecognitionService(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 识别用户意图，判断是一次性规划还是动态规划。
     *
     * @param userQuery 用户自然语言问题
     * @return 意图识别结果
     */
    public IntentResult recognize(String userQuery) {
        log.info("意图识别开始: query={}", userQuery);

        String llmResponse = deepSeekClient.chat(buildSystemPrompt(), userQuery);
        log.info("LLM 原始返回: {}", llmResponse);

        return parseResponse(llmResponse);
    }

    /**
     * 构造 System Prompt：定义分类规则和输出格式。
     */
    private String buildSystemPrompt() {
        return """
                You are a data analysis agent. Your ONLY job is to classify the user's question.

                ## Classification Rules

                **SINGLE_TURN** — The target is clear from the start.
                - All necessary information (what, which entity, which time range) is determined.
                - You don't need intermediate results to decide the next step.
                - Example: "What are the sales of Li Auto L6 in 2026?"
                - Example: "How much GMV did we have yesterday?"

                **MULTI_TURN** — Need to explore first, decide next step based on intermediate results.
                - The target is vague and requires exploration before being determined.
                - Involves comparison of multiple entities.
                - Involves causal analysis ("why" questions).
                - Example: "Why did Li Auto L6 sales drop?"
                - Example: "Which car model is growing fastest?"

                ## Output Format

                Respond with ONLY a valid JSON object, no markdown, no extra text:
                {"plan_type": "SINGLE_TURN", "reasoning": "..."}
                or
                {"plan_type": "MULTI_TURN", "reasoning": "..."}
                """;
    }

    /**
     * 解析 LLM 返回的 JSON 字符串为 IntentResult。
     */
    private IntentResult parseResponse(String llmResponse) {
        try {
            // 兼容 LLM 可能在 JSON 外面包 markdown 代码块的情况
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "")
                           .replaceAll("```\\s*", "")
                           .trim();
            }

            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(json, Map.class);

            String planTypeStr = map.get("plan_type");
            String reasoning = map.getOrDefault("reasoning", "");

            PlanType planType = PlanType.valueOf(planTypeStr.toUpperCase());
            return new IntentResult(planType, reasoning);
        } catch (JsonProcessingException e) {
            log.error("解析 LLM 返回失败，默认 SINGLE_TURN: {}", llmResponse, e);
            return new IntentResult(PlanType.SINGLE_TURN,
                    "LLM 返回格式异常，默认按一次性规划处理: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("无法识别的 plan_type，默认 SINGLE_TURN: {}", llmResponse, e);
            return new IntentResult(PlanType.SINGLE_TURN,
                    "LLM 返回了未识别的 plan_type，默认按一次性规划处理: " + e.getMessage());
        }
    }
}
