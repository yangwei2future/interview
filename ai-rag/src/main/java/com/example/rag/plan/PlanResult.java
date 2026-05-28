package com.example.rag.plan;

import com.example.rag.decompose.SubProblem;
import com.example.rag.intent.PlanType;

import java.util.*;

/**
 * 规划结果：意图 + 子问题列表 + 分层调度计划 + 每个节点的执行路径。
 */
public class PlanResult {

    private final PlanType planType;
    private final String reasoning;
    private final List<SubProblem> subProblems;
    private final List<List<SubProblem>> schedule;
    private final Map<String, ExecutionPath> executionPaths;

    public PlanResult(PlanType planType, String reasoning,
                      List<SubProblem> subProblems,
                      List<List<SubProblem>> schedule) {
        this.planType = planType;
        this.reasoning = reasoning;
        this.subProblems = new ArrayList<>(subProblems);
        this.schedule = new ArrayList<>(schedule);
        this.executionPaths = new HashMap<>();
        for (SubProblem sp : subProblems) {
            executionPaths.put(sp.getId(), sp.isQuery() ? ExecutionPath.QUERY : ExecutionPath.COMPUTE);
        }
    }

    public PlanType getPlanType() { return planType; }
    public String getReasoning() { return reasoning; }
    public List<SubProblem> getSubProblems() { return subProblems; }
    public List<List<SubProblem>> getSchedule() { return schedule; }
    public int getTotalLayers() { return schedule.size(); }

    /** 查询路径节点数 */
    public long getQueryCount() {
        return subProblems.stream().filter(SubProblem::isQuery).count();
    }

    /** 计算路径节点数 */
    public long getComputeCount() {
        return subProblems.stream().filter(sp -> !sp.isQuery()).count();
    }

    /** 获取指定节点的执行路径 */
    public ExecutionPath getPath(String nodeId) {
        return executionPaths.getOrDefault(nodeId, ExecutionPath.QUERY);
    }
}
