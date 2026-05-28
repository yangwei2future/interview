package com.example.rag.decompose;

import java.util.ArrayList;
import java.util.List;

/**
 * 子问题拆解模块的输出：子问题列表 + 依赖关系。
 *
 * <p>下游按拓扑排序执行：依赖全部完成的节点先执行，无依赖的节点可并行。</p>
 */
public class DecomposeResult {

    private final List<SubProblem> subProblems;

    public DecomposeResult(List<SubProblem> subProblems) {
        this.subProblems = new ArrayList<>(subProblems);
    }

    public List<SubProblem> getSubProblems() {
        return subProblems;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DecomposeResult{subProblems=[\n");
        for (SubProblem sp : subProblems) {
            sb.append("  ").append(sp).append("\n");
        }
        return sb.append("]}").toString();
    }
}
