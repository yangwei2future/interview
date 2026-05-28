package com.example.rag.plan;

import com.example.rag.decompose.*;
import com.example.rag.intent.IntentRecognitionService;
import com.example.rag.intent.IntentResult;
import com.example.rag.intent.PlanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一规划服务：意图识别 → 拆解 → DAG 调度，一条链走到底。
 *
 * <p>输入自然语言 query，输出结构化的 DAG 执行计划。</p>
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final IntentRecognitionService intentService;
    private final DecomposeService decomposeService;

    public PlanService(IntentRecognitionService intentService,
                       DecomposeService decomposeService) {
        this.intentService = intentService;
        this.decomposeService = decomposeService;
    }

    /**
     * 完整规划链路。
     *
     * @param userQuery 用户自然语言问题
     * @return 带意图标签的调度计划
     */
    public PlanResult plan(String userQuery) {
        log.info("开始规划: query={}", userQuery);

        // 1. 意图识别
        IntentResult intent = intentService.recognize(userQuery);
        log.info("意图识别: planType={}", intent.getPlanType());

        // 2. 根据意图类型决定后续
        DecomposeResult decomposeResult;
        if (intent.isSingleTurn()) {
            decomposeResult = singleStepDag(userQuery);
        } else {
            decomposeResult = decomposeService.decompose(userQuery);
        }

        // 3. DAG 调度
        List<List<SubProblem>> levels = DagScheduler.schedule(decomposeResult.getSubProblems());

        return new PlanResult(intent.getPlanType(), intent.getReasoning(),
                decomposeResult.getSubProblems(), levels);
    }

    /**
     * SINGLE_TURN 时构造单节点 DAG。
     */
    private DecomposeResult singleStepDag(String query) {
        SubProblem single = new SubProblem("step-1", query, List.of(), true);
        return new DecomposeResult(List.of(single));
    }
}
