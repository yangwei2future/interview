package com.example.rag.intent;

/**
 * 意图识别模块的输出结果。
 *
 * <p>职责：判断用户问题的复杂度，决定走哪条路径。
 * 不做拆解、不做规划、不做任何实际查询。</p>
 */
public class IntentResult {

    /** 规划类型 */
    private final PlanType planType;

    /** 判断理由（调试/日志用） */
    private final String reasoning;

    public IntentResult(PlanType planType, String reasoning) {
        this.planType = planType;
        this.reasoning = reasoning;
    }

    public PlanType getPlanType() {
        return planType;
    }

    public String getReasoning() {
        return reasoning;
    }

    public boolean isSingleTurn() {
        return planType == PlanType.SINGLE_TURN;
    }

    public boolean isMultiTurn() {
        return planType == PlanType.MULTI_TURN;
    }

    @Override
    public String toString() {
        return "IntentResult{planType=" + planType + ", reasoning='" + reasoning + "'}";
    }
}
