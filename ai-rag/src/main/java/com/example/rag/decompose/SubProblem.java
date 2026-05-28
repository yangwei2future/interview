package com.example.rag.decompose;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 中的一个子问题节点。
 *
 * <p>下游拿到 SubProblem 后，把它当作一个独立的"简单问题"，
 * 走 search_indicator → get_table_schema → execute_sql 的标准链。</p>
 */
public class SubProblem {

    /** 唯一标识，如 "step-1" */
    private final String id;

    /** 自然语言描述，下游直接作为子查询的输入 */
    private final String description;

    /** 依赖的步骤 ID 列表，为空表示可立即执行 */
    private final List<String> dependsOn;

    /** true = 需要查数据库，false = 纯计算/推理 */
    private final boolean query;

    public SubProblem(String id, String description, List<String> dependsOn, boolean query) {
        this.id = id;
        this.description = description;
        this.dependsOn = dependsOn != null ? new ArrayList<>(dependsOn) : new ArrayList<>();
        this.query = query;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public List<String> getDependsOn() { return dependsOn; }
    public boolean isQuery() { return query; }

    /** 所有依赖是否已满足？ */
    public boolean isReady() {
        return dependsOn.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("%s: \"%s\" dependsOn=%s query=%s",
                id, description, dependsOn, query);
    }
}
