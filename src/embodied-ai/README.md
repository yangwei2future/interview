# 具身智能数据管线 Demo

## 概述

本模块提供一个端到端的具身智能数据管线样例，覆盖 **仿真数据采集 → 格式转换与存储 → 数据清洗 → 自动标注** 的完整链路。

**适用场景：**
- 快速了解具身智能数据管线的全貌
- 为数据平台开发提供原型参考
- 面试准备：具身智能数据工程方向

## 环境搭建

### 1. 安装 ManiSkill 仿真环境

```bash
# 创建 Python 虚拟环境（推荐 Python 3.10）
conda create -n embodied-ai python=3.10
conda activate embodied-ai

# 安装 ManiSkill
pip install mani_skill

# 验证安装
python -c "import mani_skill; print(mani_skill.__version__)"
```

### 2. 安装本模块依赖

```bash
cd src/embodied-ai
pip install -r requirements.txt
```

### 3. （可选）配置 VLM 自动标注

```bash
export OPENAI_API_KEY="sk-xxx"   # GPT-4V 标注
# 或
export GEMINI_API_KEY="xxx"      # Gemini 标注
```

## 快速开始

### 一键运行全流程

```bash
# 采集 3 个 episode 并运行完整管线
python src/embodied-ai/pipeline.py --episodes 3

# 只运行清洗（跳过采集）
python src/embodied-ai/pipeline.py --skip-collection --episodes 3

# 跳过标注（避免 API 费用）
python src/embodied-ai/pipeline.py --episodes 5 --skip-annotation
```

### 分步运行

```bash
# Step 1: 数据采集
python src/embodied-ai/data_collection.py --episodes 3

# Step 2: 格式转换存储
python src/embodied-ai/data_storage.py

# Step 3: 数据清洗
python src/embodied-ai/data_cleaning.py

# Step 4: 自动标注（需要 API Key）
python src/embodied-ai/data_annotation.py
```

## 输出目录结构

```
data/embodied-ai/
├── raw/                     # 原始采集数据
│   └── episode_000/
│       ├── meta.json        # episode 元信息
│       ├── step_000/
│       │   ├── cam_high.jpg # 正面相机 RGB
│       │   ├── cam_wrist.jpg# 手腕相机 RGB
│       │   ├── depth_high.png
│       │   └── state.json   # 关节角度 + EE位姿
│       └── ...
├── processed/               # LeRobot 格式
│   ├── meta/info.json
│   └── data/chunk-000/
│       └── episode_000.parquet
├── clean/                   # 清洗后数据
│   ├── data/
│   └── cleaning_report.json # 清洗报告
└── annotated/               # 标注结果
    └── annotations.json     # 阶段分割标签
```

## 模块说明

| 文件 | 功能 |
|------|------|
| `embodied-ai-overview.md` | 具身智能数据管线知识笔记 |
| `config.py` | 全局配置（仿真、存储、清洗、标注参数） |
| `data_collection.py` | ManiSkill 仿真数据采集 |
| `data_storage.py` | 原始数据 → LeRobot 格式转换 |
| `data_cleaning.py` | 异常检测、Episode筛选、帧去重 |
| `data_annotation.py` | 基于 VLM 的自动操作阶段标注 |
| `pipeline.py` | 全流程编排主脚本 |

## 扩展方向

1. **Java 调度层**：用 Spring Boot + 线程池调度 Python 清洗/标注任务
2. **标注平台**：集成 LabelStudio 或自建标注后端
3. **数据版本管理**：基于 DVC 或自建版本控制
4. **实时质量监控**：Prometheus + Grafana 监控数据质量指标
