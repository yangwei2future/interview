# 摘要
由于商业等系列原因，公司内不允许使用Anaconda、minConda等商用环境管理工具，如果使用python虚拟环境管理，通常每个项目下都有一个虚拟环境，但其实很多项目的依赖相差不大，完全可复用，无形提高了大家的环境管理了成本。因此，通过调研后，可采纳安装Miniforge无缝回到原来的Conda管理方式。

# 安装教程

## 下载安装包
访问 [Miniforge](https://github.com/conda-forge/miniforge/releases) 的GitHub发布页面，下载 `Miniforge3-MacOSX-arm64.sh` 文件。请务必选择适配Apple Silicon的版本。

## 终端安装
打开终端，进入安装包所在目录（例如在`~/Downloads`目录，就输入`cd ~/Downloads`），然后运行以下命令（具体文件名可能因版本略有不同，请以实际下载为准）：

```shell
bash Miniforge3-MacOSX-arm64.sh
```

按照提示一路回车（Enter），看到提示时输入 `yes` 确认安装，安装程序可能会询问安装路径，一般直接回车使用默认路径即可。

## 完成安装
安装完成后，可以关闭终端并重新打开，或者输入 `source ~/.zshrc` 来重新加载配置。之后，你应该就可以使用 `conda` 命令了。可以通过 `conda --version` 命令验证是否安装成功。

## （可选）关闭自动激活base环境
安装后，默认可能会自动进入名为 `base` 的conda环境。如果你不希望这样，可以运行以下命令关闭：

```shell
conda config --set auto_activate_base false
```

之后需要手动激活环境（使用 `conda activate`）时才启用conda。

# 配置Conda与虚拟环境
安装好Conda后，进行一些基础配置能让后续使用更顺畅。

## 更换国内镜像源
为了提升包的下载速度，可以考虑配置国内的镜像源，例如清华镜像源。在终端中依次执行以下命令即可添加：

```shell
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/free/
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main/
conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud/conda-forge/
conda config --set show_channel_urls yes
```

## 创建并使用虚拟环境
强烈建议为不同的项目创建独立的虚拟环境，以避免包版本冲突。

- 创建环境：例如，创建一个名为 `my_env`，Python版本为3.9的环境：

```shell
conda create -n my_env python=3.9
```

- 激活环境：

```shell
conda activate my_env
```

- 激活后，终端的命令提示符前通常会显示当前环境名，如 `(my_env)`。

- 安装包：在激活的环境中，就可以安装需要的包了，例如：

```shell
conda install numpy pandas
```

- 退出环境：

```shell
conda deactivate
```

- 查看所有环境：

```shell
conda env list
```

- 删除环境：

```shell
conda env remove -n my_env
```
