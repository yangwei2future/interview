package com.example.rag.plan;

import com.example.rag.decompose.SubProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * DAG 执行器：按层执行，层内并行、层间串行，结果在层间传递。
 *
 * <pre>
 * Layer 0: [step-1, step-2, step-3, step-4]  4 QUERY 并行
 *          ↓ 全部完成，结果写入 results Map
 * Layer 1: [step-5, step-6]                   2 COMPUTE 并行
 *          ↓ 从 results 读 Layer 0 的输出
 * Layer 2: [step-7]                            1 COMPUTE
 *          ↓ 从 results 读 Layer 1 的输出
 * 返回最终结果
 * </pre>
 */
@Service
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);

    private final NodeExecutor nodeExecutor;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public DagExecutor(NodeExecutor nodeExecutor) {
        this.nodeExecutor = nodeExecutor;
    }

    /**
     * 执行完整 DAG，逐层推进。
     *
     * @param schedule 分层调度计划
     * @return 每个节点的执行结果
     */
    public Map<String, Object> execute(List<List<SubProblem>> schedule) {
        log.info("DAG 执行开始，共 {} 层", schedule.size());
        nodeExecutor.clear();

        for (int layerIdx = 0; layerIdx < schedule.size(); layerIdx++) {
            List<SubProblem> layer = schedule.get(layerIdx);
            log.info("--- Layer {}: {} 个节点 ---", layerIdx, layer.size());

            if (layer.size() == 1) {
                // 单节点：直接执行
                Object result = nodeExecutor.execute(layer.get(0));
                log.info("  {} -> {}", layer.get(0).getId(),
                        result.toString().substring(0, Math.min(80, result.toString().length())));
            } else {
                // 多节点：并行执行
                List<Future<Object>> futures = new ArrayList<>();
                for (SubProblem node : layer) {
                    futures.add(pool.submit(() -> nodeExecutor.execute(node)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        Object result = futures.get(i).get(30, TimeUnit.SECONDS);
                        log.info("  {} -> {}", layer.get(i).getId(),
                                result.toString().substring(0, Math.min(80, result.toString().length())));
                    } catch (Exception e) {
                        log.error("Layer {} node {} 执行失败", layerIdx, layer.get(i).getId(), e);
                    }
                }
            }
        }

        log.info("DAG 执行完成");
        return nodeExecutor.getAllResults();
    }
}
