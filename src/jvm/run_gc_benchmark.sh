#!/bin/bash
# ============================================================
# GC 调优对比实验一键运行脚本
# 用法: bash src/jvm/run_gc_benchmark.sh [cache|transient]
# 默认: cache 模式
# ============================================================

set -e

MODE="${1:-cache}"
BASE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
MAIN_CLASS="src/jvm/GCTuningBenchmark.java"
HEAP_SIZE="256m"
RUN_TIME=20  # 秒（越久数据越稳，但等得也久）

mkdir -p /tmp/gc-benchmark

echo "========================================"
echo "  GC 调优对比实验"
echo "  模式: ${MODE}"
echo "  堆大小: ${HEAP_SIZE}"
echo "  运行时长: ${RUN_TIME}秒"
echo "========================================"
echo ""

run_test() {
    local GC_NAME="$1"
    local GC_FLAG="$2"
    local LOG_FILE="/tmp/gc-benchmark/gc-${GC_NAME}-${MODE}.log"

    echo "----------------------------------------"
    echo "  正在运行: ${GC_NAME}"
    echo "  参数: ${GC_FLAG}"
    echo "  GC日志: ${LOG_FILE}"
    echo "----------------------------------------"

    cd "${BASE_DIR}"
    timeout "${RUN_TIME}" \
        /usr/bin/java \
        -Xms${HEAP_SIZE} -Xmx${HEAP_SIZE} \
        ${GC_FLAG} \
        -Xlog:gc*:file=${LOG_FILE}:time \
        ${MAIN_CLASS} ${MODE} 2>&1 | grep -v "^Java:" | grep -v "^堆大小:" | grep -v "^进程" | grep -v "^当前GC" | grep -v "^缓存已预热" | grep -v "^启动" | grep -v "========================================"

    echo ""
}

# ---- 1. Serial GC ----
run_test "serial" "-XX:+UseSerialGC"

# ---- 2. Parallel GC ----
run_test "parallel" "-XX:+UseParallelGC"

# ---- 3. G1 GC（默认） ----
run_test "g1" "-XX:+UseG1GC"

# ---- 4. G1 GC + 大新生代 ----
# -Xmn 设置新生代大小，减少 Minor GC 频率但增加单次停顿
run_test "g1-xmn128m" "-XX:+UseG1GC -Xmn128m"

# ---- 5. Parallel GC + 调优（调整吞吐量参数） ----
run_test "parallel-tuned" "-XX:+UseParallelGC -XX:ParallelGCThreads=4 -XX:GCTimeRatio=19"

echo "========================================"
echo "  所有测试完成！"
echo ""
echo "  GC 日志: /tmp/gc-benchmark/"
echo "  分析命令:"
echo "    grep -E \"Pause|GC\\(\\d+\\)\" /tmp/gc-benchmark/gc-*.log | head -20"
echo "    cat /tmp/gc-benchmark/gc-g1-cache.log | grep \"Pause Young\" | head -5"
echo "========================================"
