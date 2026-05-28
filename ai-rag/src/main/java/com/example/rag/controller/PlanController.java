package com.example.rag.controller;

import com.example.rag.decompose.SubProblem;
import com.example.rag.plan.PlanResult;
import com.example.rag.plan.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一规划接口。
 *
 * <pre>
 * POST /api/plan
 * Body: {"query": "2026年理想L6的销量是多少？"}
 *
 * Response:
 * {
 *   "query": "...",
 *   "planType": "SINGLE_TURN",
 *   "reasoning": "...",
 *   "totalLayers": 1,
 *   "totalSubProblems": 1,
 *   "schedule": [
 *     {"layer": 0, "nodes": ["step-1"], "details": [...], "parallel": true}
 *   ]
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }

        PlanResult result = planService.plan(query);

        // 构建分层调度明细
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (int i = 0; i < result.getSchedule().size(); i++) {
            List<SubProblem> layer = result.getSchedule().get(i);
            List<Map<String, Object>> details = new ArrayList<>();
            for (SubProblem sp : layer) {
                details.add(Map.of(
                        "id", sp.getId(),
                        "description", sp.getDescription(),
                        "dependsOn", sp.getDependsOn(),
                        "query", sp.isQuery()
                ));
            }
            schedule.add(Map.of(
                    "layer", i,
                    "parallel", layer.size() > 1,
                    "nodeCount", layer.size(),
                    "nodes", details
            ));
        }

        return Map.of(
                "query", query,
                "planType", result.getPlanType().name(),
                "reasoning", result.getReasoning(),
                "totalLayers", result.getTotalLayers(),
                "totalSubProblems", result.getSubProblems().size(),
                "schedule", schedule
        );
    }
}
