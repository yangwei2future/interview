package com.example.rag.controller;

import com.example.rag.intent.IntentRecognitionService;
import com.example.rag.intent.IntentResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 意图识别测试接口。
 *
 * <pre>
 * POST /api/intent/recognize
 * Body: {"query": "2026年理想L6的销量是多少？"}
 *
 * Response:
 * {
 *   "query": "2026年理想L6的销量是多少？",
 *   "planType": "SINGLE_TURN",
 *   "singleTurn": true,
 *   "reasoning": "目标(销量)、对象(L6)、时间(2026)已全部确定"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/intent")
public class IntentController {

    private final IntentRecognitionService intentService;

    public IntentController(IntentRecognitionService intentService) {
        this.intentService = intentService;
    }

    @PostMapping("/recognize")
    public Map<String, Object> recognize(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }

        IntentResult result = intentService.recognize(query);

        return Map.of(
                "query", query,
                "planType", result.getPlanType().name(),
                "singleTurn", result.isSingleTurn(),
                "reasoning", result.getReasoning()
        );
    }
}
