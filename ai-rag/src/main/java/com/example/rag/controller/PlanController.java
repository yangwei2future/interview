package com.example.rag.controller;

import com.example.rag.decompose.SubProblem;
import com.example.rag.plan.DagExecutor;
import com.example.rag.plan.PlanResult;
import com.example.rag.plan.PlanService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;
    private final DagExecutor dagExecutor;

    public PlanController(PlanService planService, DagExecutor dagExecutor) {
        this.planService = planService;
        this.dagExecutor = dagExecutor;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }

        PlanResult result = planService.plan(query);

        // 构建分层调度明细，区分 QUERY vs COMPUTE 路径
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (int i = 0; i < result.getSchedule().size(); i++) {
            List<SubProblem> layer = result.getSchedule().get(i);
            List<Map<String, Object>> details = new ArrayList<>();
            for (SubProblem sp : layer) {
                String execPath = sp.isQuery() ? "QUERY" : "COMPUTE";
                String execPipeline = sp.isQuery()
                        ? "检索指标 → 获取Schema → LLM生成SQL → 执行SQL"
                        : "等待依赖结果 → LLM计算/推理";
                details.add(Map.of(
                        "id", sp.getId(),
                        "description", sp.getDescription(),
                        "execPath", execPath,
                        "execPipeline", execPipeline,
                        "dependsOn", sp.getDependsOn()
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
                "queryCount", result.getQueryCount(),
                "computeCount", result.getComputeCount(),
                "schedule", schedule
        );
    }

    /**
     * 一键规划 + 执行。
     *
     * <pre>
     * POST /api/execute
     * Body: {"query": "2026年L6和L7哪个增长更快？"}
     * </pre>
     */
    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }

        // 1. 规划
        PlanResult plan = planService.plan(query);

        // 2. 逐层执行
        long start = System.currentTimeMillis();
        Map<String, Object> results = dagExecutor.execute(plan.getSchedule());
        long elapsed = System.currentTimeMillis() - start;

        // 3. 组装每层执行日志
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int i = 0; i < plan.getSchedule().size(); i++) {
            List<SubProblem> layer = plan.getSchedule().get(i);
            List<Map<String, Object>> nodeResults = new ArrayList<>();
            for (SubProblem sp : layer) {
                nodeResults.add(Map.of(
                        "id", sp.getId(),
                        "description", sp.getDescription(),
                        "execPath", sp.isQuery() ? "QUERY" : "COMPUTE",
                        "dependsOn", sp.getDependsOn(),
                        "result", Objects.toString(results.get(sp.getId()), "null")
                ));
            }
            layers.add(Map.of(
                    "layer", i,
                    "parallel", layer.size() > 1,
                    "nodes", nodeResults
            ));
        }

        return Map.of(
                "query", query,
                "planType", plan.getPlanType().name(),
                "totalLayers", plan.getTotalLayers(),
                "elapsedMs", elapsed,
                "layers", layers
        );
    }
}
