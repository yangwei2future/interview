package com.example.rag.controller;

import com.example.rag.decompose.DecomposeResult;
import com.example.rag.decompose.DecomposeService;
import com.example.rag.decompose.SubProblem;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 子问题拆解测试接口。
 *
 * <pre>
 * POST /api/decompose
 * Body: {"query": "2026年L6和L7哪个增长更快？"}
 *
 * Response:
 * {
 *   "query": "...",
 *   "subProblems": [
 *     {"id": "step-1", "description": "...", "dependsOn": [], "query": true, "ready": true},
 *     ...
 *   ]
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class DecomposeController {

    private final DecomposeService decomposeService;

    public DecomposeController(DecomposeService decomposeService) {
        this.decomposeService = decomposeService;
    }

    @PostMapping("/decompose")
    public Map<String, Object> decompose(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }

        DecomposeResult result = decomposeService.decompose(query);

        List<Map<String, Object>> subList = new ArrayList<>();
        for (SubProblem sp : result.getSubProblems()) {
            subList.add(Map.of(
                    "id", sp.getId(),
                    "description", sp.getDescription(),
                    "dependsOn", sp.getDependsOn(),
                    "query", sp.isQuery(),
                    "ready", sp.isReady()
            ));
        }

        return Map.of(
                "query", query,
                "subProblemCount", subList.size(),
                "subProblems", subList
        );
    }
}
