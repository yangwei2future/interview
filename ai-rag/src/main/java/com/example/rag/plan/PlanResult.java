package com.example.rag.plan;

import com.example.rag.decompose.SubProblem;
import com.example.rag.intent.PlanType;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划结果：意图 + 子问题列表 + 分层调度计划。
 */
public class PlanResult {

    private final PlanType planType;
    private final String reasoning;
    private final List<SubProblem> subProblems;
    private final List<List<SubProblem>> schedule;

    public PlanResult(PlanType planType, String reasoning,
                      List<SubProblem> subProblems,
                      List<List<SubProblem>> schedule) {
        this.planType = planType;
        this.reasoning = reasoning;
        this.subProblems = new ArrayList<>(subProblems);
        this.schedule = new ArrayList<>(schedule);
    }

    public PlanType getPlanType() { return planType; }
    public String getReasoning() { return reasoning; }
    public List<SubProblem> getSubProblems() { return subProblems; }
    public List<List<SubProblem>> getSchedule() { return schedule; }
    public int getTotalLayers() { return schedule.size(); }
}
