# macOS Miniconda 安装与配置完整指南

> 适用于 macOS（Apple Silicon / Intel），涵盖安装、镜像源配置、环境管理全流程。

## Anaconda vs Miniconda，选哪个？

| | Anaconda | Miniconda |
|------|---------|----------|
| 安装包大小 | ~700MB | ~50MB |
| 预装包 | 150+ 数据科学包 | 仅 conda + python |
| 启动速度 | 慢 | 快 |
| 适合人群 | 新手想一键全要 | **推荐——按需安装，干净可控** |

结论：**选 Miniconda**。装完按需 `conda install`，不让不需要的 150 个包占你硬盘。

## Step 1：下载安装

```bash
# 先确认你的 Mac 芯片类型
uname -m
# arm64  → Apple Silicon（M1/M2/M3/M4）
# x86_64 → Intel 芯片
```

根据芯片类型选择下载链接：

```bash
# Apple Silicon（M 系列芯片）
curl -O https://repo.anaconda.com/miniconda/Miniconda3-latest-MacOSX-arm64.sh

# Intel 芯片
curl -O https://repo.anaconda.com/miniconda/Miniconda3-latest-MacOSX-x86_64.sh
```

运行安装：

```bash
bash Miniconda3-latest-MacOSX-*.sh
```

安装过程交互说明：

| 步骤 | 操作 |
|------|------|
| 查看许可协议 | 一直按回车或按 `q` 跳过 |
| 接受许可 | 输入 `yes` |
| 安装路径 | 回车用默认路径（`~/miniconda3`） |
| 自动初始化 | 输入 `yes`（让 conda 在终端启动时自动加载） |

装完后**关掉终端重新打开**，验证：

```bash
conda --version
# conda 24.x.x
```

## Step 2：配置国内镜像源

conda 默认从国外服务器下载，换成清华镜像速度提升明显：

```bash
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main/
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/free/
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/conda-forge/
conda config --set show_channel_urls yes
```

pip 也一并换了：

```bash
pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
```

查看当前配置：

```bash
conda config --show channels
```

## Step 3：环境管理常用命令

```bash
# 创建环境（指定 Python 版本）
conda create -y -n 环境名 python=3.10

# 激活环境
conda activate 环境名

# 查看所有环境
conda env list

# 查看当前环境装了哪些包
conda list

# 退出当前环境
conda deactivate

# 删除环境（装坏了重来）
conda env remove -n 环境名
```

## Step 4：实战——创建一个 Python 3.10 环境

```bash
conda create -y -n myenv python=3.10
conda activate myenv
python --version   # Python 3.10.x
```

## 如何彻底卸载

```bash
# 删除 conda 安装目录
rm -rf ~/miniconda3

# 删除配置文件
rm -rf ~/.conda ~/.condarc

# 从 shell 配置中移除 conda 初始化（~/.zshrc 中搜索 conda 删掉对应行）
sed -i '' '/>>> conda initialize >>>/,/<<< conda initialize <<</d' ~/.zshrc
```

## 常见踩坑

| 问题 | 解决 |
|------|------|
| `conda: command not found` | 重新打开终端，或执行 `source ~/.zshrc` |
| 下载速度慢 | 检查镜像源是否配置成功：`conda config --show channels` |
| `conda activate` 报错 | 执行 `conda init zsh` 然后重启终端 |
| 安装包冲突 | 用 `conda install` 代替 `pip install`；优先用 conda-forge 频道 |

## 可选：禁止 conda 自动激活 base 环境

默认打开终端会自动进入 base 环境，不想要的话：

```bash
conda config --set auto_activate_base false
```
