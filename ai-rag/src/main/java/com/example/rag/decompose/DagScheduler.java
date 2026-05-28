package com.example.rag.decompose;

import java.util.*;

/**
 * DAG 调度器：拓扑排序 + 按层级分组。
 *
 * <p>输入子问题列表（含 dependsOn 依赖声明），
 * 输出分层执行计划：每层内部可并行，层与层之间串行等待。</p>
 *
 * <pre>
 * 输入：
 *   step-1 dependsOn=[]      → 第1层
 *   step-2 dependsOn=[]      → 第1层（和 step-1 并行）
 *   step-5 dependsOn=[1,3]   → 第2层（等 1+3 完成）
 *   step-7 dependsOn=[5,6]   → 第3层（等 5+6 完成）
 *
 * 输出：
 *   levels[0] = [step-1, step-2, step-3, step-4]  可并行
 *   levels[1] = [step-5, step-6]                   等第0层
 *   levels[2] = [step-7]                            等第1层
 * </pre>
 */
public class DagScheduler {

    private DagScheduler() {}

    /**
     * 拓扑排序 + 分层。
     *
     * @param subProblems 子问题列表
     * @return 按层组织的调度计划，每层可并行执行
     * @throws IllegalStateException 如果存在循环依赖
     */
    public static List<List<SubProblem>> schedule(List<SubProblem> subProblems) {
        // 1. 构建入度表 + 后继表
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, SubProblem> nodeMap = new HashMap<>();

        for (SubProblem sp : subProblems) {
            nodeMap.put(sp.getId(), sp);
            inDegree.putIfAbsent(sp.getId(), 0);
            successors.putIfAbsent(sp.getId(), new ArrayList<>());

            // sp 依赖谁 → 谁的后继就是 sp
            for (String dep : sp.getDependsOn()) {
                successors.putIfAbsent(dep, new ArrayList<>());
                successors.get(dep).add(sp.getId());
                // sp 需要一个前置节点完成 → sp 的入度 +1
                inDegree.merge(sp.getId(), 1, Integer::sum);
            }
        }

        // 2. 入度为 0 的节点作为起始层
        Queue<String> queue = new LinkedList<>();
        for (String id : inDegree.keySet()) {
            if (inDegree.get(id) == 0) {
                queue.offer(id);
            }
        }

        // 3. 逐层推进
        List<List<SubProblem>> levels = new ArrayList<>();
        int completed = 0;

        while (!queue.isEmpty()) {
            int layerSize = queue.size();
            List<SubProblem> currentLayer = new ArrayList<>();

            for (int i = 0; i < layerSize; i++) {
                String id = queue.poll();
                SubProblem node = nodeMap.get(id);
                if (node != null) {
                    currentLayer.add(node);
                    completed++;

                    // 当前节点完成，后继节点入度 -1
                    for (String nextId : successors.getOrDefault(id, Collections.emptyList())) {
                        int newDegree = inDegree.merge(nextId, -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.offer(nextId);
                        }
                    }
                }
            }
            levels.add(currentLayer);
        }

        // 4. 循环依赖检测
        if (completed != subProblems.size()) {
            Set<String> allIds = nodeMap.keySet();
            for (SubProblem sp : subProblems) {
                if (!inDegree.containsKey(sp.getId()) || inDegree.get(sp.getId()) > 0) {
                    throw new IllegalStateException(
                            "DAG 存在循环依赖，无法调度节点: " + sp.getId()
                                    + " dependsOn=" + sp.getDependsOn());
                }
            }
        }

        return levels;
    }
}
